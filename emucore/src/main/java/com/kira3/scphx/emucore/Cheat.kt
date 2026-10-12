package com.kira3.scphx.emucore

import kotlinx.serialization.Serializable

/** One cheat of a game: a name and the code lines the core runs while it is [enabled]. */
@Serializable
data class Cheat(
    val id: String,
    val name: String,
    /** Pnach lines (`patch=1,EE,...`) or raw "ADDRESS VALUE" pairs, one per line. */
    val code: String,
    val enabled: Boolean = false,
)

/** What [Pnach.check] found in a cheat's code. */
class CodeCheck(
    /** The code as pnach lines. */
    val lines: List<String>,
    /** The 1-based line the core wouldn't understand, or null when every line is fine. */
    val badLine: Int?,
)

/** Converts between the cheats the app lists and the `.pnach` files the core reads. */
object Pnach {
    private val rawPair = Regex("""^([0-9A-Fa-f]{8})[\s:]+([0-9A-Fa-f]{8})$""")
    private val keyValue = Regex("""^[A-Za-z_]+=.*""")
    private val header = Regex("""^\[(.+)]$""")

    /**
     * Reads [code] line by line. Pnach lines (`patch=...`) pass through; a raw pair such as
     * "203F1234 00000001" becomes the `extended` patch line the core runs it as.
     */
    fun check(code: String): CodeCheck {
        val lines = ArrayList<String>()
        var bad: Int? = null
        code.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("//") || line.startsWith("#") -> Unit
                keyValue.matches(line) -> lines += line
                else -> {
                    val pair = rawPair.matchEntire(line)
                    if (pair != null) {
                        lines += "patch=1,EE,${pair.groupValues[1].uppercase()},extended,${pair.groupValues[2].uppercase()}"
                    } else if (bad == null) {
                        bad = index + 1
                    }
                }
            }
        }
        return CodeCheck(lines, bad)
    }

    /** The group names the core is given, one per cheat that has code, each one different. */
    fun groupNames(cheats: List<Cheat>): List<Pair<Cheat, String>> {
        val used = HashSet<String>()
        return cheats.filter { check(it.code).lines.isNotEmpty() }.map { cheat ->
            val base = cheat.name.replace(Regex("""[\[\]\r\n]"""), " ").trim().ifEmpty { "Cheat" }
            var name = base
            var n = 2
            while (!used.add(name)) name = "$base ($n)".also { n++ }
            cheat to name
        }
    }

    /** The file the core reads: one group per cheat. */
    fun build(groups: List<Pair<Cheat, String>>): String = groups.joinToString("\n") { (cheat, name) ->
        "[$name]\n" + check(cheat.code).lines.joinToString("\n") + "\n"
    }

    /** The group names a pnach file lists. */
    fun namesIn(text: String): List<String> =
        text.lines().mapNotNull { header.matchEntire(it.trim())?.groupValues?.get(1) }

    /** The groups of a pnach file as cheats, all off, for importing. */
    fun parse(text: String, newId: () -> String): List<Cheat> {
        val cheats = ArrayList<Cheat>()
        var name: String? = null
        val code = ArrayList<String>()
        fun flush() {
            val n = name
            if (n != null && code.isNotEmpty()) cheats += Cheat(newId(), n, code.joinToString("\n"))
            code.clear()
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            val head = header.matchEntire(line)
            when {
                head != null -> {
                    flush()
                    name = head.groupValues[1]
                }
                name == null || line.isEmpty() || line.startsWith("//") || line.startsWith("#") -> Unit
                line.startsWith("gametitle=", ignoreCase = true) -> Unit
                else -> code += line
            }
        }
        flush()
        return cheats
    }
}
