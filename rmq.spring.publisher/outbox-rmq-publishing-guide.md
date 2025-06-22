# Outbox Pattern: RabbitMQ Publishing Strategies for High-Performance Systems

#outbox #rabbitmq #microservices #performance

## Why Outbox Publishing?

The database side of the outbox pattern has been excellently covered by [@msdousti](https://dev.to/msdousti/postgresql-outbox-pattern-revamped-part-1-3lai). But what about the publishing side? 

My investigation started when a seemingly robust outbox implementation began causing incidents under high load. The system worked flawlessly during development and low-traffic periods, but when traffic spiked, we experienced:

- **Memory exhaustion** from improper channel management
- **Publishing delays** that created growing backlogs

What you might also experience:
- **Lost messages** due to various reasons
- **Complete system freezes** when RabbitMQ publishing became a bottleneck

These incidents taught me that **the publishing is just as critical as the database design** in the outbox pattern. A poor publishing implementation can negate all the reliability benefits of the outbox approach.

This post shares the lessons learned and presents a few strategies for reliable, high-performance outbox message publishing.

## Outbox: Table + Scheduled Publishing

Before diving into publishing strategies, let's clarify what I mean by the outbox pattern and why we focus on the scheduled publishing approach.

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

## Potential Publishing Incidents: What Goes Wrong Under Load

### 1. Channel Exhaustion (OutOfMemoryError)

**Symptoms:**
- Application crashes with `OutOfMemoryError`
- High memory usage in heap dumps
- RabbitMQ connection metrics show excessive channel creation

**Root Cause:**
```kotlin
// ❌ DANGEROUS: Each call may create new channel
unpublishedMessages.forEach { message ->
    rabbitTemplate.convertAndSend(exchange, routingKey, message)
}
```

Each `convertAndSend()` can check out a new channel from the cache. Under high load, channels __with pending operations__ can't be returned to cache, forcing creation of new channels until memory is exhausted.

__Note__: Channel churn can also lead to poor performance.

### 2. Publishing Bottlenecks

**Symptoms:**
- Growing outbox table backlog
- Publishing throughput can't keep up with message creation
- System becomes unresponsive during high traffic

**Root Cause:**
Poor acknowledgment strategies that block publishing threads:

```kotlin
// ❌ BLOCKS: Each message waits for individual confirmation
unpublishedMessages.forEach { message ->
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

## Understanding Publisher Confirms: Foundation for Reliability

Before exploring specific strategies, it's crucial to understand RabbitMQ's publisher confirm mechanism, which I covered in detail in my previous articles:

- [From Fire-and-Forget to Reliable: RabbitMQ ACK](https://dev.to/eragoo/from-fire-and-forget-to-reliable-rabbitmq-ack-3nnb) - covers simple and batch acknowledgments
- [From Fire-and-Forget to Reliable: RabbitMQ ACK [pt. 2]](https://dev.to/eragoo/from-fire-and-forget-to-reliable-rabbitmq-ack-pt-2-5en6) - covers async confirmations with correlation

**Publisher confirms** ensure message delivery by having the broker acknowledge receipt of each message. This is essential for reliable outbox publishing, as it tells you definitively whether a message reached the broker.

## Strategy 1: Fire and Forget (Simple but Risky)

Let's start with the simplest approach, though it's likely not suitable for most outbox implementations.

### When Fire-and-Forget Makes Sense

Fire-and-forget is appropriate when:
- **Performance is critical over reliability**
- **Occasional message loss is acceptable**
- **Network environment is very stable**
- **Non-critical business events**

### Implementation

```kotlin
@Service
class FireAndForgetPublisher(
    private val rabbitTemplate: RabbitTemplate
) {
    fun publishOutboxMessages(messages: List<OutboxMessage>) {
        // ✅ CRITICAL: Channel churn does not occur here because no confirmation type is set.
        // Therefore, no related background processes (e.g., waiting for acknowledgment) are attached to the channel,
        // allowing the channel to be returned to the cache immediately after publishing.
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

### Key Characteristics

- **Throughput**: A LOT of messages/second
- **Latency**: Minimal (no network round-trips for confirmations)
- **Reliability**: None (messages can be lost silently)

## Strategy 2: Synchronous Batch ACK

For most outbox implementations, synchronous batch acknowledgments provide the optimal balance of reliability, simplicity, and performance while avoiding the transaction boundary issues inherent in async approaches.

### Why Synchronous Batch ACK is Ideal for Outbox

The synchronous batch approach solves critical outbox publishing challenges that async approaches cannot address:

1. **Transaction atomicity** - publishing and database updates happen in the same transaction
2. **No limbo state** - messages are never stuck between published and marked-as-published
3. **Simple error handling** - either all messages succeed or all retry together
4. **Guaranteed consistency** - SELECT FOR UPDATE prevents duplicate processing across threads
5. **Predictable behavior** - no async callbacks or timeout handling complexity

### Core Implementation

```kotlin
@Service
class SyncBatchOutboxPublisher(
    private val rabbitTemplate: RabbitTemplate,
    private val outboxRepository: OutboxRepository
) {
    @Transactional
    fun publishOutboxMessages() {
        // ✅ SELECT FOR UPDATE prevents other threads from selecting same messages
        val messages = outboxRepository.findUnpublishedAndLock(limit = 1000)
        
        if (messages.isEmpty()) return
        
        try {
            // Publish all messages in single channel with batch confirmation
            rabbitTemplate.invoke { channel ->
                messages.forEach { outboxMessage ->
                    channel.convertAndSend(
                        exchangeName,
                        routingKey,
                        outboxMessage.payload
                    )
                }
                
                // ✅ Wait for ALL confirmations before proceeding
                // This ensures all messages reached the broker
                channel.waitForConfirmsOrDie(30_000)
            }
            
            // ✅ ALL messages confirmed - mark in SAME transaction
            // Either all succeed or transaction rolls back
            outboxRepository.markAsPublished(messages.map { it.id })
            
        } catch (Exception e) {
            // ❌ Publishing failed - transaction rolls back
            // Messages remain unpublished and will be retried
            log.error("Publishing failed for batch of ${messages.size} messages", e)
            throw e  // Let transaction rollback
        }
    }
}
```

### Configuration for Sync Batch ACK

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: simple  # Required for confirmations
    cache:
      channel:
        size: 10
        checkout-timeout: 5000
```

### Key Characteristics

- **Throughput**: ~1,000-5,000 messages/second (depending on batch size)
- **Latency**: Moderate (waits for batch confirmations)
- **Reliability**: Highest (atomic transactions, no lost messages, duplicates possible on retry)
- **Memory**: Low (no pending confirmation tracking needed)
- **Complexity**: Low (simple transaction flow)

### Advantages for Outbox Pattern

**✅ Transaction Safety**
```kotlin
@Transactional
fun publishOutboxMessages() {
    val messages = findAndLock()     // Same transaction
    publishAllMessages(messages)     // Same transaction  
    markAsPublished(messages)        // Same transaction
    // Either ALL succeed or ALL rollback - no partial state
}
```

**✅ Thread Safety** (Note: publish out-of-order possible)
```kotlin
// Thread 1: Locks messages 1-1000
val batch1 = outboxRepository.findUnpublishedAndLock(limit = 1000)

// Thread 2: Gets messages 1001-2000 (different records)
val batch2 = outboxRepository.findUnpublishedAndLock(limit = 1000)

// No overlap possible due to SELECT FOR UPDATE
```

**✅ Simple Error Recovery**
```kotlin
try {
    publishAndConfirm(messages)
    markAsPublished(messages)
} catch (Exception e) {
    // Transaction rolls back automatically
    // Messages remain unpublished for next retry
    // No complex cleanup needed
}
```
**⚠️ Publish Duplicates on NACK**
```kotlin
try {
    publishAndConfirm(messages)
    markAsPublished(messages)
} catch (Exception e) {
    // If publishAndConfirm failed due to received NACK - we're
    // forced to retry the whole batch which creates duplicates
}
```

Theoretically, to address duplicate publications on NACK, you can use the callbacks provided by the `invoke()` method. You can define two callbacks: one for ACK and one for NACK. These callbacks only provide the deliveryTag, but since you're publishing from the same channel, you can try caching a deliveryTag-to-outboxMessageId mapping in order to individually track and handle NACKed messages and avoid this issue.

## Strategy 3: Async ACK with Correlation (Complex but High Throughput)

While async acknowledgments offer higher throughput, they introduce significant complexity and potential consistency issues for outbox implementations. This approach is not recommended until other options are not enough for your use case.

### Critical Issues with Async ACK for Outbox

Before exploring the async approach, it's important to understand the fundamental challenges it creates:

**⚠️ Transaction Boundary Violation**
```kotlin
@Transactional
fun publishOutboxMessages() {
    val messages = findUnpublishedAndLock()
    publishAsync(messages)  // Messages sent to broker
    // Transaction COMMITS here - but ACKs arrive later!
}

// Different thread, different transaction
confirmCallback { correlationData, ack, cause ->
    if (ack) {
        // ❌ If this fails, message was published but not marked!
        markAsPublished(messageId)
    }
}
```

**⚠️ Lost ACK Problem**
```kotlin
// What if ACK/NACK never arrives due to:
// - Network issues
// - Broker restart  
// - Connection loss
// 
// Message is published but stuck in "pending" state forever
pendingConfirmations[messageId] = message  // Limbo state
```

### Why Async ACK is Problematic for Outbox

The async approach conflicts with key outbox pattern guarantees:

1. **Breaks atomicity** - publish and mark-as-published happen in different transactions
2. **Creates limbo state** - messages can be published but never marked as such
3. **Timeout complexity** - need cleanup jobs for lost ACKs
4. **Complex recovery** - sophisticated state machine required

### When Async ACK Makes Sense

Despite the issues, async ACK can work when:
- **Batch sync ACK is not enough**
- **NACKs happen often, which leads to tons of duplicates with sync ACK**
- **Complex state management is acceptable**

### Async Implementation (Use with Caution)

```kotlin
@Service
class AsyncAckOutboxPublisher(
    private val connectionFactory: ConnectionFactory,
    private val outboxRepository: OutboxRepository
) {
    private val pendingConfirmations = ConcurrentHashMap<String, OutboxMessage>()

    @Transactional
    fun publishOutboxMessages() {
        val messages = outboxRepository.findUnpublishedAndLock(limit = 1000)
        
        // ⚠️ Mark as IN_FLIGHT to prevent re-selection
        outboxRepository.markAsInFlight(messages.map { it.id })
        
        publishAsync(messages)
        // Transaction commits with messages in IN_FLIGHT state
    }
    
    private fun publishAsync(messages: List<OutboxMessage>) {
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
    }

    private fun createAsyncTemplate(): RabbitTemplate {
        return RabbitTemplate(connectionFactory).apply {
            messageConverter = Jackson2JsonMessageConverter()
            setMandatory(true)
            
            setConfirmCallback { correlationData, ack, cause ->
                val messageId = correlationData?.id
                val outboxMessage = messageId?.let { 
                    pendingConfirmations.remove(it) 
                }
                
                if (ack && outboxMessage != null) {
                    handleSuccessfulPublish(outboxMessage)
                } else if (outboxMessage != null) {
                    handleFailedPublish(outboxMessage, cause ?: "Unknown error")
                }
            }
        }
    }
    
    private fun handleSuccessfulPublish(outboxMessage: OutboxMessage) {
        try {
            outboxRepository.markAsPublished(outboxMessage.id)
        } catch (e: Exception) {
            // ❌ Critical: Message was delivered but not marked as published!
            log.error("Failed to mark message as published: ${outboxMessage.id}", e)
            // Need sophisticated recovery mechanism here
        }
    }
    
    @Scheduled(fixedDelay = 60000)  // Cleanup job required
    fun cleanupTimeouts() {
        val timeoutMessages = outboxRepository.findInFlightOlderThan(Duration.ofMinutes(5))
        timeoutMessages.forEach { message ->
            if (pendingConfirmations.containsKey(message.id)) {
                // Still pending - reset to unpublished for retry
                outboxRepository.markAsUnpublished(message.id)
                pendingConfirmations.remove(message.id)
            }
        }
    }
}
```

### Required Infrastructure for Async ACK

If you choose async ACK despite the complexity, you need:

1. **State machine** with PENDING/IN_FLIGHT/PUBLISHED/FAILED states
2. **Cleanup jobs** for handling timeouts and lost ACKs
3. **Duplicate detection** at consumer level (always should be there IMO)
4. **Sophisticated monitoring** for limbo states
5. **Manual intervention procedures** for poison pill messages (needed for sync ACK as well)

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

### Key Characteristics

- **Throughput**: Limited only by network and number of workers
- **Latency**: Low (non-blocking confirmations)  
- **Reliability**: Moderate (complex error scenarios)
- **Memory**: High overhead (tracking pending confirmations + cleanup jobs)
- **Complexity**: High (async flows, state machines, limbo state handling)

## Channel Churn Pitfall: Critical for Both Strategies

Channel churn is one of the most dangerous issues in high-throughput RabbitMQ applications. Both publishing strategies must address this properly.

### Understanding the Problem

As documented in the [RabbitMQ Java Client Guide](https://www.rabbitmq.com/client-libraries/java-api-guide#connection-and-resource-management), the RabbitMQ client uses connection pooling and channel caching. However, under high load:

1. **Channels with pending operations** cannot be returned to cache immediately
2. **New channels get created** when cache is exhausted
3. **Memory usage grows** as channels accumulate
4. **OutOfMemoryError** occurs when too many channels exist

### Solution: Publish Batch with Same Channel

```kotlin
// ❌ PROBLEMATIC: Each call may check out new channel
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
- **No channel creation overhead** during bulk operations
- **Predictable memory usage** regardless of message count with a limited number of publisher threads

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

When `checkout-timeout` is set, the channel cache becomes a hard limit. If all channels are busy, threads will wait up to 5 seconds for an available channel, preventing unlimited channel creation. In other words, you can avoid OOM at the price of channel checkout timeout exceptions. A rule of thumb here: make sure you're not wasting resources on creating/closing channels while the cache size is sufficient to handle your load, so your application is not waiting for channels to be ready.

## Monitoring and Observability

For production outbox implementations, implement these monitoring strategies:

### Key Metrics
- **Outbox table size** - number of unpublished messages
- **Publishing rate** - messages published per second
- **Confirmation rate** - ACK/NACK ratio
- **Pending confirmations** - messages waiting for confirmation
- **Channel utilization** - active channels vs cache size