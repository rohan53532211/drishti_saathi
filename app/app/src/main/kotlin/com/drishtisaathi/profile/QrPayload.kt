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

    /**
     * The report header: the date of the check, the age band (never the exact age) and the language of the session.
     */
    fun headerLine(state: UiState): Txt {
        val date = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US).format(java.util.Date(state.createdAt ?: System.currentTimeMillis()))
        val band = ageBand(state.answers.age)
        val lang = if (state.lang == com.drishtisaathi.i18n.Lang.HI) SProfile.langHindi else SProfile.langEnglish
        fun age(t: Txt) = if (band == "?") t else Txt(band, band)
        return Txt(
            SProfile.reportHeader.en.format(date, age(SProfile.ageNotGiven).en, lang.en),
            SProfile.reportHeader.hi.format(date, age(SProfile.ageNotGiven).hi, lang.hi),
        )
    }

    /** The server's short case number once sent, else a local one from the session id. */
    fun caseId(state: UiState): String = state.remote?.caseId ?: "S${state.sessionId ?: 0}"

    fun json(state: UiState, view: ProfileView, rules: RulesConfig, appVersion: String): JsonObject = buildJsonObject {
        put("caseId", caseId(state))
        put("ageBand", ageBand(state.answers.age))
        put("tag", view.gate.tag.name)
        put("lang", state.lang.name)
        if (view.gate.emergency) put("path", "SHORT_EMERGENCY")
        put("rings", buildJsonObject {
            view.profile.rings.forEach { (id, r) ->
                put(id.lowercase(), JsonArray(listOf(JsonPrimitive(if (r.known) SProfile.ringLabel(r.label).en else "?"), JsonPrimitive(r.score))))
            }
        })
        // both eyes, always: an eye without a reading carries the reason instead of being left out
        put("eyes", JsonArray((state.quick ?: com.drishtisaathi.engine.QuickEstimate()).bothEyes.map { (eye, e, why) ->
            buildJsonObject {
                put("eye", if (eye == Eye.RIGHT) "R" else "L")
                if (e == null) { put("notMeasured", (why ?: com.drishtisaathi.engine.NotMeasured.NOT_DONE).name); return@buildJsonObject }
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
                    JsonPrimitive((if (it.eye == Eye.RIGHT) "R:" else "L:") + (it.lowestPassedPercent?.toString() ?: "none") + (if (it.invalid) ":INVALID" else if (it.reduced) ":REDUCED" else ":NORMAL"))
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
        val gate = view.gate
        add(headerLine(state))
        if (gate.emergency) add(SProfile.shortPathHeader)
        add(SProfile.reportTagLine.format(SProfile.tag(gate.tag)[state.lang]))
        if (gate.urgent) { add(SProfile.seeDoctorToday); addAll(SProfile.redFlagLines(gate)) }
        if (gate.childMyopia) add(SProfile.childMyopiaTitle)
        addAll(LensText.ringLines(view.profile, gate.emergency))
        // URGENT and CHILD MYOPIA: "For the optometrist only: R …", never "Your approximate power"
        addAll(com.drishtisaathi.ui.PowerPathText.quickLines(state, gate.powerForOptometristOnly))
        // the screening results keep their own label, directly above them
        val screening = ScreeningText.lines(state, rules).filter { it.status != ScreenLine.Status.NOT_DONE }
        if (screening.isNotEmpty()) { add(SProfile.screeningLabel); screening.forEach { add(it.text) } }
        if (view.plan.allowed) {
            add(SProfile.lensLabel)
            view.plan.all.forEach { add(Txt(it.name, it.name)) }
        }
        // the disclaimer, once
        add(com.drishtisaathi.i18n.S.disclaimer)
    }

    @Suppress("unused") private val colourNames = ColourOutcome.entries
}
