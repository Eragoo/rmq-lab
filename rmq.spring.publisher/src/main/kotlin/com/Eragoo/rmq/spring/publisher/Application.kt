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


@SpringBootApplication
class Application

fun main(args: Array<String>) {
	val runApplication = runApplication<Application>(*args)
	val application = runApplication.getBean(RmqAckPublisher::class.java)
	application.simpleAckPublish()
}
