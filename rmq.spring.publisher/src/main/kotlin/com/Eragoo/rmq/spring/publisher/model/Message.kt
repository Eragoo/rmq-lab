package com.Eragoo.rmq.spring.publisher.model

data class Message(
    val id: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
) 