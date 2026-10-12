package com.kira3.scphx.emucore.settings

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * [settings] read and edited by dotted path ("graphics.fxaa"), the same paths
 * [SettingsOverrides] records. For screens that describe their settings as data.
 */
class SettingsValues(val settings: EmulatorSettings) {
    private val json: JsonObject = SettingsJson.encode(settings)

    /** The value at [path] as a Boolean, Int, Float or String. Throws if there is no such setting. */
    operator fun get(path: String): Any {
        val value = json.valueAt(path).jsonPrimitive
        return when {
            value.isString -> value.content
            else -> value.booleanOrNull ?: value.intOrNull ?: value.floatOrNull ?: value.content
        }
    }

    fun boolean(path: String): Boolean = get(path) as Boolean

    fun int(path: String): Int = get(path) as Int

    /** Also reads a Float setting that holds a whole number, which [get] returns as an Int. */
    fun float(path: String): Float = (get(path) as Number).toFloat()

    fun string(path: String): String = get(path) as String

    /** [settings] with the value at [path] set to [value] (Boolean, Int, Float or String). */
    fun with(path: String, value: Any): EmulatorSettings {
        get(path)
        val primitive = when (value) {
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            else -> throw IllegalArgumentException("Unsupported value for $path: $value")
        }
        return SettingsJson.withValue(settings, path, primitive)
    }

    override fun equals(other: Any?): Boolean = other is SettingsValues && other.settings == settings
    override fun hashCode(): Int = settings.hashCode()
}
