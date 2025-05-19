package com.Eragoo.rmq.spring.publisher

import com.Eragoo.rmq.spring.publisher.config.RabbitMQConfig
import com.Eragoo.rmq.spring.publisher.model.Message
import java.util.UUID
import kotlin.time.measureTime
import org.springframework.amqp.rabbit.core.BatchingRabbitTemplate
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.stereotype.Service

@Service
class RmqSpringPublisher(
	private val rabbitTemplate: RabbitTemplate,
	private val batchingRabbitTemplate: BatchingRabbitTemplate
) {

	fun simplePublish() {
		val messages = mutableListOf<Message>()
		repeat(1_000_000) { index ->
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

	fun batchPublish() {
		val messages = mutableListOf<Message>()
		repeat(1_000_000) { index ->
			val message = Message(
				id = UUID.randomUUID().toString(),
				content = "Test message $index"
			)
			messages.add(message)
		}
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

@SpringBootApplication
class Application

fun main(args: Array<String>) {
	val runApplication = runApplication<Application>(*args)
	val application = runApplication.getBean(RmqSpringPublisher::class.java)
	application.batchPublish()
}
