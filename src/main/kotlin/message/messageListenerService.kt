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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retry
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

fun listenForNotificationsUnsafe(pgConn: PGConnection): Flow<Pair<String, List<Message>>> = flow {
    val sqlConn = if (pgConn is java.sql.Connection) pgConn else error("Not a SQL Connection")
    sqlConn.createStatement().use { stmt ->
        stmt.execute("LISTEN tickets_channel;")
    }

    while (true) {
        delay(500)
        val notifications = pgConn.notifications
        notifications
            ?.filter { it.name == "tickets_channel" }
            ?.forEach { notification ->
                val json = parseToJsonElement(notification.parameter).jsonObject
                val destinationEmail = json["destinationEmail"]?.jsonPrimitive?.contentOrNull ?: return@forEach

                val updatedMessages = getAllMessages(destinationEmail)
                emit(destinationEmail to updatedMessages)
            }
    }
}

fun listenForNotifications(pgConn: PGConnection, maxRetries: Long = 5): Flow<Pair<String, List<Message>>> {
    return listenForNotificationsUnsafe(pgConn)
        .retry(retries = maxRetries) { e ->
            Logger.getLogger("MessageListener")
                .severe("Flow failed, retrying: ${e.message}")
            true // retry on any exception
        }
        .catch { e ->
            Logger.getLogger("MessageListener").severe("Flow failed permanently: ${e.message}")
            throw e // propagate to let Kubernetes restart pod
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
        val pgConn = listenerConnection.unwrap(PGConnection::class.java)
        listenForNotifications(pgConn).collect { (destinationEmail, updatedMessages) ->
            broadcastTicketsToSubscribers(destinationEmail, updatedMessages)
        }
    }
}