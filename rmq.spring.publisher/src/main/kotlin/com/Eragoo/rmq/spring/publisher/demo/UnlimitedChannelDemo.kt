package com.Eragoo.rmq.spring.publisher.demo

import com.Eragoo.rmq.spring.publisher.config.RabbitMQConfig
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
@Profile("unlimited")
class UnlimitedChannelDemo : CommandLineRunner {

    @Autowired
    private lateinit var connectionFactory: CachingConnectionFactory
    
    @Autowired
    private lateinit var rabbitTemplate: RabbitTemplate

    override fun run(vararg args: String?) {
        
        println("\n=== UNLIMITED CHANNEL CREATION DEMO ===")
        println("This demo shows what happens when channel cache is exhausted")
        println("Expected: 4th thread will get a NEW channel immediately (no waiting)")
        
        // Display actual configuration from application.yml
        println("\n🔧 Connection Factory Configuration (from application.yml):")
        println("   - Host: ${connectionFactory.host}")
        println("   - Port: ${connectionFactory.port}")
        println("   - Channel cache size: ${connectionFactory.channelCacheSize}")
        println("   - Channel checkout timeout: 0 = unlimited")
        println("   - Profile: unlimited")
        
        val startLatch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(6)
        
        // 5th thread - Monitoring thread to print channel statistics
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
        
        // Threads 1-3: Block channels using invoke for 12 seconds
        repeat(3) { threadNum ->
            executor.submit {
                try {
                    startLatch.await()
                    println("\n🔒 Thread ${threadNum + 1} starting - will block channel for 12 seconds")
                    
                    rabbitTemplate.invoke { channel ->
                        println("✅ Thread ${threadNum + 1} acquired channel: ${channel.hashCode()}")
                        
                        // Send a message to show channel is working
                        channel.convertAndSend(
                            RabbitMQConfig.EXCHANGE_NAME,
                            RabbitMQConfig.ROUTING_KEY,
                            "Message from thread ${threadNum + 1}".toByteArray()
                        )
                        
                        // Block this channel for 12 seconds
                        Thread.sleep(12000)
                        println("🔓 Thread ${threadNum + 1} releasing channel: ${channel.hashCode()}")
                    }
                } catch (e: Exception) {
                    println("❌ Thread ${threadNum + 1} error: ${e.message}")
                }
            }
        }
        startLatch.countDown() // Start all blocking threads
        
        println("\n⏰ Waiting 3 seconds for first 3 threads to acquire channels...")
        Thread.sleep(3000)
        
        // 4th thread - Should get channel immediately (creates new one)
        executor.submit {
            try {
                println("\n🔥 4th Thread starting - should get channel IMMEDIATELY")
                val startTime = System.currentTimeMillis()
                
                rabbitTemplate.invoke { channel ->
                    val endTime = System.currentTimeMillis()
                    val duration = endTime - startTime
                    println("🚀 4th Thread SUCCESS: Got channel ${channel.hashCode()} after ${duration}ms")
                    
                    // Send a message to show it works
                    channel.convertAndSend(
                        RabbitMQConfig.EXCHANGE_NAME,
                        RabbitMQConfig.ROUTING_KEY,
                        "Message from 4th thread".toByteArray())
                    
                    // Hold for 8 seconds
                    Thread.sleep(8000)
                    println("🔓 4th Thread releasing channel: ${channel.hashCode()}")
                }
            } catch (e: Exception) {
                val endTime = System.currentTimeMillis()
                val startTime = endTime - 1000 // approximate
                println("❌ 4th Thread FAILED after ${endTime - startTime}ms: ${e.message}")
            }
        }

        println("\n🚀 All threads started!")
        
        // Wait for everything to complete
        Thread.sleep(18000)
        
        println("\n🏁 Demo completed. Key observations:")
        println("   - All 4 threads should have gotten channels")
        println("   - 4th thread got channel immediately (new channel created)")
        println("   - Channel numbers likely: 1, 2, 3, 4 (or similar)")
        
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
    }
} 