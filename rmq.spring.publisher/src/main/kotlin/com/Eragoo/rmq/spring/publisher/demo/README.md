# Channel Management Demo Classes

These two demo classes showcase the different behaviors of Spring AMQP channel management when the channel cache is exhausted. The configuration is managed through `application.yml` profiles.

## Prerequisites

1. **RabbitMQ Server Running**: Make sure RabbitMQ is running on `localhost:5672`
2. **Test Queue**: Create a queue named `test-queue` (or demos will fail when publishing)

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

### 1. UnlimitedChannelDemo (Default Behavior)

**Profile**: `unlimited`

**Configuration** (from `application.yml`):
```yaml
spring:
  profiles: unlimited
  rabbitmq:
    cache:
      channel:
        size: 3
        checkout-timeout: 0  # Default - unlimited channel creation
```

**Expected Behavior**:
- First 3 threads acquire channels 1, 2, 3 and block them for 12 seconds
- 4th thread **immediately gets a NEW channel (channel 4)** without waiting
- All threads complete successfully
- Shows **unlimited channel creation** when cache is exhausted

### 2. LimitedChannelDemo (With Timeout)

**Profile**: `limited`

**Configuration** (from `application.yml`):
```yaml
spring:
  profiles: limited
  rabbitmq:
    cache:
      channel:
        size: 3
        checkout-timeout: 5000  # 5 seconds timeout
```

**Expected Behavior**:
- First 3 threads acquire channels 1, 2, 3 and block them for 15 seconds
- 4th thread **waits 5 seconds then TIMES OUT** with `AmqpTimeoutException`
- 6th thread (started later) might succeed when channels are released
- Shows **limited channel behavior** with timeout

## How to Run

### Option 1: Using Spring Boot Profiles

```bash
# Run unlimited demo
./gradlew bootRun --args='--spring.profiles.active=unlimited'

# Run limited demo  
./gradlew bootRun --args='--spring.profiles.active=limited'
```

### Option 2: Using IDE

1. Set active profile in your IDE:
   - For unlimited: `--spring.profiles.active=unlimited`
   - For limited: `--spring.profiles.active=limited`

2. Run the respective main class

### Option 3: Using Different application.yml profiles

The demo uses the main `application.yml` file with profile-specific configurations. The configuration is automatically loaded based on the active profile.

## Key Observations to Look For

### Unlimited Demo:
```
✅ Thread 1 acquired channel: 1
✅ Thread 2 acquired channel: 2  
✅ Thread 3 acquired channel: 3
🚀 4th Thread SUCCESS: Got channel 4 after 15ms  # ← NEW CHANNEL CREATED
```

### Limited Demo:
```
✅ Thread 1 acquired channel: 1
✅ Thread 2 acquired channel: 2
✅ Thread 3 acquired channel: 3
✅ 4th Thread EXPECTED TIMEOUT after ~5000ms     # ← TIMEOUT EXCEPTION
   Exception: Channel checkout timeout after 5000ms
```

## Understanding the Difference

The demos leverage **Spring Boot's auto-configuration** for RabbitMQ. When you set `spring.rabbitmq.cache.channel.checkout-timeout` in `application.yml`, Spring Boot automatically configures the `CachingConnectionFactory` with these settings.

From the [Spring AMQP Documentation](https://docs.spring.io/spring-amqp/docs/current/reference/html/#connection-and-resource-management):

> **It is important to understand that the cache size is (by default) not a limit but is merely the number of channels that can be cached.** With a cache size of, say, 10, any number of channels can actually be in use. If more than 10 channels are being used and they are all returned to the cache, 10 go in the cache; the rest are physically closed.

> **Starting with version 2.0.2, you can set the channelCheckoutTimeout property on the CachingConnectionFactory.** When this value is greater than zero, the channelCacheSize becomes a limit, and checking out a channel blocks until a channel is available or the timeout is reached.

## Performance Implications

- **Unlimited**: Higher memory usage but no blocking
- **Limited**: Controlled memory usage but potential blocking/timeouts
- **Production**: Consider `channelCheckoutTimeout` for resource control 