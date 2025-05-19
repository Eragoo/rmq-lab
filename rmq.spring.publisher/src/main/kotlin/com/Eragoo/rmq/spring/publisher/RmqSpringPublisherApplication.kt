package com.Eragoo.rmq.spring.publisher

import com.Eragoo.rmq.spring.publisher.model.Message
import com.Eragoo.rmq.spring.publisher.service.MessagePublisher
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.util.UUID

@SpringBootApplication
class RmqSpringPublisherApplication(private val messagePublisher: MessagePublisher) {

    @Bean
    fun init() {
        // Publish some test messages
        repeat(5) { index ->
            val message = Message(
                id = UUID.randomUUID().toString(),
                content = "Test message $index"
            )
            messagePublisher.publishMessage(message)
            println("Published message: $message")
        }
    }
}

fun main(args: Array<String>) {
    runApplication<RmqSpringPublisherApplication>(*args)
} 