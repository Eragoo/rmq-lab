package com.Eragoo.rmq.spring.publisher

import kotlin.collections.component1
import kotlin.collections.component2
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory

object RmqUtils {
    fun monitorConnectionsCache(connectionFactory: CachingConnectionFactory, freq: Long = 1000L) {
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
                Thread.sleep(freq)
            } catch (e: Exception) {
                println("❌ Error getting cache properties: ${e.message}")
            }
        }
    }
}