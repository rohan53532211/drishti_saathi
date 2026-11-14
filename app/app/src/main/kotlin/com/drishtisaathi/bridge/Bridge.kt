package com.drishtisaathi.bridge

import android.content.Context
import com.drishtisaathi.UiState
import com.drishtisaathi.engine.PowerKnown
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.anamnesis.FunctionalProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Server address and token (staff settings). */
data class BridgeConfig(val url: String = "", val token: String = "") {
    val baseUrl: String get() = url.trim().trimEnd('/')
    val isValid: Boolean get() = baseUrl.startsWith("https://") || baseUrl.startsWith("http://")
}

/** Stored per phone. The token is a shared demo secret; backups are off for the whole app (allowBackup=false). */
class BridgeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("bridge_settings", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(BridgeConfig(prefs.getString("url", "") ?: "", prefs.getString("token", "") ?: ""))
    val config: StateFlow<BridgeConfig> = _config.asStateFlow()

    fun save(c: BridgeConfig) {
        prefs.edit().putString("url", c.url.trim()).putString("token", c.token.trim()).apply()
        _config.value = c.copy(url = c.url.trim(), token = c.token.trim())
    }
}

/** How the customer agreed to the upload (both are required; the server checks too). */
data class BridgeConsent(val consentAt: Long, val spoken: Boolean, val tapped: Boolean) {
    val complete: Boolean get() = spoken && tapped
}

/**
 * The profile sent to the optometrist: answers, functional profile, power source and values, measurements and the
 * app's estimates. Numbers and choices only: there is no name or photo anywhere in the app's data, and
 * [forbiddenKeys] (the same list the server rejects) is checked by a unit test.
 */
object BridgeProfile {
    const val CONSENT_TEXT_VERSION = "bridge-1"

    /** Short case number: first 6 characters of the server id, upper case (backend/app/main.py case_id). */
    fun caseId(remoteId: String): String = remoteId.take(6).uppercase()
    /** Keep in sync with backend/app/main.py FORBIDDEN_KEYS. */
    val forbiddenKeys = setOf(
        "name", "firstname", "lastname", "fullname", "customername", "photo", "photos", "image", "images", "picture",
        "selfie", "faceimage", "frameimage", "jpeg", "png", "base64", "phone", "phonenumber", "mobile",
        "email", "address", "aadhaar", "aadhar",
    )

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private fun <T> enc(s: KSerializer<T>, v: T?): JsonElement = if (v == null) JsonNull else json.encodeToJsonElement(s, v)

    fun build(
        state: UiState, rules: RulesConfig, consent: BridgeConsent, appVersion: String,
        /** The profile rings and the lens suggestion (Phase E); null = not sent. */
        view: com.drishtisaathi.profile.ProfileView? = null,
    ): JsonObject {
        require(consent.complete) { "spoken and tapped consent are both required" }
        val r = requireNotNull(state.result) { "no result yet" }
        val a = r.assessment
        val adv = a.advice
        val answers = state.answers
        val gate = com.drishtisaathi.engine.profile.Safety.gate(
            answers, state.survey, rules.safety, state.quick, state.tests.takeIf { state.testsDone }, state.screening)
        return buildJsonObject {
            // Phase A: how the optometrist's list marks and sorts this report; the red-flag reasons come first.
            put("triage", buildJsonObject {
                put("tag", gate.tag.name)
                put("redFlags", kotlinx.serialization.json.JsonArray(gate.redFlags.map { JsonPrimitive(it.name) }))
                put("redFlagReasons", kotlinx.serialization.json.JsonArray(gate.redFlags.map { JsonPrimitive(com.drishtisaathi.i18n.SProfile.redFlag(it).en) }))
                put("emergency", gate.emergency)
                put("childMyopia", gate.childMyopia)
                put("lensSuggested", gate.lensAllowed)
                if (gate.powerForOptometristOnly) put("powerLabel", com.drishtisaathi.i18n.SProfile.forOptometristOnly.en)
            })
            put("consent", buildJsonObject {
                put("consentAt", consent.consentAt); put("spoken", consent.spoken); put("tapped", consent.tapped)
                put("textVersion", CONSENT_TEXT_VERSION)
            })
            put("app", buildJsonObject { put("version", appVersion); put("lang", state.lang.name) })
            put("demo", state.demo)
            put("answers", enc(com.drishtisaathi.engine.InterviewAnswers.serializer(), answers))
            put("functionalProfile", enc(FunctionalProfile.serializer(), FunctionalProfile.from(answers, rules)))
            put("powerSource", adv?.powerSource?.name?.let(::JsonPrimitive) ?: JsonNull)
            put("power", buildJsonObject {
                put("eyes", enc(ListSerializer(com.drishtisaathi.engine.EyeAdvice.serializer()), adv?.eyes))
                put("addD", enc(com.drishtisaathi.engine.Estimate.serializer(com.drishtisaathi.engine.PowerBand.serializer()), adv?.add))
                put("knownPower", enc(com.drishtisaathi.engine.KnownRx.serializer(),
                    answers.knownRx?.takeIf { answers.powerKnown == PowerKnown.YES }))
                put("phoneEstimate", enc(com.drishtisaathi.engine.QuickEstimate.serializer(), state.quick))
                // The phone check as ranges (never one exact number), readable on the optometrist page.
                put("phoneRange", buildJsonObject {
                    state.quick?.right?.let { put("right", JsonPrimitive(com.drishtisaathi.vision.quickEyeText(it).en)) }
                    state.quick?.left?.let { put("left", JsonPrimitive(com.drishtisaathi.vision.quickEyeText(it).en)) }
                    state.quick?.nearPointCm?.let { cm ->
                        val q = state.quick!!
                        put("near", JsonPrimitive(com.drishtisaathi.vision.nearResultText(cm, q.addD, q.expectedNearPointCm, q.possibleLongSight).en))
                    }
                })
            })
            put("measurements", buildJsonObject {
                put("pdMm", enc(com.drishtisaathi.engine.Estimate.serializer(kotlinx.serialization.serializer<Double>()), a.pdMm))
                put("faceWidthMm", enc(com.drishtisaathi.engine.Estimate.serializer(kotlinx.serialization.serializer<Double>()), a.faceWidthMm))
                put("face", enc(com.drishtisaathi.engine.MeasuredFace.serializer(), state.face))
                put("screenTests", enc(com.drishtisaathi.engine.VisionTests.serializer(), state.tests.takeIf { state.testsDone }))
                put("glassesOn", state.glassesOn)
            })
            // Screening tests: the results in words (always under the label) and the raw results with the tapped grid squares.
            state.screening?.let { sc ->
                put("screening", buildJsonObject {
                    put("label", com.drishtisaathi.i18n.SProfile.screeningLabel.en)
                    put("lines", kotlinx.serialization.json.JsonArray(com.drishtisaathi.profile.ScreeningText.lines(state, rules).map {
                        buildJsonObject { put("text", it.text.en); put("status", it.status.name) }
                    }))
                    put("results", enc(com.drishtisaathi.engine.profile.ScreeningResults.serializer(), sc))
                })
            }
            if (view != null) {
                put("visionProfile", buildJsonObject {
                    view.profile.rings.forEach { (id, r) ->
                        put(id.lowercase(), buildJsonObject {
                            put("score", r.score); put("label", com.drishtisaathi.i18n.SProfile.ringLabel(r.label).en); put("answered", r.known)
                        })
                    }
                    put("overall", view.profile.overall)
                    put("nightGlareSensitive", view.profile.nightGlareSensitive)
                })
                put("lens", buildJsonObject {
                    put("label", com.drishtisaathi.i18n.SProfile.lensLabel.en)
                    put("suggested", view.plan.allowed)
                    put("optometristConfirms", view.plan.optometristConfirms)
                    put("ids", kotlinx.serialization.json.JsonArray(view.plan.ids.map(::JsonPrimitive)))
                    put("items", kotlinx.serialization.json.JsonArray(view.plan.all.map { s ->
                        buildJsonObject {
                            put("kind", s.kind.name); put("id", s.id); put("product", s.name)
                            put("why", kotlinx.serialization.json.JsonArray(com.drishtisaathi.profile.LensText.why(s).map { JsonPrimitive(it.en) }))
                            s.url?.let { put("url", it) }
                        }
                    }))
                    view.plan.referralInfo?.let { put("referralInfo", it.name) }
                })
                if (state.survey.values.isNotEmpty()) put("survey", enc(com.drishtisaathi.engine.profile.SurveyAnswers.serializer(), state.survey))
            }
            put("estimates", buildJsonObject {
                put("referralLevel", a.referral.level.name)
                put("referralReasons", enc(ListSerializer(com.drishtisaathi.engine.ReferralReason.serializer()), a.referral.reasons))
                put("adviceWithheld", a.referral.adviceWithheld)
                put("lensType", adv?.lensType?.value?.name?.let(::JsonPrimitive) ?: JsonNull)
                put("material", adv?.materialId?.value?.let(::JsonPrimitive) ?: JsonNull)
                put("coatings", enc(ListSerializer(com.drishtisaathi.engine.Coating.serializer()), adv?.coatings))
                put("notes", enc(ListSerializer(com.drishtisaathi.engine.Note.serializer()), a.notes))
                // a red flag or child myopia: nothing is suggested or sold, so no frames are listed either
                put("suggestedFrames", kotlinx.serialization.json.JsonArray((if (gate.lensAllowed) r.frames.take(5) else emptyList()).map {
                    JsonPrimitive("${it.frame.id} · ₹${it.frame.priceInr}")
                }))
                if (!gate.lensAllowed) put("suggestionsNote", com.drishtisaathi.i18n.SProfile.noLensToday.en)
            })
        }
    }

    /** Paths of keys the server would reject (empty for a valid profile). */
    fun forbiddenPaths(e: JsonElement, path: String = ""): List<String> = when (e) {
        is JsonObject -> e.flatMap { (k, v) ->
            val p = if (path.isEmpty()) k else "$path.$k"
            val n = k.lowercase().replace("_", "").replace("-", "")
            (if (n in forbiddenKeys) listOf(p) else emptyList()) + forbiddenPaths(v, p)
        }
        is kotlinx.serialization.json.JsonArray -> e.flatMapIndexed { i, v -> forbiddenPaths(v, "$path[$i]") }
        else -> emptyList()
    }
}

@Serializable data class BridgeCreated(val id: String, val status: String, val receivedAt: Long)
@Serializable data class BridgeReview(
    val status: String,
    val advice: String? = null,
    val needsInPersonExam: Boolean? = null,
    val reviewedAt: Long? = null,
) {
    val reviewed: Boolean get() = status == "REVIEWED"
}
@Serializable data class BridgeHealth(val ok: Boolean, val tokenRequired: Boolean = false, val tokenOk: Boolean = true)

class BridgeException(val kind: Kind, val detail: String = "") : IOException("$kind $detail") {
    enum class Kind { NOT_CONFIGURED, NETWORK, UNAUTHORIZED, REJECTED, NOT_FOUND, SERVER }
}

/** Blocking HTTP client (call it off the main thread). Plain HttpURLConnection: no extra dependency. */
class BridgeClient(private val config: BridgeConfig, private val timeoutMs: Int = 15_000) {
    private val json = Json { ignoreUnknownKeys = true }

    fun health(): BridgeHealth = json.decodeFromString(BridgeHealth.serializer(), request("GET", "/health"))
    fun send(profile: JsonObject): BridgeCreated =
        json.decodeFromString(BridgeCreated.serializer(), request("POST", "/profiles", profile.toString()))
    fun review(remoteId: String): BridgeReview =
        json.decodeFromString(BridgeReview.serializer(), request("GET", "/profiles/${enc(remoteId)}/review"))
    fun delete(remoteId: String) { request("DELETE", "/profiles/${enc(remoteId)}") }

    private fun enc(id: String) = java.net.URLEncoder.encode(id, "UTF-8")

    private fun request(method: String, path: String, body: String? = null): String {
        if (!config.isValid) throw BridgeException(BridgeException.Kind.NOT_CONFIGURED)
        val conn = try {
            (URL(config.baseUrl + path).openConnection() as HttpURLConnection)
        } catch (e: Exception) {
            throw BridgeException(BridgeException.Kind.NOT_CONFIGURED, e.message.orEmpty())
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            if (config.token.isNotBlank()) conn.setRequestProperty("X-Bridge-Token", config.token)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return when (code) {
                in 200..299 -> text
                401 -> throw BridgeException(BridgeException.Kind.UNAUTHORIZED)
                404 -> throw BridgeException(BridgeException.Kind.NOT_FOUND)
                413, 422 -> throw BridgeException(BridgeException.Kind.REJECTED, "$code")
                else -> throw BridgeException(BridgeException.Kind.SERVER, "$code")
            }
        } catch (e: BridgeException) {
            throw e
        } catch (e: IOException) {
            throw BridgeException(BridgeException.Kind.NETWORK, e.message.orEmpty())
        } finally {
            conn.disconnect()
        }
    }
}
