package com.drishtisaathi.aivoice

import com.drishtisaathi.bridge.BridgeConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Blocking client for the backend's /anamnesis endpoints (same server address and token as the optometrist bridge).
 * Only audio of the customer's answers leaves the phone; the Sarvam key stays on the backend.
 */
class AiAnamnesisClient(private val config: BridgeConfig, private val timeoutMs: Int = 45_000) : AiAnamnesisApi {
    private val json = Json { ignoreUnknownKeys = true }

    private fun <T> decode(s: kotlinx.serialization.KSerializer<T>, text: String): T =
        try { json.decodeFromString(s, text) } catch (e: IllegalArgumentException) { throw AiException("SERVER") }

    override fun start(language: AiLanguage): AiStartResponse =
        decode(AiStartResponse.serializer(), post("/anamnesis/start", buildJsonObject { put("language", language.code) }.toString()))

    override fun turn(sessionId: String, wavBase64: String): AiTurnResponse =
        decode(AiTurnResponse.serializer(), post("/anamnesis/turn", buildJsonObject {
            put("session_id", sessionId); put("audio", wavBase64)
        }.toString()))

    override fun finish(sessionId: String): AiProfile =
        decode(AiProfile.serializer(), post("/anamnesis/finish", buildJsonObject { put("session_id", sessionId) }.toString()))

    private fun post(path: String, body: String): String {
        if (!config.isValid) throw AiException("NO_SERVER")
        val conn = try {
            URL(config.baseUrl + path).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw AiException("NO_SERVER")
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (config.token.isNotBlank()) conn.setRequestProperty("X-Bridge-Token", config.token)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) return text
            if (code == 401) throw AiException("UNAUTHORIZED", code)
            val backendCode = runCatching { json.parseToJsonElement(text).codeOrNull() }.getOrNull()
            throw AiException(backendCode ?: if (code == 404) "NO_SERVER" else "SERVER", code)
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            throw AiException("NETWORK")
        } finally {
            conn.disconnect()
        }
    }
}
