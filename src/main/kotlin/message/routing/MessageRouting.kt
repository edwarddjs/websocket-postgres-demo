package com.example.message.routing

import com.example.message.repository.insertMessage
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun Application.configureMessageRoutes() {
    routing {
        post("/message/{destinationEmail}") {
            val content = call.receive<String>()
            val destinationEmail = call.parameters["destinationEmail"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing or malformed destinationEmail")

            val savedMessage = insertMessage(content, destinationEmail)
            call.respond(HttpStatusCode.Created, savedMessage)
        }
    }
}