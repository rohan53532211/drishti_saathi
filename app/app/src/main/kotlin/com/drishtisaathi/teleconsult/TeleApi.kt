package com.drishtisaathi.teleconsult

import android.content.Context
import com.drishtisaathi.bridge.BridgeSettings
import com.drishtisaathi.i18n.STele
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.i18n.fmtD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

// Teleconsult, patient side: the account, asking for a consultation, chat, video link and the optometrist's advice.
// The server is the same backend as the optometrist bridge (its address comes from the bridge settings); these
// endpoints use the patient's own login (a JWT), not the shared bridge token.

@Serializable
data class TeleUser(
    val id: String, val role: String = "patient", val name: String = "", val phone: String? = null, val age: Int? = null,
    val gender: String? = null, val language: String? = null, val consentAt: Long? = null,
)

@Serializable data class TeleAuth(val token: String, val user: TeleUser)

@Serializable
data class TeleOptometrist(
    val id: String, val name: String = "", val qualification: String? = null, val registrationNo: String? = null,
    val clinicName: String? = null, val city: String? = null, val languages: List<String> = emptyList(), val fee: Int? = null,
    val available: Boolean = false, val allowWhatsapp: Boolean = false, val demo: Boolean = false,
)

@Serializable
data class TeleConsultation(
    val id: String, val caseId: String = "", val status: String = "REQUESTED", val priority: String = "ROUTINE",
    val createdAt: Long = 0, val updatedAt: Long = 0, val fee: Int? = null, val paymentStatus: String = "",
    val videoUrl: String? = null, val requestedOptometristId: String? = null, val optometrist: TeleOptometrist? = null,
    val hasPrescription: Boolean = false, val messageCount: Int = 0, val lastMessageId: Long = 0,
    val redFlagReasons: List<String> = emptyList(), val urgentNote: String? = null, val whatsappUrl: String? = null,
) {
    val urgent: Boolean get() = priority == "URGENT"
    val closed: Boolean get() = status == "CLOSED"
    /** Chat opens once an optometrist has the case and stops when it is closed. */
    val canChat: Boolean get() = status == "ACCEPTED" || status == "IN_PROGRESS" || status == "COMPLETED"
}

@Serializable
data class TeleMessage(
    val id: Long, val senderRole: String = "system", val kind: String = "TEXT", val text: String? = null,
    val hasImage: Boolean = false, val createdAt: Long = 0,
)

@Serializable data class TeleEye(val sph: Double? = null, val cyl: Double? = null, val axis: Int? = null)

/** The optometrist's advice for one consultation, as written in the portal. */
@Serializable
data class TeleAdvice(
    val right: TeleEye = TeleEye(), val left: TeleEye = TeleEye(), val add: Double? = null, val pdMm: Double? = null,
    val lensAdvice: List<String> = emptyList(), val referralAdvice: String? = null, val needsInPersonExam: Boolean = false,
    val followUpDate: String? = null, val notes: String? = null,
    val optometristName: String = "", val registrationNo: String = "", val qualification: String? = null, val clinic: String? = null,
    val createdAt: Long = 0,
)

class TeleException(val kind: Kind, val detail: String = "") : IOException("$kind $detail") {
    enum class Kind { NOT_CONFIGURED, NETWORK, UNAUTHORIZED, FORBIDDEN, CONFLICT, NOT_FOUND, INVALID, SERVER }
}

/** Wording and small rules of the patient side (pure, unit-tested). */
object TeleText {
    /** Digits of a typed mobile number without a leading +91 / 0; null when it is not a 10-digit number. */
    fun phoneDigits(typed: String): String? {
        var d = typed.filter(Char::isDigit)
        if (d.length == 12 && d.startsWith("91")) d = d.drop(2)
        if (d.length == 11 && d.startsWith("0")) d = d.drop(1)
        return d.takeIf { it.length == 10 }
    }

    /** What is wrong with a registration form; null = ready to send. The consent box is required. */
    fun registrationProblem(name: String, phone: String, password: String, age: String, consent: Boolean): Txt? = when {
        name.isBlank() -> STele.errName
        phoneDigits(phone) == null -> STele.errPhone
        password.length < 8 -> STele.errPassword
        (age.trim().toIntOrNull() ?: 0) !in 1..120 -> STele.errAge
        !consent -> STele.consentNeeded
        else -> null
    }

    fun loginProblem(phone: String, password: String): Txt? = when {
        phoneDigits(phone) == null -> STele.errPhone
        password.isEmpty() -> STele.errPassword
        else -> null
    }

    fun error(e: Throwable, login: Boolean = false): Txt = when ((e as? TeleException)?.kind) {
        TeleException.Kind.NOT_CONFIGURED -> STele.errNotConfigured
        TeleException.Kind.NETWORK -> STele.errNetwork
        TeleException.Kind.UNAUTHORIZED -> if (login) STele.errLogin else STele.errSession
        TeleException.Kind.CONFLICT -> if (login) STele.errTaken else STele.errClosed
        TeleException.Kind.NOT_FOUND -> STele.errGone
        null -> STele.errServer.format(e.message ?: "?")
        else -> STele.errServer.format((e as TeleException).detail.ifBlank { e.kind.name })
    }

    private fun eye(e: TeleEye): String? {
        if (e.sph == null && e.cyl == null) return null
        val sph = fmtD(e.sph ?: 0.0)
        return if (e.cyl == null || e.cyl == 0.0) "$sph DS" else "$sph / ${fmtD(e.cyl)} × ${e.axis ?: "?"}"
    }

    /** The advice in lines for the consultation screen: each eye, add, PD, lens advice, referral, follow-up, notes. */
    fun adviceLines(a: TeleAdvice): List<Txt> = buildList {
        eye(a.right)?.let { add(Txt("${STele.eyeRight.en}: $it", "${STele.eyeRight.hi}: $it")) }
        eye(a.left)?.let { add(Txt("${STele.eyeLeft.en}: $it", "${STele.eyeLeft.hi}: $it")) }
        a.add?.let { add(STele.nearAdd.format(fmtD(it))) }
        a.pdMm?.let { add(STele.pd.format(String.format(Locale.US, "%.1f", it))) }
        if (a.lensAdvice.isNotEmpty()) add(STele.lensAdvice.format(a.lensAdvice.joinToString(", ")))
        if (a.needsInPersonExam) add(STele.inPerson)
        a.referralAdvice?.takeIf { it.isNotBlank() }?.let { add(STele.referral.format(it)) }
        a.followUpDate?.takeIf { it.isNotBlank() }?.let { add(STele.followUp.format(it)) }
        a.notes?.takeIf { it.isNotBlank() }?.let { add(STele.notes.format(it)) }
        if (a.optometristName.isNotBlank()) add(STele.signedBy.format(a.optometristName, a.registrationNo))
    }

    /** One line under an optometrist's name: qualification, clinic and city, languages. */
    fun optometristLine(o: TeleOptometrist): String =
        listOfNotNull(o.qualification, listOfNotNull(o.clinicName, o.city).joinToString(", ").ifBlank { null },
            o.languages.joinToString(", ").ifBlank { null }).joinToString(" · ")

    /** Only links the server made for a call or WhatsApp are opened: https to Jitsi Meet or wa.me. */
    fun safeLink(url: String?): String? = url?.takeIf { it.startsWith("https://meet.jit.si/") || it.startsWith("https://wa.me/") }
}

/** Blocking HTTP client for the teleconsult API (call it off the main thread). */
class TeleClient(private val baseUrl: String, private val token: String? = null, private val timeoutMs: Int = 15_000) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun registerPatient(name: String, phone: String, password: String, age: Int, gender: String?, language: String, consent: Boolean): TeleAuth =
        json.decodeFromString(TeleAuth.serializer(), text("POST", "/api/auth/register/patient", buildJsonObject {
            put("name", name.trim()); put("phone", phone); put("password", password); put("age", age)
            gender?.let { put("gender", it) }
            put("language", language); put("consent", consent)
        }.toString()))

    fun login(phone: String, password: String): TeleAuth =
        json.decodeFromString(TeleAuth.serializer(), text("POST", "/api/auth/login", buildJsonObject {
            put("role", "patient"); put("identifier", phone); put("password", password)
        }.toString()))

    fun me(): TeleUser = json.decodeFromString(TeleUser.serializer(), text("GET", "/api/me"))
    fun deleteAccount() { text("DELETE", "/api/me") }
    fun optometrists(): List<TeleOptometrist> = json.decodeFromString(ListSerializer(TeleOptometrist.serializer()), text("GET", "/api/optometrists"))

    /** [report]: the session's optometrist report (numbers and answers only). [optometristId] null = any available. */
    fun requestConsultation(report: JsonObject, optometristId: String?): TeleConsultation =
        json.decodeFromString(TeleConsultation.serializer(), text("POST", "/api/consultations", buildJsonObject {
            put("report", report); put("optometristId", optometristId?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()))

    fun consultations(): List<TeleConsultation> = json.decodeFromString(ListSerializer(TeleConsultation.serializer()), text("GET", "/api/consultations"))
    fun consultation(id: String): TeleConsultation = json.decodeFromString(TeleConsultation.serializer(), text("GET", "/api/consultations/${enc(id)}"))
    fun close(id: String): TeleConsultation = json.decodeFromString(TeleConsultation.serializer(), text("POST", "/api/consultations/${enc(id)}/close"))
    fun messages(id: String, after: Long): List<TeleMessage> =
        json.decodeFromString(ListSerializer(TeleMessage.serializer()), text("GET", "/api/consultations/${enc(id)}/messages?after=$after"))
    fun sendMessage(id: String, message: String): TeleMessage =
        json.decodeFromString(TeleMessage.serializer(), text("POST", "/api/consultations/${enc(id)}/messages", buildJsonObject { put("text", message) }.toString()))
    fun image(id: String, messageId: Long): ByteArray = bytes("GET", "/api/consultations/${enc(id)}/messages/$messageId/image")
    fun advice(id: String): TeleAdvice = json.decodeFromString(TeleAdvice.serializer(), text("GET", "/api/consultations/${enc(id)}/prescription"))
    fun advicePdf(id: String): ByteArray = bytes("GET", "/api/consultations/${enc(id)}/prescription.pdf")

    private fun enc(id: String) = java.net.URLEncoder.encode(id, "UTF-8")
    private fun text(method: String, path: String, body: String? = null): String = String(bytes(method, path, body), Charsets.UTF_8)

    private fun bytes(method: String, path: String, body: String? = null): ByteArray {
        if (!(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) throw TeleException(TeleException.Kind.NOT_CONFIGURED)
        val conn = try {
            URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw TeleException(TeleException.Kind.NOT_CONFIGURED, e.message.orEmpty())
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val data = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            if (code in 200..299) return data
            val detail = detailOf(String(data, Charsets.UTF_8)).ifBlank { code.toString() }
            throw TeleException(when (code) {
                401 -> TeleException.Kind.UNAUTHORIZED
                403 -> TeleException.Kind.FORBIDDEN
                404 -> TeleException.Kind.NOT_FOUND
                409 -> TeleException.Kind.CONFLICT
                413, 422 -> TeleException.Kind.INVALID
                else -> TeleException.Kind.SERVER
            }, detail)
        } catch (e: TeleException) {
            throw e
        } catch (e: IOException) {
            throw TeleException(TeleException.Kind.NETWORK, e.message.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** The server's own message from an error body ({"detail": "…"}), or "" when there is none. */
        fun detailOf(body: String): String = runCatching {
            val d = Json.parseToJsonElement(body).jsonObject["detail"]
            if (d is JsonPrimitive) d.jsonPrimitive.content else d?.toString().orEmpty().take(200)
        }.getOrDefault("")
    }
}

/** The patient's login on this phone: the token and the account as last seen. Backups are off for the whole app. */
class TeleStore(context: Context) {
    private val prefs = context.getSharedPreferences("teleconsult", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _user = MutableStateFlow(prefs.getString("user", null)?.let { runCatching { json.decodeFromString(TeleUser.serializer(), it) }.getOrNull() })
    val user: StateFlow<TeleUser?> = _user.asStateFlow()
    val token: String? get() = prefs.getString("token", null)

    fun save(auth: TeleAuth) {
        prefs.edit().putString("token", auth.token).putString("user", json.encodeToString(TeleUser.serializer(), auth.user)).apply()
        _user.value = auth.user
    }

    fun clear() {
        prefs.edit().clear().apply()
        _user.value = null
    }
}

/** Runs teleconsult calls off the main thread with the saved login; a dead login logs the patient out. */
class TeleController(context: Context, private val bridge: BridgeSettings) {
    val store = TeleStore(context)
    val user: StateFlow<TeleUser?> get() = store.user

    suspend fun <T> call(block: (TeleClient) -> T): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val cfg = bridge.config.value
            if (!cfg.isValid) throw TeleException(TeleException.Kind.NOT_CONFIGURED)
            block(TeleClient(cfg.baseUrl, store.token))
        }.onFailure { if ((it as? TeleException)?.kind == TeleException.Kind.UNAUTHORIZED && store.token != null) store.clear() }
    }

    suspend fun login(phone: String, password: String): Result<TeleUser> = signIn { it.login(phone, password) }

    suspend fun register(name: String, phone: String, password: String, age: Int, gender: String?, language: String): Result<TeleUser> =
        signIn { it.registerPatient(name, phone, password, age, gender, language, consent = true) }

    private suspend fun signIn(block: (TeleClient) -> TeleAuth): Result<TeleUser> = withContext(Dispatchers.IO) {
        runCatching {
            val cfg = bridge.config.value
            if (!cfg.isValid) throw TeleException(TeleException.Kind.NOT_CONFIGURED)
            val auth = block(TeleClient(cfg.baseUrl))
            store.save(auth)
            auth.user
        }
    }

    fun logout() = store.clear()

    suspend fun deleteAccount(): Result<Unit> = call { it.deleteAccount() }.onSuccess { store.clear() }
}
