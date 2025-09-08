package com.example.message

import com.example.message.models.Message
import com.example.listenerConnection
import com.example.message.repository.getAllMessages
import io.ktor.server.application.Application
import java.util.logging.Logger
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json.Default.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.postgresql.PGConnection
import java.util.Collections
import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import org.postgresql.PGNotification

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


val notificationSharedFlow = MutableSharedFlow<PGNotification>(
    extraBufferCapacity = 100,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)

val messageNotificationSharedFlow = MutableSharedFlow<Pair<String, List<Message>>>(
    extraBufferCapacity = 100,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)

// One coroutine reads from Postgres and pushes notifications into the shared flow
suspend fun startNotificationEmitter(pgConn: PGConnection, maxRetries: Int = 5) {
    var retries = 0
    while (true) {
        try {
            startNotificationEmitterUnsafe(pgConn)
        } catch (e: Exception) {
            retries++
            Logger.getLogger("MessageListener")
                .severe("Emitter failed (attempt $retries): ${e.message}")
            if (retries >= maxRetries) throw e // Let K8s restart
            delay(5000)
        }
    }
}

private suspend fun startNotificationEmitterUnsafe(pgConn: PGConnection) {
    if (pgConn !is java.sql.Connection) error("Not a SQL Connection")

    pgConn.createStatement().use { stmt -> stmt.execute("LISTEN tickets_channel;") }
    while (true) {
        delay(500)
        val notifications = pgConn.notifications
        notifications?.forEach { notification ->

            notificationSharedFlow.emit(notification)
        }
    }
}

private suspend fun startMessageNotificationEmitter() {
    notificationSharedFlow
        .filter { it.name == "tickets_channel" }
        .collect { notification ->
            val json = Json.parseToJsonElement(notification.parameter).jsonObject
            val email = json["destinationEmail"]?.jsonPrimitive?.contentOrNull ?: return@collect
            val messages = getAllMessages(email)
            messageNotificationSharedFlow.emit(email to messages)
        }
}

fun Application.listenToNotifications() {
    val pgConn = listenerConnection.unwrap(PGConnection::class.java)
    launch {
        startNotificationEmitter(pgConn)
    }
    launch {
        startMessageNotificationEmitter()
    }
    launch {
        messageNotificationSharedFlow.collect { (email, messages) ->
            broadcastTicketsToSubscribers(email, messages)
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