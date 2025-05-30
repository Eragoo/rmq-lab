package com.Eragoo.rmq.spring.publisher

import com.Eragoo.rmq.spring.publisher.config.RabbitMQConfig
import com.Eragoo.rmq.spring.publisher.model.Message
import java.util.UUID
import kotlin.time.measureTime
import org.springframework.amqp.core.Message as RmqMessage
import org.springframework.amqp.core.ReturnedMessage
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.connection.CorrelationData
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.stereotype.Service

@Service
class RmqCorrelatedAckPublisher(
    private val connectionFactory: ConnectionFactory,
) {

    fun correlatedAckPublish(times: Int = 1_000_000) {
        val messages = mutableListOf<Message>()
        repeat(times) { index ->
            val message = Message(
                id = UUID.randomUUID().toString(),
                content = "Test message $index"
            )
            messages.add(message)
        }
        
        val rabbitTemplate = createCorrelatedAckTemplate()
        
        measureTime {
            messages.chunked(10_000).forEach { chunk ->
                rabbitTemplate.invoke { channel ->
                    chunk.forEach { message ->
                        val correlationData = CorrelationData(message.id)
                        correlationData.setReturned(ReturnedMessage(
                            RmqMessage(message.content.toByteArray()),
                            1,
                            message.id,
                            RabbitMQConfig.EXCHANGE_NAME,
                            RabbitMQConfig.ROUTING_KEY,
                        ))
                        channel.convertAndSend(
                            RabbitMQConfig.EXCHANGE_NAME,
                            RabbitMQConfig.ROUTING_KEY,
                            message,
                            { msg: RmqMessage -> msg },
                            correlationData
                        )
                    }
                }
            }
        }.let { millis ->
            println("Published $times messages successfully in $millis")
        }
    }
    
    private fun createCorrelatedAckTemplate(): RabbitTemplate {
        val template = RabbitTemplate(connectionFactory)
        template.messageConverter = Jackson2JsonMessageConverter()
        
        template.setMandatory(true)

        template.setConfirmCallback { correlationData, ack, cause ->
            if (!ack) {
                println("Message not acknowledged: ${correlationData?.id}")
                println("Body: ${correlationData?.returned?.message?.body?.decodeToString()}")
            }
        }
        
        return template
    }
} 