package com.example.message.repository

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.javatime.datetime



object MessageTable : IdTable<Int>() {
    override val id = integer("id").autoIncrement().entityId()
    val destination_email = varchar("destination_email", 255)
    val content = varchar("content", 255)
    val created_at = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

