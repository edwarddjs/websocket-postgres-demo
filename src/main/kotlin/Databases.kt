package com.example

import io.ktor.server.application.Application
import org.jetbrains.exposed.sql.Database
import java.sql.DriverManager

val url = "jdbc:postgresql://localhost:5432/mydb"
val user = "user"
val password = "password"

val listenerConnection = DriverManager.getConnection(url, user, password)

fun Application.configureDatabase() {
    Database.connect(
        url = url,
        driver = "org.postgresql.Driver",
        user = user,
        password = password
    )
}
