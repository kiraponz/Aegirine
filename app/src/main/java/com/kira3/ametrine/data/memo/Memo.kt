package com.kira3.ametrine.data.memo

import kotlinx.serialization.Serializable

/**
 * A note kept over a game: usually a Gemini answer ([question] is what was asked, [text] the answer).
 * [x] and [y] are where its top-left corner sits, as fractions of the screen so the place holds in
 * either orientation.
 */
@Serializable
data class Memo(
    val id: String,
    val question: String,
    val text: String,
    val x: Float,
    val y: Float,
    /** Shown as its title only. */
    val collapsed: Boolean = false,
    val createdAt: Long = 0L,
)
