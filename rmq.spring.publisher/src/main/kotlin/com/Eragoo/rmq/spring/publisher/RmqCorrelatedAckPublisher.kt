package com.Eragoo.rmq.spring.publisher

import com.Eragoo.rmq.spring.publisher.config.RabbitMQConfig
import com.Eragoo.rmq.spring.publisher.model.Message
import java.util.concurrent.ConcurrentHashMap
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.connection.CorrelationData
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.stereotype.Service

@Service
class RmqCorrelatedAckPublisher(
    private val connectionFactory: ConnectionFactory
) {
    private val pendingConfirmations = ConcurrentHashMap<String, Message>()

    fun publishWithAsyncAck(messages: List<Message>) {
        val template = createAsyncTemplate()

        template.invoke { channel ->
            messages.forEach { message ->
                val correlationData = CorrelationData(message.id)
                pendingConfirmations[message.id] = message

                channel.convertAndSend(
                    RabbitMQConfig.EXCHANGE_NAME,
                    RabbitMQConfig.ROUTING_KEY,
                    message,
                    correlationData
                )
            }
        }

        println("Published ${messages.size} messages asynchronously")
        println("Confirmations will arrive via callbacks")
    }

    private fun createAsyncTemplate(): RabbitTemplate {
        val template = RabbitTemplate(connectionFactory)
        template.messageConverter = Jackson2JsonMessageConverter()
        template.setMandatory(true)

        template.setConfirmCallback { correlationData, ack, cause ->
            val messageId = correlationData?.id
            val message = messageId?.let { pendingConfirmations.remove(it) }

            if (!ack && message != null) {
                println("❌ Message ${message.id} NACKed: $cause")
                retry(message)
            }
        }

        return template
    }

    private fun retry(message: Message) {
        println("🔄 Retrying message: ${message.id}")
    }
}