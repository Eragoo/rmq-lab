# RabbitMQ Publishing Performance Lab

## Table of Contents

- [Batch vs Simple One-by-One Publishing](#batch-publish-1-thread-vs-simple-one-by-one-publish)
- [Publishing with Publisher Confirms](#publishing-with-publisher-confirms)
- [Handling NACKs and Individual Message Tracking](#handling-nacks-and-individual-message-tracking)
  - [Batch vs Async ACK Comparison](#comparison-batch-vs-async-ack)
- [Complete Performance Comparison](#complete-performance-comparison)
---

# Batch Publish (1 Thread) vs Simple One-by-One Publish

## Performance Comparison (without waiting for ack from brocker)

| Method                 | Messages | Batch Size | Time     |
|------------------------|----------|------------|----------|
| **Batch Publish**      | 1M       | 10         | ~2 sec   |
| **One-by-One Publish** | 1M       | N/A        | ~12.5 sec|

## Observations in RabbitMQ UI

When using **batch publish**, the RabbitMQ UI displays the entire batch as **a single message**, but with an unusual structure. For example:

```json
b{"id":"4d90d564-70ea-4b72-b664-62769b568bb5","content":"Test message 0","timestamp":1747638407869}
b{"id":"5cece87b-2720-494a-b0e9-88b358899cda","content":"Test message 1","timestamp":1747638407869}
b{"id":"c22be8e4-ef90-4fce-8071-22aeeb812f41","content":"Test message 2","timestamp":1747638407869}
b{"id":"5c70dac9-bfa4-426d-98af-c24997cb45be","content":"Test message 3","timestamp":1747638407869}
b{"id":"db0e8d59-1a58-47a7-a180-6e98a8e90633","content":"Test message 4","timestamp":1747638407869}
b{"id":"3384c237-7931-4311-9d35-d5cb2829ea76","content":"Test message 5","timestamp":1747638407869}
b{"id":"996faf28-3717-41bb-b20c-7825802c1662","content":"Test message 6","timestamp":1747638407869}
b{"id":"e67d796f-abd6-4cbd-a071-5b93ca07e72f","content":"Test message 7","timestamp":1747638407869}
b{"id":"b453a9bd-a858-4a29-900f-cc6c4938da9d","content":"Test message 8","timestamp":1747638407869}
b{"id":"bb074e71-7e93-4bef-a23b-9075dc353e10","content":"Test message 9","timestamp":1747638407869}
```

## Question

> **Can consumers process this batched message?**

### Answer:

> **Yes, but the consumers should expect such message, since the format is different. So you cannot just change publisher to batch without changing consumer**

# Publishing with Publisher Confirms

RabbitMQ supports publisher confirms, which allows producers to wait for acknowledgment that messages have been received by the broker. This ensures message delivery reliability.

## Channel Management and Memory Considerations

⚠️ **Important**: Publisher confirmations are **per-channel** due to delivery tags being channel-scoped in the AMQP protocol. Each message published gets a unique delivery tag that only has meaning within its specific channel context.

**Without using `.invoke()`:**
- Each `convertAndSend()` call may checkout a new channel from the cache
- Channels with pending confirmations cannot be immediately returned to cache
- High-throughput publishing can exhaust the channel cache
- New channels get created when cache is full
- This can lead to **channel churn** and potential **OOM exceptions**

**With `.invoke()` function:**
- All operations within the callback use the **same dedicated channel**
- Single channel handles all messages and their confirmations
- Eliminates channel create/close overhead
- Prevents memory issues from excessive channel creation

```kotlin
// ❌ Problematic - creates channel churn
messages.forEach { message ->
    template.convertAndSend(exchange, routingKey, message) // Each call = potential new channel
}

// ✅ Correct - uses single channel for all operations
template.invoke { channel ->
    messages.forEach { message ->
        channel.convertAndSend(exchange, routingKey, message) // All use same channel
    }
}
```

## RabbitMQ Threading Model

💡 **Technical Detail**: The RabbitMQ Java client library uses **separate threads** for different operations:

- **I/O Thread per Connection**: Each connection has a dedicated thread for reading from the network socket
- **Consumer Thread Pool**: Consumer callbacks are dispatched via a separate `ExecutorService` thread pool  
- **Full-Duplex Channels**: A single channel can handle **bidirectional traffic** - publishing outbound messages while receiving confirmations/consumers on different threads

This threading architecture enables:
- **Concurrent Operations**: Publishing and consuming can happen simultaneously on the same channel
- **Non-blocking Confirmations**: Confirmation callbacks are handled asynchronously without blocking publishers
- **Scalable Performance**: Multiple threads can work with the same channel safely for different operation types

**References**: 
- [RabbitMQ Java Client API Guide - Consumer Operation Thread Pool](https://www.rabbitmq.com/client-libraries/java-api-guide#consumer-operation-thread-pool)
- [RabbitMQ Java Client API Guide - Concurrency Considerations](https://www.rabbitmq.com/client-libraries/java-api-guide#concurrency-considerations-thread-safety)

## Implementation

To use publisher confirms:

1. Configure RabbitMQ to use publisher confirms (e.g. publisher-confirm-type: simple)
2. Use `rabbitTemplate.invoke` instead of direct `convertAndSend`
3. Wait for confirmation using `waitForConfirmsOrDie`
> Important note: you confirm only messages published inside .invoke function (same Channel used)

Example from `RmqAckPublisher.kt`:
```kotlin
rabbitTemplate.invoke {
    it.convertAndSend(
        RabbitMQConfig.EXCHANGE_NAME,
        RabbitMQConfig.ROUTING_KEY,
        message
    )
    it.waitForConfirmsOrDie(10_000)
}
```

> **Note about confirmation behavior**: While this example waits for confirmation after each message, `waitForConfirms` actually confirms all messages published since the last call to `waitForConfirms` on the same channel. This means you can publish multiple messages and then call `waitForConfirms` once to confirm all of them, which is more efficient than confirming each message individually.

Here's how to implement batch confirmation:
```kotlin
rabbitTemplate.invoke {
    // Publish multiple messages
    messages.forEach { message ->
        it.convertAndSend(
            RabbitMQConfig.EXCHANGE_NAME,
            RabbitMQConfig.ROUTING_KEY,
            message
        )
    }
    // Wait for confirmation of all messages at once
    it.waitForConfirmsOrDie(10_000)
}
```

## Performance Comparison

| Method | Messages | Confirmation Strategy | Time |
|--------|----------|----------------------|------|
| Simple Ack | 1M | Per message | 14m 34s |
| Batched Ack | 1M | Per 10k messages | 17s |

The batched approach is significantly faster because it:
- Reduces the number of confirmation waits
- Uses a single channel for all messages in a batch
- Maintains the same reliability guarantees

## Handling NACKs and Individual Message Tracking

### The Problem with Batch Acknowledgments

When using batch acknowledgments (like in `RmqAckPublisher.kt`), there's a significant limitation: **if a NACK is received, you cannot identify which specific message failed**.

```kotlin
// Batch approach - limited error handling
rabbitTemplate.invoke {
    messages.forEach { message ->
        it.convertAndSend(exchange, routingKey, message)
    }
    // If this fails, which of the 10,000 messages was problematic?
    it.waitForConfirmsOrDie(10_000)
}
```

**Issues with batch acknowledgments:**
- Cannot identify individual failed messages
- Must republish entire batch on failure
- No granular retry mechanism
- Wastes resources on already successful messages

### Solution: Individual Acknowledgments

This approach solves the retry issue by providing callbacks that can track individual message acknowledgments and negative acknowledgments (NACKs). Unlike the batch approach, this method allows you to identify exactly which messages failed and handle them individually.

Here's the implementation from `simpleAckPublishWithCallback`:

```kotlin
fun simpleAckPublishWithCallback(messages: List<Message>) {
    measureTime {
        messages.chunked(10_000).forEach { chunk ->
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
        }
    }.let { millis ->
        println("Published 1M messages in $millis")
    }
}
```

**Important note about `waitForConfirms`**: We must call `waitForConfirms` after publishing because without it, when we stop publishing, we will stop receiving ACK/NACKs. The `waitForConfirms` ensures that we wait for all confirmations to be received before proceeding. This guarantees that all acknowledgment and negative acknowledgment callbacks are properly triggered.
This approach is already okay for most applications, but we are still waiting for all messages to be confirmed, which can be desirable in some cases. However, for best publishing performance, it would be better to be fully asynchronous.

### Solution: Asynchronous Acknowledgments with Individual Tracking

The solution is to use **correlated publisher confirms** with asynchronous callbacks. This approach solves the batch limitation by tracking each message individually.

#### What Problems Does Async ACK Solve?

1. **Individual Message Tracking**: Know exactly which messages succeeded/failed
2. **Non-blocking Operations**: Don't wait synchronously for confirmations
3. **Granular Error Handling**: Retry only failed messages
4. **Better Throughput**: Channel doesn't block during confirmation waits
5. **Resource Efficiency**: Avoid republishing successful messages

#### Implementation

Key implementation points for async acknowledgments with individual tracking:

- **Cache messages by ID**: Store messages in a `ConcurrentHashMap<String, Message>` using message ID as key
- **Send correlation data**: Use `CorrelationData(message.id)` when publishing so broker can return it back
- **Enable publisher confirms**: Set `template.isPublisherConfirms = true` on RabbitTemplate  
- **Add confirm callback**: Use `setConfirmCallback` to handle ACK/NACK responses asynchronously
- **No blocking waits**: Don't use `waitForConfirms` - let callbacks handle everything asynchronously
- **Thread safety**: Use atomic counters and concurrent collections for multi-threaded access

Implmenetation example: `RmqCorrelatedAckPublisher.kt`

#### Comparison: Batch vs Async ACK

| Aspect | Batch ACK (`RmqAckPublisher`) | Async ACK |
|--------|-------------------------------|-----------|
| **Blocking** | ✅ Synchronous, blocks on `waitForConfirms` | ❌ Non-blocking, async callbacks |
| **Individual Tracking** | ❌ Cannot identify specific failed messages | ✅ Track each message individually |
| **Error Recovery** | ❌ Must retry entire batch | ✅ Retry only failed messages |
| **Throughput** | ⚠️ Limited by confirmation waits | ✅ Higher throughput, no blocking |
| **Resource Usage** | ❌ May republish successful messages | ✅ Only retry actual failures |
| **Complexity** | ✅ Simple implementation | ⚠️ More complex callback handling |
| **Immediate Feedback** | ✅ Know result before method returns | ❌ Results arrive asynchronously |

#### Pros and Cons of Async ACK

**Pros:**
- **Non-blocking**: Higher throughput, better resource utilization
- **Individual tracking**: Know exactly which messages failed
- **Efficient retry**: Only republish failed messages
- **Scalable**: Can handle high-volume publishing
- **Granular control**: Different handling per message type

**Cons:**
- **Complexity**: More complex error handling logic
- **Async nature**: Results don't arrive immediately
- **Memory usage**: Must track pending confirmations
- **Callback management**: Need to handle callback lifecycle
- **Debugging**: Harder to debug async flows

#### When to Use Each Approach

**Use Batch ACK when:**
- Simple use cases with good broker reliability
- Immediate feedback required
- Occasional failures are acceptable
- Simpler codebase preferred

**Use Async ACK when:**
- High-throughput requirements
- Individual message tracking needed
- Sophisticated error recovery required
- Production systems with reliability demands
- Need to minimize resource waste on retries

## Complete Performance Comparison

### All Publishing Methods Performance Results

| Method | Messages | Strategy         | Confirmation | Time | Notes                                              |
|--------|----------|------------------|--------------|------|----------------------------------------------------|
| **Simple One-by-One** | 1M | Individual send  | None | ~12.5s | No reliability                                     |
| **Batch Publish** | 1M | Batch of 10      | None | ~2s | Fast but no confirmation, require Consumer support |
| **Simple ACK** | 1M | Individual send  | Per message | 14m 34s | Extremely slow                                     |
| **Batched ACK** | 1M | ACK Batch of 10k | Per batch | ~17s | Good performance, limited error handling           |
| **Async ACK** | 1M | Individual send  | Async callbacks | **~16.5s** | Best balance: performance + individual tracking    |

### Key Insights

1. **Async ACK vs Batched ACK**: Identical performance
2. **Individual Tracking**: Async ACK provides individual message tracking
3. **Reliability**: Async ACK offers the best of both worlds - speed and granular error handling
4. **Scalability**: Async ACK performs well under high load without blocking