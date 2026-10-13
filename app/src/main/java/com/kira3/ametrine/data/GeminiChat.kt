package com.kira3.ametrine.data

import kotlinx.serialization.Serializable

/** One turn of a conversation with Gemini: [role] is "user" or "model". */
@Serializable
data class GeminiMessage(val role: String, val text: String) {
    val fromUser: Boolean get() = role == USER

    companion object {
        const val USER = "user"
        const val MODEL = "model"
    }
}

/** A conversation about a game, kept in that game's history. The screenshot it began with is not kept. */
@Serializable
data class GeminiChat(
    val id: String,
    val updatedAt: Long,
    val messages: List<GeminiMessage>,
) {
    /** What the conversation began with, for the history list. */
    val title: String get() = messages.firstOrNull { it.fromUser }?.text ?: messages.firstOrNull()?.text.orEmpty()
}
