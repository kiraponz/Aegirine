package com.kira3.ametrine.emucore

import kotlinx.serialization.Serializable

/** One cheat of a game: a name and the code lines the core runs while it is [enabled]. */
@Serializable
data class Cheat(
    val id: String,
    val name: String,
    /** GameShark lines ("8009C6E4 0FFF"), one per line. */
    val code: String,
    val enabled: Boolean = false,
)

/** What [GameShark.check] found in a cheat's code. */
class CodeCheck(
    /** The code lines, as "AAAAAAAA VVVV". */
    val lines: List<String>,
    /** The 1-based line the core wouldn't understand, or null when every line is fine. */
    val badLine: Int?,
)

/** Reads the GameShark codes the core runs for a PS1 game. */
object GameShark {
    /** An 8-digit code and address, then a 4-digit value (8 digits for the core's 32-bit codes). */
    private val codeLine = Regex("""^([0-9A-Fa-f]{8})[\s:]+([0-9A-Fa-f]{4}|[0-9A-Fa-f]{8})$""")

    /** Reads [code] line by line; blank lines and comments (`#`, `;`, `//`) are skipped. */
    fun check(code: String): CodeCheck {
        val lines = ArrayList<String>()
        var bad: Int? = null
        code.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("//") -> Unit
                else -> {
                    val match = codeLine.matchEntire(line)
                    if (match != null) {
                        lines += "${match.groupValues[1].uppercase()} ${match.groupValues[2].uppercase()}"
                    } else if (bad == null) {
                        bad = index + 1
                    }
                }
            }
        }
        return CodeCheck(lines, bad)
    }
}
