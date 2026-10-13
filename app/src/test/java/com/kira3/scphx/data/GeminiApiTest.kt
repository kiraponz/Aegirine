package com.kira3.scphx.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiApiTest {
    @Test
    fun keepsOnlyModelsThatCanGenerateContent() {
        val root = Json.parseToJsonElement(
            """
            {"models": [
              {"name": "models/gemini-2.5-flash", "displayName": "Gemini 2.5 Flash",
               "supportedGenerationMethods": ["generateContent", "countTokens"]},
              {"name": "models/text-embedding-004", "displayName": "Embedding",
               "supportedGenerationMethods": ["embedContent"]},
              {"name": "models/no-display", "supportedGenerationMethods": ["generateContent"]}
            ], "nextPageToken": ""}
            """,
        ).jsonObject
        assertEquals(
            listOf(GeminiModel("gemini-2.5-flash", "Gemini 2.5 Flash"), GeminiModel("no-display", "no-display")),
            GeminiApi.parseModels(root),
        )
    }

    @Test
    fun anEmptyResponseHasNoModels() {
        assertEquals(emptyList<GeminiModel>(), GeminiApi.parseModels(Json.parseToJsonElement("{}").jsonObject))
    }

    private fun parse(json: String) = GeminiApi.parseAnswer(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun joinsTheTextPartsOfTheFirstCandidate() {
        assertEquals(
            "Hello, world",
            parse("""{"candidates": [{"content": {"parts": [{"text": "Hello, "}, {"text": "world"}]}}]}"""),
        )
    }

    @Test
    fun aBlockedPromptSaysWhy() {
        val error = runCatching { parse("""{"promptFeedback": {"blockReason": "SAFETY"}}""") }.exceptionOrNull()
        assertEquals("Blocked: SAFETY", (error as GeminiError.Empty).message)
    }

    @Test
    fun anAnswerThatStoppedEarlyNamesTheReason() {
        val error = runCatching { parse("""{"candidates": [{"finishReason": "MAX_TOKENS", "content": {"parts": []}}]}""") }.exceptionOrNull()
        assertEquals("Stopped: MAX_TOKENS", (error as GeminiError.Empty).message)
    }

    @Test
    fun theRequestCarriesTheImageThenTheQuestion() {
        val body = Json.parseToJsonElement(GeminiApi.requestBody("", byteArrayOf(1, 2, 3), listOf(GeminiMessage("user", "What now?")))).jsonObject
        assertEquals(null, body["systemInstruction"])
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals("image/jpeg", parts[0].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content)
        assertEquals("AQID", parts[0].jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("What now?", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun aConversationIsSentWholeWithTheImageOnlyOnTheFirstMessage() {
        val messages = listOf(
            GeminiMessage("user", "What now?"),
            GeminiMessage("model", "Go left."),
            GeminiMessage("user", "Why?"),
        )
        val contents = Json.parseToJsonElement(GeminiApi.requestBody("", byteArrayOf(1), messages))
            .jsonObject["contents"]!!.jsonArray
        assertEquals(listOf("user", "model", "user"), contents.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(listOf(2, 1, 1), contents.map { it.jsonObject["parts"]!!.jsonArray.size })
    }

    @Test
    fun noImageMeansTextOnly() {
        val contents = Json.parseToJsonElement(GeminiApi.requestBody("", null, listOf(GeminiMessage("user", "Hi"))))
            .jsonObject["contents"]!!.jsonArray
        assertEquals(1, contents[0].jsonObject["parts"]!!.jsonArray.size)
    }

    @Test
    fun aSystemPromptIsSentAsTheSystemInstruction() {
        val body = Json.parseToJsonElement(GeminiApi.requestBody("Be brief", byteArrayOf(), listOf(GeminiMessage("user", "?")))).jsonObject
        val text = body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("Be brief", text)
    }
}
