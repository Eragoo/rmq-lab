package com.Eragoo.rmq.spring.publisher

import com.Eragoo.rmq.spring.publisher.config.RabbitMQConfig
import com.Eragoo.rmq.spring.publisher.model.Message
import java.util.UUID
import kotlin.time.measureTime
import org.springframework.amqp.rabbit.core.BatchingRabbitTemplate
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Service

@Service
class RmqAckPublisher(
    private val rabbitTemplate: RabbitTemplate,
    private val batchingRabbitTemplate: BatchingRabbitTemplate
) {
    fun simplePublish(times: Int = 1_000_000) {
        val messages = mutableListOf<Message>()
        repeat(times) { index ->
            val message = Message(
                id = UUID.randomUUID().toString(),
                content = "Test message $index"
            )
            messages.add(message)
        }
        measureTime {
            messages.forEach { message ->
                rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_NAME,
                    RabbitMQConfig.ROUTING_KEY,
                    message
                )
            }
        }.let { millis ->
            println("Published 1M messages in $millis")
        }
    }
}