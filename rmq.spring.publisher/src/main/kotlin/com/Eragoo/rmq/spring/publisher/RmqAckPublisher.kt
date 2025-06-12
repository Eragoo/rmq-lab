package com.Eragoo.rmq.spring.publisher

import com.Eragoo.rmq.spring.publisher.config.RabbitMQConfig
import com.Eragoo.rmq.spring.publisher.model.Message
import kotlin.time.measureTime
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Service

@Service
class RmqAckPublisher(
    private val rabbitTemplate: RabbitTemplate,
) {
    fun simpleAckPublish(messages: List<Message>) {
        measureTime {
            messages.chunked(10_000).forEach { chunk ->
                rabbitTemplate.invoke {
                    chunk.forEach { message ->
                        it.convertAndSend(
                            RabbitMQConfig.EXCHANGE_NAME,
                            RabbitMQConfig.ROUTING_KEY,
                            message
                        )
                    }
                    it.waitForConfirmsOrDie(10_000)
                }
            }
        }.let { millis ->
            println("Published ${messages.size} messages in $millis")
        }
    }

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
            println("Published ${messages.size} messages in $millis")
        }
    }
}