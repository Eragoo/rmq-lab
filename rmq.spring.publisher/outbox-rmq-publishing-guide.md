# Outbox Pattern: RabbitMQ Publishing Strategies for High-Performance Systems

#outbox #rabbitmq #microservices #performance

## The Journey: Why I Started Investigating Outbox Publishing

The database side of the outbox pattern has been excellently covered by [@msdousti](https://dev.to/msdousti/postgresql-outbox-pattern-revamped-part-1-3lai). But what about the publishing side? 

My investigation started when a seemingly robust outbox implementation in production began causing incidents under high load. The system worked flawlessly during development and low-traffic periods, but when traffic spiked, we experienced:

- **Memory exhaustion** from improper channel management
- **Publishing delays** that created growing backlogs

What you also might experience:
- **Lost messages** during broker connection issues
- **Complete system freezes** when RabbitMQ publishing became a bottleneck

These production incidents taught me that **the publishing strategy is just as critical as the database design** in the outbox pattern. A poor publishing implementation can negate all the reliability benefits of the outbox approach.

This post shares the lessons learned and presents two production-tested strategies for reliable, high-performance outbox message publishing.

## Understanding the Outbox Pattern: Table + Scheduled Publishing

Before diving into publishing strategies, let's clarify what we mean by the outbox pattern and why we focus on the scheduled publishing approach.

### What is the Outbox Pattern?

The outbox pattern ensures reliable message delivery in distributed systems through three key steps:

1. **Persist messages** in a database table alongside business data (same transaction)
2. **Scheduled publisher** reads unpublished messages from the outbox table
3. **Mark as published** once successfully delivered to the message broker

```sql
CREATE TABLE outbox (
    id BIGINT PRIMARY KEY,
    payload JSON NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ  -- NULL = unpublished
);
```

### Why Focus on Scheduled Publishing?

There are several outbox implementation approaches:

| Approach | Mechanism | Pros | Cons |
|----------|-----------|------|------|
| **Debezium CDC** | Change Data Capture | Automatic, Kafka-optimized | Complex setup, Kafka-specific |
| **Event-driven** | Database triggers/events | Immediate publishing | Tight coupling, hard to debug |
| **Scheduled Publishing** | Periodic job reads table | Full control, flexible | Manual implementation |

**This article focuses on scheduled publishing** because it provides:
- **Full control** over publishing logic and error handling
- **Flexibility** to publish to any message broker
- **Simpler debugging** and observability
- **Custom retry strategies** and performance tuning

The scheduled approach fits perfectly when you need reliable publishing with complete control over the process.

## Database Side: Already Solved

The database optimization aspects of the outbox pattern—including table partitioning, indexing strategies, and query performance—are thoroughly covered in [@msdousti's comprehensive article](https://dev.to/msdousti/postgresql-outbox-pattern-revamped-part-1-3lai).

His work demonstrates how to:
- Implement partitioned outbox tables for performance
- Optimize queries for fetching unpublished messages
- Handle index cleanup and maintenance
- Avoid common PostgreSQL pitfalls

**This article picks up where his leaves off**: once you have optimized outbox table queries, how do you publish those messages to RabbitMQ efficiently and reliably?

## Potential Publishing Incidents: What Goes Wrong in Production

### 1. Channel Exhaustion (OutOfMemoryError)

**Symptoms:**
- Application crashes with `OutOfMemoryError`
- High memory usage in heap dumps
- RabbitMQ connection metrics show excessive channel creation

**Root Cause:**
```kotlin
// ❌ DANGEROUS: Each call may create new channel
messages.forEach { message ->
    rabbitTemplate.convertAndSend(exchange, routingKey, message)
}
```

Each `convertAndSend()` can checkout a new channel from the cache. Under high load, channels with pending operations can't be returned to cache, forcing creation of new channels until memory is exhausted.

### 2. Publishing Bottlenecks

**Symptoms:**
- Growing outbox table backlog
- Publishing throughput can't keep up with message creation
- System becomes unresponsive during high traffic

**Root Cause:**
Poor acknowledgment strategies that block publishing threads:

```kotlin
// ❌ BLOCKS: Each message waits for individual confirmation
messages.forEach { message ->
    rabbitTemplate.invoke { channel ->
        channel.convertAndSend(exchange, routingKey, message)
        channel.waitForConfirmsOrDie(10_000) // Blocks here!
    }
}
```

### 3. Silent Message Loss

**Symptoms:**
- Messages disappear without error logs
- Inconsistencies between outbox table and actual delivered messages
- "Missing" business events in downstream systems

**Root Cause:**
Fire-and-forget publishing without any reliability checks:

```kotlin
// ❌ NO GUARANTEES: Message might never reach broker
rabbitTemplate.convertAndSend(exchange, routingKey, message)
// Immediately marked as published, but was it really?
markAsPublished(message)
```

### 4. Connection Pool Exhaustion

**Symptoms:**
- `AmqpTimeoutException` during high load
- Publishing threads hanging indefinitely
- System recovery only after restart

**Root Cause:**
Improper channel cache configuration and checkout timeouts.

## Understanding Publisher Confirms: Foundation for Reliability

Before exploring specific strategies, it's crucial to understand RabbitMQ's publisher confirm mechanism, which I covered in detail in my previous articles:

- [From Fire-and-Forget to Reliable: RabbitMQ Ack](https://dev.to/eragoo/from-fire-and-forget-to-reliable-rabbitmq-ack-3nnb) - covers simple and batch acknowledgments
- [From Fire-and-Forget to Reliable: RabbitMQ Ack [pt. 2]](https://dev.to/eragoo/from-fire-and-forget-to-reliable-rabbitmq-ack-pt-2-5en6) - covers async confirmations with correlation

**Publisher confirms** ensure message delivery by having the broker acknowledge receipt of each message. This is essential for reliable outbox publishing, as it tells you definitively whether a message reached the broker.

## Strategy 1: Fire and Forget (Simple but Risky)

Let's start with the simplest approach, though it's likely not suitable for most outbox implementations.

### When Fire-and-Forget Makes Sense

Fire-and-forget is appropriate when:
- **Performance is critical** over reliability
- **Occasional message loss is acceptable** (analytics, logs)
- **Network environment is very stable**
- **Non-critical business events** (user activity tracking)

### Implementation

```kotlin
@Service
class FireAndForgetPublisher(
    private val rabbitTemplate: RabbitTemplate
) {
    fun publishOutboxMessages(messages: List<OutboxMessage>) {
        // ✅ CRITICAL: Use invoke() to prevent channel churn
        rabbitTemplate.invoke { channel ->
            messages.forEach { outboxMessage ->
                channel.convertAndSend(
                    exchangeName,
                    routingKey,
                    outboxMessage.payload
                )
            }
        }
        
        // Mark all as published immediately
        // ⚠️ RISK: No guarantee they actually reached the broker
        outboxRepository.markAsPublished(messages.map { it.id })
    }
}
```

### Performance Characteristics

- **Throughput**: ~80,000 messages/second
- **Latency**: Minimal (no network round-trips for confirmations)
- **Reliability**: None (messages can be lost silently)

### The Channel Churn Problem

Even with fire-and-forget, you must use `rabbitTemplate.invoke()` to prevent channel exhaustion:

```kotlin
// ❌ DANGEROUS: Potential channel churn
messages.forEach { message ->
    rabbitTemplate.convertAndSend(exchange, routingKey, message.payload)
}

// ✅ SAFE: Single channel for all operations
rabbitTemplate.invoke { channel ->
    messages.forEach { message ->
        channel.convertAndSend(exchange, routingKey, message.payload)
    }
}
```

As explained in the [Spring AMQP documentation](https://docs.spring.io/spring-amqp/docs/current/reference/html/#connection-and-resource-management), each `convertAndSend()` call may checkout a new channel from the cache. Under high load, this can exhaust available channels and cause memory issues.

### Configuration for Fire-and-Forget

```yaml
spring:
  rabbitmq:
    # No publisher-confirm-type needed for fire-and-forget
    cache:
      channel:
        size: 10
        checkout-timeout: 5000
```

## Strategy 2: Async ACK with Correlation (Recommended)

For most production outbox implementations, async acknowledgments with correlation provide the best balance of performance and reliability.

### Why Async ACK is Ideal for Outbox

The async approach solves key outbox publishing challenges:

1. **Individual message tracking** - know exactly which messages succeeded/failed
2. **Non-blocking operations** - high throughput without blocking publisher threads
3. **Granular error handling** - retry only failed messages
4. **Precise outbox updates** - mark only confirmed messages as published

### Core Implementation

```kotlin
@Service
class AsyncAckOutboxPublisher(
    private val connectionFactory: ConnectionFactory,
    private val outboxRepository: OutboxRepository
) {
    private val pendingConfirmations = ConcurrentHashMap<String, OutboxMessage>()

    fun publishOutboxMessages(messages: List<OutboxMessage>) {
        val template = createAsyncTemplate()
        
        measureTime {
            template.invoke { channel ->
                messages.forEach { outboxMessage ->
                    // Track pending confirmation
                    pendingConfirmations[outboxMessage.id] = outboxMessage
                    
                    // Publish with correlation data
                    channel.convertAndSend(
                        exchangeName,
                        routingKey,
                        outboxMessage.payload,
                        CorrelationData(outboxMessage.id)
                    )
                }
            }
        }.let { duration ->
            println("Published ${messages.size} messages in $duration")
            println("Confirmations will arrive asynchronously")
        }
    }

    private fun createAsyncTemplate(): RabbitTemplate {
        return RabbitTemplate(connectionFactory).apply {
            messageConverter = Jackson2JsonMessageConverter()
            setMandatory(true) // Ensure routing validation
            
            // Handle confirmations asynchronously
            setConfirmCallback { correlationData, ack, cause ->
                val messageId = correlationData?.id
                val outboxMessage = messageId?.let { 
                    pendingConfirmations.remove(it) 
                }
                
                if (ack && outboxMessage != null) {
                    // Success: Mark as published in database
                    handleSuccessfulPublish(outboxMessage)
                } else if (outboxMessage != null) {
                    // Failure: Handle retry logic
                    handleFailedPublish(outboxMessage, cause ?: "Unknown error")
                }
            }
        }
    }
    
    private fun handleSuccessfulPublish(outboxMessage: OutboxMessage) {
        try {
            outboxRepository.markAsPublished(outboxMessage.id)
            println("✅ Message ${outboxMessage.id} confirmed and marked as published")
        } catch (e: Exception) {
            println("❌ Failed to mark message ${outboxMessage.id} as published: ${e.message}")
            // Could implement retry logic for database updates
        }
    }
    
    private fun handleFailedPublish(outboxMessage: OutboxMessage, cause: String) {
        println("❌ Message ${outboxMessage.id} NACKed: $cause")
        
        when {
            cause.contains("NO_ROUTE") -> {
                // Routing failure - likely configuration issue
                sendToDeadLetterQueue(outboxMessage, "NO_ROUTE: $cause")
            }
            cause.contains("RESOURCE_ERROR") -> {
                // Temporary broker issue - schedule retry
                scheduleRetry(outboxMessage, Duration.ofMinutes(5))
            }
            else -> {
                // Unknown error - investigate
                logForInvestigation(outboxMessage, cause)
                scheduleRetry(outboxMessage, Duration.ofMinutes(1))
            }
        }
    }
    
    private fun scheduleRetry(outboxMessage: OutboxMessage, delay: Duration) {
        // Implementation depends on your retry mechanism
        // Could use database-backed retry queue, scheduler, etc.
        println("🔄 Scheduling retry for message ${outboxMessage.id} in ${delay.toMinutes()} minutes")
    }
    
    private fun sendToDeadLetterQueue(outboxMessage: OutboxMessage, reason: String) {
        // Send to DLQ and mark as failed in database
        println("💀 Sending message ${outboxMessage.id} to dead letter queue: $reason")
        outboxRepository.markAsFailed(outboxMessage.id, reason)
    }
}
```

### Configuration for Async ACK

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated  # Required for correlation
    cache:
      channel:
        size: 10
        checkout-timeout: 5000
```

### Performance Characteristics

- **Throughput**: ~60,000 messages/second (with individual tracking)
- **Latency**: Low (non-blocking confirmations)
- **Reliability**: High (individual message guarantees)
- **Memory**: Moderate overhead for tracking pending confirmations

## The Channel Churn Pitfall: Critical for Both Strategies

Channel churn is one of the most dangerous issues in high-throughput RabbitMQ applications. Both publishing strategies must address this properly.

### Understanding the Problem

As documented in the [RabbitMQ Java Client Guide](https://www.rabbitmq.com/client-libraries/java-api-guide#connection-and-resource-management), the RabbitMQ client uses connection pooling and channel caching. However, under high load:

1. **Channels with pending operations** cannot be returned to cache immediately
2. **New channels get created** when cache is exhausted
3. **Memory usage grows** as channels accumulate
4. **OutOfMemoryError** occurs when too many channels exist

### The Solution: Always Use invoke()

```kotlin
// ❌ PROBLEMATIC: Each call may checkout new channel
outboxMessages.forEach { message ->
    rabbitTemplate.convertAndSend(exchange, routingKey, message.payload)
}

// ✅ CORRECT: Single channel for all operations
rabbitTemplate.invoke { channel ->
    outboxMessages.forEach { message ->
        channel.convertAndSend(exchange, routingKey, message.payload)
    }
}
```

### Why invoke() Works

The `invoke()` method ensures:
- **Single channel checkout** for the entire operation
- **Controlled channel lifecycle** - returned to cache when lambda completes
- **No channel creation overhead** during bulk operations
- **Predictable memory usage** regardless of message count

### Additional Channel Management

For production systems, consider these channel cache settings:

```yaml
spring:
  rabbitmq:
    cache:
      channel:
        size: 25              # Reasonable cache size
        checkout-timeout: 5000 # 5 second timeout
```

When `checkout-timeout` is set, the channel cache becomes a hard limit. If all channels are busy, threads will wait up to 5 seconds for an available channel, preventing unlimited channel creation.

## Performance Comparison: Real Benchmarks

Based on testing with 1 million messages:

| Strategy | Throughput | Reliability | Memory Usage | Use Case |
|----------|------------|-------------|--------------|----------|
| **Fire & Forget** | 80k msg/s | None | Low | Analytics, logs, non-critical events |
| **Async ACK** | 60k msg/s | Individual tracking | Moderate | Financial transactions, audit trails |
| Simple ACK* | 1k msg/s | Blocking | Low | Legacy systems (not recommended) |

*Simple ACK performance from [my previous article](https://dev.to/eragoo/from-fire-and-forget-to-reliable-rabbitmq-ack-3nnb) - included for comparison but not recommended for outbox implementations.

### The 25% Performance Trade-off

Async ACK provides individual message tracking with only a 25% performance reduction compared to fire-and-forget (60k vs 80k msg/s). For most production outbox implementations, this trade-off is worthwhile because:

- **Precise database updates** - only mark actually delivered messages as published
- **Granular error handling** - retry individual failed messages
- **Better observability** - know exactly what succeeded/failed
- **Reduced waste** - don't republish already successful messages

## Decision Framework: Choosing Your Strategy

### Choose Fire-and-Forget When:
- Maximum throughput is critical (80k+ msg/s required)
- Occasional message loss is acceptable
- Non-critical business events (user activity, analytics)
- Very stable network and broker environment
- Simple implementation is preferred

### Choose Async ACK When:
- Message delivery guarantees are required
- Individual error handling is needed
- Good performance is acceptable (60k+ msg/s)
- Critical business data (payments, orders, audit trails)
- Sophisticated retry mechanisms are desired

### Configuration Examples

#### Fire-and-Forget Configuration
```kotlin
@Service
class OutboxPublisher(
    private val rabbitTemplate: RabbitTemplate,
    private val outboxRepository: OutboxRepository
) {
    @Scheduled(fixedDelay = 1000)
    fun publishPendingMessages() {
        val unpublishedMessages = outboxRepository.findUnpublished(limit = 1000)
        
        if (unpublishedMessages.isNotEmpty()) {
            publishFireAndForget(unpublishedMessages)
        }
    }
    
    private fun publishFireAndForget(messages: List<OutboxMessage>) {
        rabbitTemplate.invoke { channel ->
            messages.forEach { message ->
                channel.convertAndSend(
                    "outbox.exchange",
                    message.routingKey,
                    message.payload
                )
            }
        }
        
        // Mark all as published (risk: some might not have reached broker)
        outboxRepository.markAsPublished(messages.map { it.id })
    }
}
```

#### Async ACK Configuration
```kotlin
@Service
class ReliableOutboxPublisher(
    private val connectionFactory: ConnectionFactory,
    private val outboxRepository: OutboxRepository
) {
    private val pendingConfirmations = ConcurrentHashMap<String, OutboxMessage>()
    
    @Scheduled(fixedDelay = 1000)
    fun publishPendingMessages() {
        val unpublishedMessages = outboxRepository.findUnpublished(limit = 1000)
        
        if (unpublishedMessages.isNotEmpty()) {
            publishWithAsyncAck(unpublishedMessages)
        }
    }
    
    // Implementation as shown above...
}
```

## Monitoring and Observability

For production outbox implementations, implement these monitoring strategies:

### Key Metrics
- **Outbox table size** - number of unpublished messages
- **Publishing rate** - messages published per second
- **Confirmation rate** - ACK/NACK ratio
- **Pending confirmations** - messages waiting for confirmation
- **Channel utilization** - active channels vs cache size

### Alerting Thresholds
```yaml
# Example monitoring thresholds
outbox:
  unpublished_messages:
    warning: 10000    # Growing backlog
    critical: 50000   # Publishing can't keep up
  
  publishing_rate:
    warning: < 1000   # Publishing too slow
    critical: < 100   # Near-zero publishing
  
  pending_confirmations:
    warning: 5000     # Many unconfirmed messages
    critical: 20000   # Potential memory issues
```

## Conclusion

The outbox pattern's reliability depends not just on database design but equally on the publishing strategy. Through production experience and testing, I've found that:

1. **Channel management is critical** - always use `rabbitTemplate.invoke()` to prevent memory issues
2. **Fire-and-forget maximizes speed** but eliminates reliability guarantees
3. **Async ACK provides the best balance** - 60k msg/s with individual tracking
4. **Strategy choice depends on requirements** - speed vs reliability trade-offs

For most production systems handling critical business events, **async ACK with correlation** offers the optimal combination of performance, reliability, and operational visibility.

The database optimization techniques are thoroughly covered in [@msdousti's article](https://dev.to/msdousti/postgresql-outbox-pattern-revamped-part-1-3lai), while the foundational RabbitMQ publishing patterns are detailed in my previous articles on [basic ACK strategies](https://dev.to/eragoo/from-fire-and-forget-to-reliable-rabbitmq-ack-3nnb) and [advanced async patterns](https://dev.to/eragoo/from-fire-and-forget-to-reliable-rabbitmq-ack-pt-2-5en6).

---

*Have you encountered outbox publishing issues in production? What strategies worked best for your use case? Share your experiences in the comments!* 