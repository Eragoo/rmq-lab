package com.Eragoo.rmq.spring.publisher.config

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbit.batch.BatchingStrategy
import org.springframework.amqp.rabbit.batch.SimpleBatchingStrategy
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.BatchingRabbitTemplate
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler


@Configuration
class RabbitMQConfig {

    companion object {
        const val QUEUE_NAME = "test.queue"
        const val EXCHANGE_NAME = "test.exchange"
        const val ROUTING_KEY = "test.routing.key"
    }

    @Bean
    fun rabbitAdmin(connectionFactory: ConnectionFactory): RabbitAdmin {
        val rabbitAdmin = RabbitAdmin(connectionFactory)
        val exchange = exchange()
        rabbitAdmin.declareExchange(exchange)
        println("Exchange '${exchange.name}' declared")

        // Declare queue
        val queue = queue()
        rabbitAdmin.declareQueue(queue)
        println("Queue '${queue.name}' declared")

        // Declare binding
        val binding = binding(queue, exchange)
        rabbitAdmin.declareBinding(binding)
        println("Binding declared between exchange '${exchange.name}' and queue '${queue.name}' with routing key '$ROUTING_KEY'")
        return rabbitAdmin
    }

    @Bean
    fun queue(): Queue {
        return Queue(QUEUE_NAME, true)
    }

    @Bean
    fun exchange(): DirectExchange {
        return DirectExchange(EXCHANGE_NAME)
    }

    @Bean
    fun binding(queue: Queue, exchange: DirectExchange): Binding {
        return BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY)
    }

    @Bean
    fun rabbitTemplate(connectionFactory: ConnectionFactory): RabbitTemplate {
        val rabbitTemplate = RabbitTemplate(connectionFactory)
        rabbitTemplate.messageConverter = Jackson2JsonMessageConverter()
        return rabbitTemplate
    }

    @Bean
    fun batchingRabbitTemplate(connectionFactory: ConnectionFactory): BatchingRabbitTemplate {
        val strategy: BatchingStrategy = SimpleBatchingStrategy(10, 25000, 3000)
        val scheduler: TaskScheduler = ConcurrentTaskScheduler(ScheduledThreadPoolExecutor(1))
        val template = BatchingRabbitTemplate(strategy, scheduler)
        template.messageConverter = Jackson2JsonMessageConverter()
        template.setConnectionFactory(connectionFactory)
        return template
    }
} 