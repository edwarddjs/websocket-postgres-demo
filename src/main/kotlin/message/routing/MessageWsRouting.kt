package com.example.message.routing

import com.example.message.subscribeToMessages
import com.example.message.unsubscribeFromAllMessages
import com.example.message.unsubscribeFromMessages
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json.Default.parseToJsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.logging.Logger

fun Application.configureMessageSockets() {
    routing {
        webSocket("/ws/message") { // websocketSession
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        Logger.getLogger("websocket Logger").info("Received frame: $frame")
                        val text = frame.readText()
                        // Expect client to send JSON like { "subscribe": test@example.com }
                        val obj = parseToJsonElement(text).jsonObject

                        obj["subscribe"]?.jsonPrimitive?.contentOrNull?.let { destinationEmail ->
                            subscribeToMessages(this, destinationEmail)
                        }

                        obj["unsubscribe"]?.jsonPrimitive?.contentOrNull?.let { destinationEmail ->
                            unsubscribeFromMessages(this, destinationEmail)
                        }
                    }
                }
            } finally {
                unsubscribeFromAllMessages(this)
            }
        }
    }
}
