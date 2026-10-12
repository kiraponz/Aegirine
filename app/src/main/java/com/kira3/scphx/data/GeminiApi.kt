package com.kira3.scphx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A model the key can use for generateContent. [id] is what the settings store ("gemini-2.5-flash"). */
data class GeminiModel(val id: String, val displayName: String)

/** Why a call to the Gemini API failed, for the screen to word. */
sealed class GeminiError(message: String) : Exception(message) {
    class NoKey : GeminiError("No API key")
    class Rejected(val code: Int, detail: String) : GeminiError("HTTP $code: $detail")
    /** The API answered but with nothing to show: blocked, or an empty reply. */
    class Empty(message: String) : GeminiError(message)
    class Network(cause: Throwable) : GeminiError(cause.message ?: "Network error")
}

/** The Gemini REST API, called directly: no Google SDK, so nothing proprietary is linked in. */
object GeminiApi {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private val json = Json { ignoreUnknownKeys = true }

    /** The models [apiKey] can call generateContent on, in the order the API lists them. */
    suspend fun listModels(apiKey: String): Result<List<GeminiModel>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(GeminiError.NoKey())
        runCatching {
            val models = mutableListOf<GeminiModel>()
            var pageToken: String? = null
            do {
                val query = "pageSize=1000" + (pageToken?.let { "&pageToken=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
                val root = json.parseToJsonElement(request("$BASE/models?$query", apiKey, null)).jsonObject
                models += parseModels(root)
                pageToken = root["nextPageToken"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
            } while (pageToken != null)
            models.toList()
        }.recoverCatching { throw if (it is GeminiError) it else GeminiError.Network(it) }
    }

    /** The models in one page of the API's list that support generateContent, with the "models/" prefix dropped. */
    internal fun parseModels(root: JsonObject): List<GeminiModel> =
        (root["models"] as? JsonArray).orEmpty().mapNotNull { element ->
            val model = element.jsonObject
            val methods = (model["supportedGenerationMethods"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            val name = model["name"]?.jsonPrimitive?.contentOrNull
            if (name == null || "generateContent" !in methods) return@mapNotNull null
            val id = name.removePrefix("models/")
            GeminiModel(id, model["displayName"]?.jsonPrimitive?.contentOrNull ?: id)
        }

    /**
     * Asks [model] about [image] (a JPEG) and returns its answer as text. [systemPrompt] is sent as the
     * system instruction when it is not blank.
     */
    suspend fun ask(apiKey: String, model: String, systemPrompt: String, image: ByteArray, question: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext Result.failure(GeminiError.NoKey())
            runCatching {
                val body = requestBody(systemPrompt, image, question)
                parseAnswer(json.parseToJsonElement(request("$BASE/models/$model:generateContent", apiKey, body)).jsonObject)
            }.recoverCatching { throw if (it is GeminiError) it else GeminiError.Network(it) }
        }

    internal fun requestBody(systemPrompt: String, image: ByteArray, question: String): String = buildJsonObject {
        if (systemPrompt.isNotBlank()) {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", systemPrompt) }) }
            }
        }
        putJsonArray("contents") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(
                            buildJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", "image/jpeg")
                                    put("data", java.util.Base64.getEncoder().encodeToString(image))
                                }
                            },
                        )
                        add(buildJsonObject { put("text", question) })
                    }
                },
            )
        }
    }.toString()

    /** The text of the first candidate, or a [GeminiError.Empty] saying why there is none. */
    internal fun parseAnswer(root: JsonObject): String {
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
        val text = (candidate?.get("content")?.jsonObject?.get("parts") as? JsonArray).orEmpty()
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("")
        if (text.isNotBlank()) return text
        val blocked = root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
        val finish = candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull
        throw GeminiError.Empty(blocked?.let { "Blocked: $it" } ?: finish?.let { "Stopped: $it" } ?: "Empty answer")
    }

    /** Sends [body] as a POST, or a GET without one, with the key in a header, not the URL, so it does not end up in logs. */
    private fun request(url: String, apiKey: String, body: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = if (body == null) 20_000 else 90_000
            connection.setRequestProperty("x-goog-api-key", apiKey)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val detail = runCatching {
                    json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                }.getOrNull() ?: body.take(200)
                throw GeminiError.Rejected(code, detail)
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw GeminiError.Network(e)
        } finally {
            connection.disconnect()
        }
    }
}
