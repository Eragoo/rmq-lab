# Channel Management Demo Classes

These two demo classes showcase the different behaviors of Spring AMQP channel management when the channel cache is exhausted. The configuration is managed through `application.yml` profiles.

## Configuration

The demos use Spring Boot's auto-configuration with profile-specific settings in `application.yml`:

```yaml
# Base configuration
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

# Unlimited profile
---
spring:
  profiles: unlimited
  rabbitmq:
    cache:
      channel:
        size: 3
        checkout-timeout: 0  # Unlimited channel creation

# Limited profile  
---
spring:
  profiles: limited
  rabbitmq:
    cache:
      channel:
        size: 3
        checkout-timeout: 5000  # 5 seconds timeout
```

## Demo Classes

### 1. UnlimitedChannelDemo

**Profile**: `unlimited`

**Key Features**:
- **Channel Cache Size**: 3 channels
- **Checkout Timeout**: 0 (unlimited channel creation)
- **Test Scenario**: 4 threads compete for channels
- **Connection Monitoring**: Real-time monitoring of connection cache

**Test Sequence**:
1. **Threads 1-3**: Each acquires a channel and blocks it for **12 seconds**
2. **4th Thread**: Starts after 3 seconds, should get a NEW channel immediately
3. **All threads**: Publish test messages through their channels

**Expected Behavior**:
- First 3 threads acquire cached channels and hold them for 12 seconds
- 4th thread **immediately gets a NEW channel** (outside cache) without waiting
- All threads complete successfully, demonstrating **unlimited channel creation**

**Expected Output Example**:
```
=== UNLIMITED CHANNEL CREATION DEMO ===
🔧 Connection Factory Configuration:
   - Channel cache size: 3
   - Channel checkout timeout: 0 = unlimited
   - Profile: unlimited

🔒 Thread 1 starting - will block channel for 12 seconds
✅ Thread 1 acquired channel: 123456789
🔒 Thread 2 starting - will block channel for 12 seconds  
✅ Thread 2 acquired channel: 987654321
🔒 Thread 3 starting - will block channel for 12 seconds
✅ Thread 3 acquired channel: 456789123

🔥 4th Thread starting - should get channel IMMEDIATELY
🚀 4th Thread SUCCESS: Got channel 789123456 after 15ms
   (New channel created - no waiting!)
```

### 2. LimitedChannelDemo

**Profile**: `limited`

**Key Features**:
- **Channel Cache Size**: 3 channels  
- **Checkout Timeout**: 5 seconds (strict limit)
- **Test Scenario**: 4+ threads compete for limited channels
- **Connection Monitoring**: Real-time monitoring of connection cache

**Test Sequence**:
1. **Threads 1-3**: Each acquires a channel and blocks it for **15 seconds**
2. **4th Thread**: Starts after 3 seconds, should **TIMEOUT after 5 seconds**
3. **6th Thread**: Starts after 8 more seconds to test channel release
4. **All threads**: Attempt to publish test messages through their channels

**Expected Behavior**:
- First 3 threads acquire all cached channels and hold them for 15 seconds
- 4th thread **waits 5 seconds then TIMES OUT** with `AmqpTimeoutException`
- 6th thread (started after 8 seconds) might succeed when some channels are released
- Demonstrates **channel checkout timeout** enforcement

**Expected Output Example**:
```
=== LIMITED CHANNEL WITH TIMEOUT DEMO ===
🔧 Connection Factory Configuration:
   - Channel cache size: 3
   - Channel checkout timeout: 5s
   - Profile: limited
   - This LIMITS total channels and makes threads WAIT

🔒 Thread 1 starting - will block channel for 15 seconds
✅ Thread 1 acquired channel: 123456789
🔒 Thread 2 starting - will block channel for 15 seconds
✅ Thread 2 acquired channel: 987654321  
🔒 Thread 3 starting - will block channel for 15 seconds
✅ Thread 3 acquired channel: 456789123

⏰ 4th Thread starting - should TIMEOUT after 5 seconds
   (All 3 channels are blocked, no new channels allowed)
✅ 4th Thread EXPECTED TIMEOUT after ~5000ms
   Exception: Channel checkout timeout after 5000ms
   This proves channelCheckoutTimeout is working!

🔄 6th Thread starting after 8 seconds - channels might be available now
✅ 6th Thread SUCCESS: Got channel 789123456 after 25ms
   (Some channels may have been released by now)
```

## How to Run

```bash
# Run unlimited demo
./gradlew bootRun --args='--spring.profiles.active=unlimited'

# Run limited demo  
./gradlew bootRun --args='--spring.profiles.active=limited'
```


## Key Observations to Look For

### Unlimited Demo Success Indicators:
- All 4 threads get channels successfully
- 4th thread gets channel almost immediately (< 50ms)
- Channel hash codes show 4 different channels
- No timeout exceptions occur

### Limited Demo Behavior Indicators:
- First 3 threads get channels successfully
- 4th thread times out after exactly ~5000ms
- `AmqpTimeoutException` is thrown for 4th thread
- 6th thread may succeed depending on timing of channel releases

## Understanding the Difference

The demos leverage **Spring Boot's auto-configuration** for RabbitMQ. When you set `spring.rabbitmq.cache.channel.checkout-timeout` in `application.yml`, Spring Boot automatically configures the `CachingConnectionFactory` with these settings.

From the [Spring AMQP Documentation](https://docs.spring.io/spring-amqp/docs/current/reference/html/#connection-and-resource-management):

> **It is important to understand that the cache size is (by default) not a limit but is merely the number of channels that can be cached.** With a cache size of, say, 10, any number of channels can actually be in use. If more than 10 channels are being used and they are all returned to the cache, 10 go in the cache; the rest are physically closed.

> **Starting with version 2.0.2, you can set the channelCheckoutTimeout property on the CachingConnectionFactory.** When this value is greater than zero, the channelCacheSize becomes a limit, and checking out a channel blocks until a channel is available or the timeout is reached.

## Performance Implications

- **Unlimited (Default)**:
  - ✅ No blocking - immediate channel access
  - ❌ Higher memory usage - unlimited channel creation
  - ❌ Potential resource exhaustion under high load
  - **Best for**: Low to medium load scenarios

- **Limited (With Timeout)**:
  - ✅ Controlled memory usage - strict channel limits
  - ✅ Predictable resource consumption
  - ❌ Potential blocking - threads may wait or timeout
  - **Best for**: High load scenarios requiring resource control