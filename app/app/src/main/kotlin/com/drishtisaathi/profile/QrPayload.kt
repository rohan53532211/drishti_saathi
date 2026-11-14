package com.drishtisaathi.profile

import com.drishtisaathi.UiState
import com.drishtisaathi.engine.Eye
import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.engine.profile.ColourOutcome
import com.drishtisaathi.engine.profile.PowerMethod
import com.drishtisaathi.i18n.SProfile
import com.drishtisaathi.i18n.Txt
import com.drishtisaathi.vision.PowerRange
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the result QR holds (Phase G). When the report is on the optometrist's server and the server is reachable,
 * the QR is the link to it (`<server>/profiles/<id>`); otherwise it is a compact JSON summary that needs no network:
 * case id, age band, tag, the four rings, each eye's power range with its method and the lens used, PD, the
 * screening results, the suggested lens ids and the app version. Never a name, a photo or a phone number.
 */
object QrPayload {
    fun ageBand(age: Int?): String = when {
        age == null -> "?"
        age < 18 -> "<18"
        age < 30 -> "18-29"
        age < 45 -> "30-44"
        age < 60 -> "45-59"
        else -> "60+"
    }

    /** The server's short case number once sent, else a local one from the session id. */
    fun caseId(state: UiState): String = state.remote?.caseId ?: "S${state.sessionId ?: 0}"

    fun json(state: UiState, view: ProfileView, rules: RulesConfig, appVersion: String): JsonObject = buildJsonObject {
        put("caseId", caseId(state))
        put("ageBand", ageBand(state.answers.age))
        put("tag", view.gate.tag.name)
        put("rings", buildJsonObject {
            view.profile.rings.forEach { (id, r) ->
                put(id.lowercase(), JsonArray(listOf(JsonPrimitive(if (r.known) SProfile.ringLabel(r.label).en else "?"), JsonPrimitive(r.score))))
            }
        })
        put("eyes", JsonArray(state.quick?.eyes.orEmpty().map { (eye, e) ->
            buildJsonObject {
                put("eye", if (eye == Eye.RIGHT) "R" else "L")
                val se = e.seD
                if (se == null || e.noMyopiaAtReach) put("clearAtReach", true) else {
                    val r = PowerRange.around(se)
                    put("from", e.rangeFromD ?: r.fromD); put("to", e.rangeToD ?: r.toD)
                }
                put("method", (e.method ?: PowerMethod.UNAIDED).name)
                e.lensD?.let { put("lens", it) }
            }
        }))
        state.result?.assessment?.pdMm?.let { put("pd", Math.round(it.value * 10) / 10.0) }
        state.screening?.let { s ->
            put("screening", buildJsonObject {
                if (s.amsler.isNotEmpty()) put("grid", JsonArray(s.amsler.map { JsonPrimitive((if (it.eye == Eye.RIGHT) "R:" else "L:") + (if (it.distortion) "DISTORTION" else "OK")) }))
                if (s.contrast.isNotEmpty()) put("contrast", JsonArray(s.contrast.map {
                    JsonPrimitive((if (it.eye == Eye.RIGHT) "R:" else "L:") + (it.lowestPassedPercent?.toString() ?: "none") + (if (it.reduced) ":REDUCED" else ":NORMAL"))
                }))
                s.colour?.let { put("colour", it.outcome.name) }
                if (s.eTest.any { it.belowLimit }) put("eTest", "BELOW_LIMIT")
                if (s.clockDial.any { it.astigmatismFlag }) put("dial", "ASTIGMATISM_FLAG")
            })
        }
        put("lens", JsonArray(view.plan.ids.map(::JsonPrimitive)))
        put("appVersion", appVersion)
    }

    /**
     * The QR's text: the bridge link when the report was sent and the backend is [online]; else the compact JSON.
     */
    fun content(state: UiState, view: ProfileView, rules: RulesConfig, appVersion: String, online: Boolean): String {
        val r = state.remote
        return if (online && r != null) "${r.backendUrl.trimEnd('/')}/profiles/${r.remoteId}" else json(state, view, rules, appVersion).toString()
    }

    /** The lines of the summary picture shared on WhatsApp: tag, rings, power ranges with the method, screening, lens. */
    fun shareLines(state: UiState, view: ProfileView, rules: RulesConfig): List<Txt> = buildList {
        add(SProfile.reportTagLine.format(SProfile.tag(view.gate.tag)[state.lang]))
        if (view.gate.urgent) { add(SProfile.seeDoctorToday); view.gate.redFlags.forEach { add(SProfile.redFlag(it)) } }
        if (view.gate.childMyopia) add(SProfile.childMyopiaTitle)
        addAll(LensText.ringLines(view.profile))
        addAll(com.drishtisaathi.ui.PowerPathText.quickLines(state))
        ScreeningText.lines(state, rules).filter { it.status != ScreenLine.Status.NOT_DONE }.forEach { add(it.text) }
        if (view.plan.allowed) {
            add(SProfile.lensLabel)
            view.plan.all.forEach { add(Txt(it.name, it.name)) }
        }
        add(Txt(SProfile.screeningLabel.en + " · " + com.drishtisaathi.i18n.S.disclaimer.en, SProfile.screeningLabel.hi + " · " + com.drishtisaathi.i18n.S.disclaimer.hi))
    }

    @Suppress("unused") private val colourNames = ColourOutcome.entries
}
