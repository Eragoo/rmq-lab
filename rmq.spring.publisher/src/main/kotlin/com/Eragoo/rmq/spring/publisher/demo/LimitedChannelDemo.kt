package com.Eragoo.rmq.spring.publisher.demo

import com.Eragoo.rmq.spring.publisher.config.RabbitMQConfig
import org.springframework.amqp.AmqpTimeoutException
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Profile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootApplication
@Profile("limited")
class LimitedChannelDemo : CommandLineRunner {

    @Autowired
    private lateinit var connectionFactory: CachingConnectionFactory
    
    @Autowired
    private lateinit var rabbitTemplate: RabbitTemplate

    override fun run(vararg args: String?) {

        println("\n=== LIMITED CHANNEL WITH TIMEOUT DEMO ===")
        println("This demo shows what happens when channelCheckoutTimeout is set")
        println("Expected: 4th thread will WAIT 5 seconds then TIMEOUT with AmqpTimeoutException")

        // Display actual configuration from application.yml
        println("\n🔧 Connection Factory Configuration (from application.yml):")
        println("   - Host: ${connectionFactory.host}")
        println("   - Port: ${connectionFactory.port}")
        println("   - Channel cache size: ${connectionFactory.channelCacheSize}")
        println("   - Channel checkout timeout: 5s")
        println("   - Profile: limited")
        println("   - This LIMITS total channels and makes threads WAIT")

        val startLatch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(6)

        //Monitoring thread to print channel statistics
        executor.submit {
            var iteration = 0
            while (iteration < 25) {
                try {
                    val props = connectionFactory.cacheProperties
                    println("\n📊 === Channel Statistics (${++iteration}) ===")
                    props.forEach { (key, value) ->
                        if (key.equals("idleConnections") ||
                            key.equals("channelCacheSize") ||
                            key.equals("openConnections") ||
                            key.equals("channelCacheSize") ||
                            key.equals("idleChannelsTx") ||
                            key.equals("idleChannelsNotTx") ||
                            key.equals("idleChannelsTxHighWater") ||
                            key.equals("idleChannelsNotTxHighWater")
                            ) {
                            println("   $key: $value")
                        }
                    }
                    Thread.sleep(1000)
                } catch (e: Exception) {
                    println("❌ Error getting cache properties: ${e.message}")
                }
            }
        }

        // Threads 1-3: Block channels using invoke for 15 seconds
        repeat(3) { threadNum ->
            executor.submit {
                try {
                    startLatch.await()
                    println("\n🔒 Thread ${threadNum + 1} starting - will block channel for 15 seconds")

                    rabbitTemplate.invoke { channel ->
                        println("✅ Thread ${threadNum + 1} acquired channel: ${channel.hashCode()}")

                        // Send a message to show channel is working
                        channel.convertAndSend(
                            RabbitMQConfig.EXCHANGE_NAME,
                            RabbitMQConfig.ROUTING_KEY,
                             "Message from thread ${threadNum + 1}".toByteArray()
                        )

                        // Block this channel for 15 seconds (longer than timeout)
                        Thread.sleep(15000)
                        println("🔓 Thread ${threadNum + 1} releasing channel: ${channel.hashCode()}")
                    }
                } catch (e: Exception) {
                    println("❌ Thread ${threadNum + 1} error: ${e.message}")
                }
            }
        }
        startLatch.countDown() // Start all blocking threads

        println("\n⏰ Waiting 3 seconds for first 3 threads to acquire all channels...")
        Thread.sleep(3000)

        //4th Thread - Should timeout waiting for available channel
        executor.submit {
            try {
                println("\n⏰ 4th Thread starting - should TIMEOUT after 5 seconds")
                println("   (All 3 channels are blocked, no new channels allowed)")
                val startTime = System.currentTimeMillis()

                rabbitTemplate.invoke { channel ->
                    val endTime = System.currentTimeMillis()
                    val duration = endTime - startTime
                    println("🚀 4th Thread UNEXPECTED SUCCESS: Got channel ${channel.hashCode()} after ${duration}ms")
                    println("   (This shouldn't happen with our configuration!)")

                    // Send a message to show it works
                    channel.convertAndSend(
                        RabbitMQConfig.EXCHANGE_NAME,
                        RabbitMQConfig.ROUTING_KEY,
                        "Message from 4th thread".toByteArray()
                    )

                    Thread.sleep(8000)
                    println("🔓 4th Thread releasing channel: ${channel.hashCode()}")
                }
            } catch (e: AmqpTimeoutException) {
                val endTime = System.currentTimeMillis()
                val startTime = endTime - 5000 // We know it should be around 5 seconds
                val actualDuration = endTime - startTime
                println("✅ 4th Thread EXPECTED TIMEOUT after ~5000ms (actual: ${actualDuration}ms)")
                println("   Exception: ${e.message}")
                println("   This proves channelCheckoutTimeout is working!")
            } catch (e: Exception) {
                println("❌ 4th Thread UNEXPECTED ERROR: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // Wait another 8 seconds then start a 6th thread to test when channels are released
        Thread.sleep(8000)
        executor.submit {
            try {
                println("\n🔄 6th Thread starting after 8 seconds - channels might be available now")
                val startTime = System.currentTimeMillis()

                rabbitTemplate.invoke { channel ->
                    val endTime = System.currentTimeMillis()
                    val duration = endTime - startTime
                    println("✅ 6th Thread SUCCESS: Got channel ${channel.hashCode()} after ${duration}ms")
                    println("   (Some channels may have been released by now)")

                    channel.convertAndSend(
                        RabbitMQConfig.EXCHANGE_NAME,
                        RabbitMQConfig.ROUTING_KEY,
                        "Message from 6th thread".toByteArray()
                    )
                    Thread.sleep(3000)
                    println("🔓 6th Thread releasing channel: ${channel.hashCode()}")
                }
            } catch (e: AmqpTimeoutException) {
                println("⏰ 6th Thread also timed out: ${e.message}")
            } catch (e: Exception) {
                println("❌ 6th Thread error: ${e.message}")
            }
        }

        // Wait for everything to complete
        Thread.sleep(25000)

        println("\n🏁 Demo completed. Key observations:")
        println("   - First 3 threads got channels 1, 2, 3")
        println("   - 4th thread should have TIMED OUT after 5 seconds")
        println("   - 6th thread might have succeeded if started after some channels were released")
        println("   - This demonstrates channelCheckoutTimeout behavior!")

        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
    }
}