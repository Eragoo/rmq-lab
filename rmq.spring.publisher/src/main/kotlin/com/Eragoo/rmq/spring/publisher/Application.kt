package com.Eragoo.rmq.spring.publisher

import com.Eragoo.rmq.spring.publisher.model.Message
import java.util.UUID
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication


@SpringBootApplication
class Application

fun main(args: Array<String>) {
	val runApplication = runApplication<Application>(*args)
	val application = runApplication.getBean(RmqCorrelatedAckPublisher::class.java)

	val times = 1_000_002
	val messages = mutableListOf<Message>()
	repeat(times) { index ->
		val message = Message(
			id = UUID.randomUUID().toString(),
			content = "Test message $index"
		)
		messages.add(message)
	}

	application.publishWithAsyncAck(messages)
}
