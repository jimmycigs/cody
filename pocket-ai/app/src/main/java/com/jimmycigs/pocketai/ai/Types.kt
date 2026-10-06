package com.jimmycigs.pocketai.ai

/** A single fact in the knowledge base. */
data class Memory(
    val id: Long,
    val text: String,
    /** [SOURCE_BASE] for the starter knowledge, [SOURCE_CHAT] for things learned from the user. */
    val source: String,
    val createdAt: Long,
) {
    companion object {
        const val SOURCE_BASE = "base"
        const val SOURCE_CHAT = "chat"
    }
}

enum class Role { USER, ASSISTANT }

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val createdAt: Long,
)
