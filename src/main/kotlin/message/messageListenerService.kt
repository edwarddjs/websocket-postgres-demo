package com.example.message

import com.example.message.models.Message
import com.example.listenerConnection
import com.example.message.repository.getAllMessages
import io.ktor.server.application.Application
import java.util.logging.Logger
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json.Default.encodeToString
import kotlinx.serialization.json.Json.Default.parseToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.postgresql.PGConnection
import java.util.Collections
import io.ktor.websocket.Frame
import kotlinx.serialization.json.contentOrNull

data class ClientSubscription(
    val session: WebSocketSession,
    val destinationEmail: String
)

private val subscriptions = Collections.synchronizedSet(mutableSetOf<ClientSubscription>())

fun subscribeToMessages(session: WebSocketSession, destinationEmail: String) {
    val subscription = ClientSubscription(session, destinationEmail)
    subscriptions += subscription
}

fun unsubscribeFromMessages(session: WebSocketSession, destinationEmail: String) {
    val subscription = subscriptions.find { it.session == session && it.destinationEmail == destinationEmail }
    subscriptions -= subscription
}

fun unsubscribeFromAllMessages(session: WebSocketSession) {
    subscriptions.removeIf { it.session == session }
}

suspend fun listenForNotifications() {
    listenerConnection.createStatement().use { stmt ->
        stmt.execute("LISTEN tickets_channel;")
    }

    val pgConn = listenerConnection.unwrap(PGConnection::class.java)

    while (true) {
        Thread.sleep(500)
        val notifications = pgConn.notifications
        notifications?.
            filter { it.name == "tickets_channel" }?.
            forEach { notification ->
                val json = parseToJsonElement(notification.parameter).jsonObject
                val destinationEmail = json["destinationEmail"]?.jsonPrimitive?.contentOrNull ?: return@forEach

                // Fetch updated messages list for that ticket
                val updatedMessages = getAllMessages(destinationEmail)

                broadcastTicketsToSubscribers(destinationEmail, updatedMessages)
            }
    }

}

private suspend fun broadcastTicketsToSubscribers(
    destinationEmail: String,
    updatedMessages: List<Message>
) {
    val dead = mutableListOf<ClientSubscription>()
    subscriptions.forEach { sub ->
        if (destinationEmail == sub.destinationEmail) {
            try {
                sub.session.send(Frame.Text(encodeToString(updatedMessages)))
            } catch (e: Exception) {
                Logger.getLogger("MessageListener").warning("Failed to send message to client: ${e.message}")
                dead += sub
            }
        }
    }
    subscriptions.removeAll(dead)
}

fun Application.listenToMessageNotifications() {
    launch {
        listenForNotifications()
    }
}