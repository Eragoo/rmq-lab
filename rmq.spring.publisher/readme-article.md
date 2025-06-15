# Spring RabbitTemplate High-Performance Publishing

When building high-throughput messaging systems with RabbitMQ, the choice of publishing strategy can dramatically impact both performance and reliability, especially when you rely on abstractions like Spring RabbitTemplate. I've performed some tests which led me to dig into documentation and source code to understand the behavior behind the scenes, and today I want to share my observations.

## Simplest Confirmations with Callbacks

The foundation of reliable RabbitMQ publishing starts with publisher confirmations. Here's a practical example that demonstrates how to track individual message acknowledgments:

```kotlin
rabbitTemplate.invoke ({ channel ->
    chunk.forEach { message ->
        channel.convertAndSend(
            RabbitMQConfig.EXCHANGE_NAME,
            RabbitMQConfig.ROUTING_KEY,
            message
        )
    }
    channel.waitForConfirmsOrDie(10_000)
},
    { deliveryTag, multiple ->
        println("ACK tag: $deliveryTag multiple: $multiple")
    },
    { deliveryTag, multiple ->
        println("NACK tag $deliveryTag multiple: $multiple")
    }
)
```

**Key Details:**
- **Delivery Tags**: Each message gets a unique delivery tag that's scoped to the channel
- **Callbacks**: Separate handlers for ACK (success) and NACK (failure) responses
- **waitForConfirms**: Essential for ensuring all callbacks are triggered before proceeding and returning channel to the cache

To be honest, it's almost the same as just using waitForConfirms, but here you get some observability on failures and can theoretically even retry if you keep a mapping of message to channel + delivery tag. 

## Correlated Async ACK: Non-Blocking Individual Message Tracking

For granular control, correlated publisher confirmations offer asynchronous, non-blocking message tracking:

```kotlin
fun publishWithAsyncAck(messages: List<Message>) {
    val template = createAsyncTemplate()
    
    measureTime {
        template.invoke { channel ->
            messages.forEach { message ->
                val correlationData = CorrelationData(message.id)
                pendingConfirmations[message.id] = message
                
                channel.convertAndSend(
                    RabbitMQConfig.EXCHANGE_NAME,
                    RabbitMQConfig.ROUTING_KEY,
                    message,
                    correlationData
                )
            }
        }
    }.let { millis ->
        println("Published ${messages.size} messages in $millis")
        println("Confirmations will arrive via callbacks")
    }
}

private fun createAsyncTemplate(): RabbitTemplate {
    val template = RabbitTemplate(connectionFactory)
    template.messageConverter = Jackson2JsonMessageConverter()
    template.setMandatory(true)
    
    template.setConfirmCallback { correlationData, ack, cause ->
        val messageId = correlationData?.id
        val message = messageId?.let { pendingConfirmations.remove(it) }
        
        if (!ack && message != null) {
            println("❌ Message ${message.id} NACKed: $cause")
            retry(message)
        }
    }
    
    return template
}
```

**Advantages of Correlated Async ACK:**
- **Non-blocking**: No `waitForConfirms()` calls blocking the publishing thread
- **Individual tracking**: Each message tracked by its unique correlation ID
- **Granular retry**: Only failed messages need to be retried, not entire batches

## Channel Churn: The Hidden Performance Killer

One of the most critical discoveries is how **channel churn** can destroy publishing performance, and how the `invoke()` function prevents it.

### Performance Issue with confirm-type: correlated

```kotlin
// ❌ Problematic - creates channel churn
messages.forEach { message ->
    template.convertAndSend(exchange, routingKey, message) // Each call = potential new channel
}
```

According to the [RabbitMQ Java Client API Guide](https://www.rabbitmq.com/client-libraries/java-api-guide#concurrency-considerations-thread-safety), publisher confirmations are **per-channel** due to delivery tags being channel-scoped in the AMQP protocol:

- Each `convertAndSend()` call may checkout a new channel from the cache
- Channels with pending confirmations cannot be immediately returned to cache
- High-throughput publishing can exhaust the channel cache
- New channels get created when cache is full, leading to **channel churn** and potential **OOM exceptions**

```kotlin
// ✅ Correct - uses single channel for all operations
template.invoke { channel ->
    messages.forEach { message ->
        channel.convertAndSend(exchange, routingKey, message) // All use same channel
    }
}
```

The `invoke()` function ensures:
- All operations use the **same dedicated channel**
- Single channel handles all messages and their confirmations
- Eliminates channel create/close overhead
- Prevents memory issues from excessive channel creation

## Publisher Confirm Types: Simple vs Correlated

The choice between `simple` and `correlated` publisher confirmation types affects both functionality and channel behavior:

### Simple Confirmations
```yaml
spring:
  rabbitmq:
    publisher-confirm-type: simple
```
You can have confirm-type: simple and still use correlation data, but you might experience an issue due to a bug in Spring RabbitTemplate: with confirm-type: simple, channels are returned to cache immediately if you don't tell them to wait for confirmations (with waitForConfirm for example).

### Correlated Confirmations
```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated
```

There is no such issue for confirm-type: correlated, but since channels are held until all confirmations arrive with the default configuration, you might experience performance issues due to channel churn.

## Bonus: Why Channel Limits Matter for Performance

A lesser-known performance consideration is limiting the number of channels in your connection pool. Here's why this matters:

From the [Spring AMQP Documentation](https://docs.spring.io/spring-amqp/docs/current/reference/html/#connection-and-resource-management):

> "The cache size is (by default) not a limit but is merely the number of channels that can be cached. With a cache size of, say, 10, any number of channels can actually be in use."

### Unlimited vs Limited Channel Configuration

**Unlimited Channels (Default)**:
```yaml
spring:
  rabbitmq:
    cache:
      channel:
        size: 3
        checkout-timeout: 0  # Unlimited channel creation
```

- ✅ No blocking - immediate channel access
- ❌ Higher memory usage - unlimited channel creation
- ❌ Potential resource exhaustion under high load

**Limited Channels**:
```yaml
spring:
  rabbitmq:
    cache:
      channel:
        size: 3
        checkout-timeout: 5000  # 5 seconds timeout
```

- ✅ Controlled memory usage - strict channel limits
- ✅ Predictable resource consumption
- ❌ Potential blocking - threads may wait or timeout

### Practical Example

The demo classes in this project (`UnlimitedChannelDemo` and `LimitedChannelDemo`) demonstrate these behaviors with 4 competing threads. The unlimited configuration allows immediate channel creation, while the limited configuration enforces timeouts when all channels are busy.

**When to limit channels:**
- High-load production systems requiring resource control
- When you need predictable memory consumption
- Applications with many concurrent publishing threads

**When to use unlimited:**
- Low to medium load scenarios
- Development and testing environments
- When thread blocking is unacceptable

## Key Takeaways

1. **Monitor your RabbitMQ stats**
2. **Async ACK offers the best balance**
3. **Channel limits matter**
4. **Measure everything**

The RabbitMQ Java client's threading model, with separate I/O threads and consumer thread pools, enables these optimizations to work effectively. Understanding these patterns can transform your messaging system from a performance bottleneck into a high-throughput, reliable component of your architecture.

## References

- [RabbitMQ Java Client API Guide - Consumer Operation Thread Pool](https://www.rabbitmq.com/client-libraries/java-api-guide#consumer-operation-thread-pool)
- [RabbitMQ Java Client API Guide - Concurrency Considerations](https://www.rabbitmq.com/client-libraries/java-api-guide#concurrency-considerations-thread-safety)
- [Spring AMQP Documentation - Connection and Resource Management](https://docs.spring.io/spring-amqp/docs/current/reference/html/#connection-and-resource-management) 