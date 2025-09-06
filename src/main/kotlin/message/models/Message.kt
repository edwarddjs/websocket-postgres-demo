package com.example.message.models

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: Int,
    val destinationEmail: String,
    val content: String,
    val createdAt: String //Timestamp
)