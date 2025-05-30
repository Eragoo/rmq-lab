package com.Eragoo.rmq.spring.publisher

import com.Eragoo.rmq.spring.publisher.config.RabbitMQConfig
import com.Eragoo.rmq.spring.publisher.model.Message
import kotlin.time.measureTime
import org.springframework.amqp.rabbit.core.BatchingRabbitTemplate
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Service

@Service
class RmqPublisher(
    private val rabbitTemplate: RabbitTemplate,
    private val batchingRabbitTemplate: BatchingRabbitTemplate
) {
    fun simplePublish(messages: List<Message>) {
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

    fun batchPublish(messages: List<Message>) {
        measureTime {
            messages.forEach { message ->
                batchingRabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_NAME,
                    RabbitMQConfig.ROUTING_KEY,
                    message
                )
            }
        }.let { millis ->
            println("Batch published 1M messages in $millis")
        }
    }
}