package com.example.message.repository

import com.example.message.models.Message
import org.jetbrains.exposed.sql.select
import java.time.LocalDateTime
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction

fun getAllMessages(destinationEmail: String): List<Message> =
    transaction {
        MessageTable.select { MessageTable.destination_email eq destinationEmail }.map {
            Message(
                id = it[MessageTable.id].value,
                destinationEmail = it[MessageTable.destination_email],
                content = it[MessageTable.content],
                createdAt = it[MessageTable.created_at].toString()
            )
        }
    }

fun insertMessage(content: String, destinationEmail: String): Message {
    val id = transaction {
        MessageTable.insertAndGetId {
            it[destination_email] = destinationEmail
            it[MessageTable.content] = content
            it[created_at] = LocalDateTime.now()
        }.value
    }

    return Message(
        id = id,
        destinationEmail = destinationEmail,
        content = content,
        createdAt = LocalDateTime.now().toString()
    )
}
