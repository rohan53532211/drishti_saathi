package com.drishtisaathi.engine.profile

import com.drishtisaathi.engine.Budget
import com.drishtisaathi.engine.VisionProblem
import com.drishtisaathi.engine.config.LensEngineConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

// ---- lens_catalog.json -----------------------------------------------------------------------------

@Serializable
data class CatalogLensType(
    val id: String, val role: String? = null, val name: String,
    @SerialName("how_it_works") val howItWorks: String = "",
    val benefits: List<String> = emptyList(),
    @SerialName("designs_available") val designsAvailable: List<String> = emptyList(),
    val trigger: String,
    @SerialName("why_en") val whyEn: String, @SerialName("why_hi") val whyHi: String,
    val url: String,
)

@Serializable
data class CatalogDesign(val id: String, @SerialName("applies_to") val appliesTo: List<String>, val tier: String, val what: String, val trigger: String)

@Serializable
data class CatalogMaterial(
    val id: String, val name: String, val default: Boolean = false, val what: String, val trigger: String? = null,
    @SerialName("why_en") val whyEn: String, @SerialName("why_hi") val whyHi: String, val url: String,
)

@Serializable data class CatalogCoating(val id: String, val name: String, val what: String, val trigger: String)
@Serializable data class CatalogSun(val id: String, val what: String, val trigger: String)

/** The lens catalogue asset: products, their texts, "why" templates and links. The trigger texts are documentation; their numbers are in rules.json → lensEngine. */
@Serializable
data class LensCatalog(
    val version: String,
    val source: String,
    @SerialName("selection_order") val selectionOrder: List<String>,
    @SerialName("lens_types") val lensTypes: List<CatalogLensType>,
    val designs: List<CatalogDesign>,
    val materials: List<CatalogMaterial>,
    val coatings: List<CatalogCoating>,
    @SerialName("second_pair_sun") val secondPairSun: List<CatalogSun>,
    val urls: Map<String, String>,
) {
    /** Every product id with a trigger rule. */
    val ids: Set<String> get() = (lensTypes.map { it.id } + designs.map { it.id } + materials.map { it.id } + coatings.map { it.id } + secondPairSun.map { it.id }).toSet()

    companion object {
        const val ASSET = "lens_catalog.json"
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): LensCatalog = json.decodeFromString(serializer(), text)
    }
}

// ---- output ----------------------------------------------------------------------------------------

@Serializable enum class SuggestionKind { LENS_TYPE, DESIGN, MATERIAL, COATING, SECOND_PAIR }

/** Why a product was picked, for products without their own "why" template: a code the app words, with its numbers. */
@Serializable data class Reason(val code: String, val args: List<String> = emptyList())

/**
 * One suggested product: its name and what it does (from the catalogue), the "why for you" lines filled from this
 * customer's answers and measurements ([why] from the catalogue's templates, [reasons] as codes), and the link.
 */
@Serializable
data class Suggestion(
    val kind: SuggestionKind, val id: String, val name: String, val what: String,
    val why: List<L10n> = emptyList(), val reasons: List<Reason> = emptyList(), val url: String? = null,
)

/** The lens suggestion for one customer. Always shown as "Suggested ZEISS lens family – optician confirms". */
@Serializable
data class LensPlan(
    /** False after a red flag or for child myopia: nothing is suggested, the referral comes instead. */
    val allowed: Boolean,
    val tag: ReportTag = ReportTag.ROUTINE,
    /** Child without myopia: every card is labelled "optometrist confirms". */
    val optometristConfirms: Boolean = false,
    val type: Suggestion? = null,
    val design: Suggestion? = null,
    val materials: List<Suggestion> = emptyList(),
    val coating: Suggestion? = null,
    val secondPairs: List<Suggestion> = emptyList(),
    /** Child myopia: the catalogue's myopia-management entry as information for the referral, never a lens choice. */
    val referralInfo: Suggestion? = null,
) {
    val all: List<Suggestion> get() = listOfNotNull(type, design) + materials + listOfNotNull(coating) + secondPairs
    val ids: List<String> get() = all.map { it.id }
}

/** Words the catalogue's "why" templates need: {distance} and {symptom}. */
data class LensWords(val far: L10n, val near: L10n, val tiredEyes: L10n, val headache: L10n, val neckPain: L10n, val strain: L10n)

/**
 * The lens engine (Phase E). Follows the catalogue's selection order: safety gate → lens type → design → materials
 * (UVProtect always) → coating → up to two second pairs. Every catalogue entry has one trigger in [triggers]; the
 * numbers come from rules.json → lensEngine.
 */
class LensEngine(private val catalog: LensCatalog, private val cfg: LensEngineConfig) {

    private fun Freq?.some() = this?.atLeast(cfg.symptomFrom) == true
    private fun budget(i: ProfileInputs): Budget = i.budget ?: cfg.defaultBudget
    private fun child(i: ProfileInputs) = i.age != null && i.age < cfg.childUnderAge
    private fun addNeeded(i: ProfileInputs) = (i.addD ?: 0.0) >= cfg.nearAddFromD
    private fun screenHeavy(i: ProfileInputs) = (i.screenHours ?: 0.0) >= cfg.screenHoursFrom
    private fun nearOrScreenTired(i: ProfileInputs) = screenHeavy(i) || i.tiredEyes.some() || i.nearBlur.some()
    private fun digitalLifestyle(i: ProfileInputs) = (i.screenHours ?: 0.0) >= cfg.digitalLifestyleFromHours
    private fun busyVisualWork(i: ProfileInputs) = i.workActivities.size >= 2 || (i.deskHours ?: 0.0) >= cfg.officeDeskHoursFrom
    private fun publicFacing(i: ProfileInputs) = "MEETINGS" in i.workActivities
    private fun drives(i: ProfileInputs) = i.drive in cfg.driveFrequencies
    private fun poorLowLight(i: ProfileInputs) = i.lowLightDriving.some() || i.lowLightTrouble.some() || i.contrastReduced
    private fun driveSafe(i: ProfileInputs) = drives(i) && (i.glare.some() || poorLowLight(i))
    private fun outdoor(i: ProfileInputs) = i.outdoorHours ?: 0.0
    private fun photoFusion(i: ProfileInputs) = outdoor(i) >= cfg.photoFusionOutdoorHoursFrom && i.onePair == true
    private fun blueGuard(i: ProfileInputs) = screenHeavy(i) || i.tiredEyes.some() || i.focusStruggle.some()

    /**
     * Every catalogue trigger for this customer: product id → fires. One entry per catalogue id (a unit test checks
     * that none is missing), each written from the catalogue's own trigger text.
     */
    fun triggers(i: ProfileInputs): Map<String, Boolean> {
        val age = i.age
        val b = budget(i)
        return mapOf(
            // ---- lens types ----
            // age < 30, OR age 30-44 without near/screen tiredness, AND no near add needed
            "single_vision" to ((age == null || age < cfg.singleVisionUnderAge || (age in cfg.antiFatigueFromAge..cfg.antiFatigueToAge && !nearOrScreenTired(i))) && !addNeeded(i)),
            // age 30-44 AND (screen hours >= 4 OR tired eyes/near blur >= sometimes) AND measured add < 0.75
            "anti_fatigue" to (age != null && age in cfg.antiFatigueFromAge..cfg.antiFatigueToAge && nearOrScreenTired(i) && !addNeeded(i)),
            // age >= 45 OR measured near add >= 0.75
            "progressive" to ((age != null && age >= cfg.progressiveFromAge) || addNeeded(i)),
            // desk/screen work hours >= 6 AND (age >= 40 OR neck pain/headache >= sometimes)
            "office" to ((i.deskHours ?: 0.0) >= cfg.officeDeskHoursFrom && ((age != null && age >= cfg.officeFromAge) || i.neckPain.some() || i.headache.some())),
            // age < 18 AND any minus power -> REFERRAL ONLY, never a lens choice
            "myopia_management" to (child(i) && i.myopic),
            // ---- designs ----
            "clearview" to (b == Budget.LOW),
            "light2" to (b == Budget.LOW || b == Budget.MID || i.firstProgressive == true),
            // budget = mid/high AND digital/mobile-heavy lifestyle (default premium)
            "smartlife" to (b != Budget.LOW && (digitalLifestyle(i) || i.budget == null || b == Budget.MID)),
            // budget = high AND (busy visual work OR focus complaints)
            "clearmind" to (b == Budget.HIGH && (busyVisualWork(i) || i.focusStruggle.some())),
            // drives daily/weekly AND (oncoming glare OR poor low-light vision >= sometimes)
            "drivesafe" to driveSafe(i),
            // age < 18 AND NOT myopic
            "smartlife_young" to (child(i) && !i.myopic),
            // ---- materials ----
            "uvprotect" to true,
            // screen hours >= 4 OR digital strain symptoms >= sometimes
            "blueguard" to blueGuard(i),
            // outdoor hours >= 2 AND wants one pair for indoor+outdoor (never because of night glare)
            "photofusion_x" to photoFusion(i),
            // ---- coatings ----
            "dv_gold" to (b == Budget.HIGH),
            "dv_platinum" to (b != Budget.LOW),
            "dv_antivirus_platinum_uv" to (b != Budget.LOW && publicFacing(i)),
            "dv_silver" to (b == Budget.MID),
            "dv_chrome" to (b == Budget.LOW),
            "dv_kids" to child(i),
            // DriveSafe design chosen (as the main lens)
            "dv_drivesafe" to (driveSafe(i) && driveSafeIsMain(i)),
            // never together with BlueGuard: only when a screen user gets neither BlueGuard nor PhotoFusion X
            "dv_blueprotect" to (screenHeavy(i) && !blueGuard(i) && !photoFusion(i)),
            // ---- prescription sun, second pair ----
            // drives in daytime OR water/outdoor sport OR field/farm work
            "sun_polarised" to ((i.drive != null && i.drive != DriveFrequency.NEVER) || "WATER" in i.leisure || "OUTDOOR_SPORT" in i.leisure ||
                "FARMING_FIELD" in i.leisure || "FIELD_FARM" in i.workActivities),
            // changing light, wants sunglasses that adapt
            "sun_adaptivesun" to (i.indoorOutdoor == "BOTH" && outdoor(i) >= cfg.photoFusionOutdoorHoursFrom && i.onePair != true),
            // style / budget
            "sun_tint" to (b == Budget.LOW && outdoor(i) >= cfg.photoFusionOutdoorHoursFrom),
            // very bright outdoor exposure
            "sun_mirror_flash" to (outdoor(i) >= cfg.veryBrightOutdoorHoursFrom),
            // water or snow sports
            "sun_skylet" to ("WATER" in i.leisure),
        )
    }

    private fun driveSafeIsMain(i: ProfileInputs) = i.drive in cfg.driveSafeMainFrequencies && budget(i) != Budget.LOW

    // ---- "why for you" ----

    private fun hours(v: Double?): String = v?.let { if (it == Math.floor(it)) "%.0f".format(Locale.US, it) else "%.1f".format(Locale.US, it) } ?: "?"

    private fun symptom(i: ProfileInputs, w: LensWords): L10n = when {
        i.neckPain.some() -> w.neckPain
        i.headache.some() -> w.headache
        i.tiredEyes.some() || i.focusStruggle.some() -> w.tiredEyes
        else -> w.strain
    }

    private fun fill(en: String, hi: String, i: ProfileInputs, w: LensWords): L10n {
        val distance = if (i.problem == VisionProblem.NEAR) w.near else w.far
        val sym = symptom(i, w)
        fun f(t: String, d: String, s: String) = t.replace("{distance}", d).replace("{age}", i.age?.toString() ?: "?")
            .replace("{screen_hours}", hours(i.screenHours)).replace("{add}", i.addD?.let { "%.2f".format(Locale.US, it) } ?: "?")
            .replace("{desk_hours}", hours(i.deskHours)).replace("{symptom}", s).replace("{outdoor_hours}", hours(i.outdoorHours))
        return L10n(f(en, distance.en, sym.en), f(hi, distance.hi, sym.hi))
    }

    private fun name(id: String): String = cfg.names[id] ?: error("lensEngine.names has no name for '$id'")

    private fun typeSuggestion(id: String, i: ProfileInputs, w: LensWords, kind: SuggestionKind = SuggestionKind.LENS_TYPE, extra: List<Reason> = emptyList()): Suggestion {
        val t = catalog.lensTypes.first { it.id == id }
        // a "why" line is only used when this customer's numbers fill it (no "?" left in it)
        val why = fill(t.whyEn, t.whyHi, i, w).takeIf { "?" !in it.en }
        val reasons = extra + if (why == null && id == "progressive" && i.age != null) listOf(Reason("AGE_NEAR_FOCUS", listOf(i.age.toString()))) else emptyList()
        return Suggestion(kind, t.id, t.name, t.howItWorks, listOfNotNull(why), reasons, t.url)
    }

    private fun design(id: String, reasons: List<Reason>, kind: SuggestionKind = SuggestionKind.DESIGN): Suggestion {
        val d = catalog.designs.first { it.id == id }
        return Suggestion(kind, d.id, name(d.id), d.what, reasons = reasons, url = catalog.urls[d.id])
    }

    private fun material(id: String, i: ProfileInputs, w: LensWords): Suggestion {
        val m = catalog.materials.first { it.id == id }
        return Suggestion(SuggestionKind.MATERIAL, m.id, m.name, m.what, listOf(fill(m.whyEn, m.whyHi, i, w)).filter { "?" !in it.en }, url = m.url)
    }

    private fun driveReasons(i: ProfileInputs) = listOfNotNull(
        Reason("DRIVES", listOf(i.drive?.name ?: "")),
        Reason("GLARE").takeIf { i.glare.some() },
        Reason("LOW_LIGHT").takeIf { poorLowLight(i) },
    )

    // ---- selection ----

    fun plan(i: ProfileInputs, gate: SafetyGate, w: LensWords): LensPlan {
        val t = triggers(i)
        fun on(id: String) = t.getValue(id)
        // 1. safety gate: a red flag, or a child with myopia → no lens suggestion, referral only
        if (!gate.lensAllowed) {
            val info = if (gate.childMyopia && !gate.urgent) typeSuggestion("myopia_management", i, w) else null
            return LensPlan(allowed = false, tag = gate.tag, referralInfo = info)
        }
        val b = budget(i)
        val kid = child(i)

        // 2. lens type
        val typeId = when {
            kid -> "single_vision"
            on("progressive") -> "progressive"
            on("anti_fatigue") -> "anti_fatigue"
            else -> "single_vision"
        }
        val type = catalog.lensTypes.first { it.id == typeId }

        // 3. design within the type
        fun fits(id: String): Boolean {
            val d = catalog.designs.first { it.id == id }
            return typeId in d.appliesTo && (type.designsAvailable.isEmpty() || id in type.designsAvailable)
        }
        val budgetReason = Reason("BUDGET", listOf(b.name))
        val pick: Pair<String, List<Reason>> = when {
            kid -> "smartlife_young" to listOf(Reason("CHILD"))
            on("drivesafe") && driveSafeIsMain(i) && fits("drivesafe") -> "drivesafe" to driveReasons(i)
            b == Budget.HIGH && on("clearmind") && fits("clearmind") ->
                "clearmind" to listOfNotNull(Reason("BUSY_WORK").takeIf { busyVisualWork(i) }, Reason("FOCUS").takeIf { i.focusStruggle.some() }, budgetReason)
            b == Budget.LOW && fits("clearview") -> "clearview" to listOf(budgetReason)
            fits("light2") && (b == Budget.LOW || (b == Budget.MID && i.firstProgressive == true)) ->
                "light2" to listOfNotNull(Reason("FIRST_PROGRESSIVE").takeIf { i.firstProgressive == true }, budgetReason)
            else -> "smartlife" to listOfNotNull(Reason("DIGITAL", listOf(hours(i.screenHours))).takeIf { digitalLifestyle(i) }, budgetReason.takeIf { b != Budget.LOW })
        }
        val designId = pick.first.takeIf(::fits) ?: catalog.designs.first { fits(it.id) && it.id != "smartlife_young" }.id
        val design = design(designId, pick.second)

        // 4. materials: UVProtect always; PhotoFusion X already has the blue filter inside, so BlueGuard is not added to it
        val materials = buildList {
            add(material("uvprotect", i, w))
            if (on("photofusion_x")) add(material("photofusion_x", i, w)) else if (on("blueguard")) add(material("blueguard", i, w))
        }

        // 5. coating
        val coatingId = when {
            kid -> "dv_kids"
            designId == "drivesafe" -> "dv_drivesafe"
            b == Budget.LOW -> "dv_chrome"
            b == Budget.HIGH -> "dv_gold"
            on("dv_antivirus_platinum_uv") -> "dv_antivirus_platinum_uv"
            digitalLifestyle(i) -> "dv_platinum"
            else -> "dv_silver"
        }
        val c = catalog.coatings.first { it.id == coatingId }
        val coatingReasons = when (coatingId) {
            "dv_kids" -> listOf(Reason("CHILD"))
            "dv_drivesafe" -> listOf(Reason("DRIVESAFE_DESIGN"))
            "dv_antivirus_platinum_uv" -> listOf(Reason("PUBLIC_FACING"))
            else -> listOf(budgetReason)
        }
        val coating = Suggestion(SuggestionKind.COATING, c.id, c.name, c.what, reasons = coatingReasons, url = catalog.urls["duravision"])

        // 6. up to two second pairs: office, DriveSafe (when it is not already the main lens), one sun lens
        val second = buildList {
            if (!kid && on("office")) add(typeSuggestion("office", i, w, SuggestionKind.SECOND_PAIR))
            if (!kid && on("drivesafe") && designId != "drivesafe") add(design("drivesafe", driveReasons(i), SuggestionKind.SECOND_PAIR))
            val photo = on("photofusion_x")
            val sun = listOf("sun_polarised", "sun_skylet", "sun_mirror_flash", "sun_adaptivesun", "sun_tint")
                .firstOrNull { on(it) && !(photo && it in setOf("sun_adaptivesun", "sun_tint")) }
            if (sun != null) {
                val s = catalog.secondPairSun.first { it.id == sun }
                val why = listOfNotNull(
                    Reason("DRIVES_DAY").takeIf { sun == "sun_polarised" && i.drive != null && i.drive != DriveFrequency.NEVER },
                    Reason("WATER").takeIf { "WATER" in i.leisure },
                    Reason("OUTDOOR_SPORT").takeIf { sun == "sun_polarised" && "OUTDOOR_SPORT" in i.leisure },
                    Reason("FIELD_WORK").takeIf { sun == "sun_polarised" && ("FARMING_FIELD" in i.leisure || "FIELD_FARM" in i.workActivities) },
                    Reason("OUTDOOR_HOURS", listOf(hours(i.outdoorHours))).takeIf { i.outdoorHours != null && sun != "sun_polarised" },
                )
                add(Suggestion(SuggestionKind.SECOND_PAIR, s.id, name(s.id), s.what, reasons = why, url = catalog.urls["sunglass"]))
            }
        }.take(cfg.maxSecondPairs)

        return validate(LensPlan(true, gate.tag, gate.optometristConfirms, typeSuggestion(typeId, i, w), design, materials, coating, second))
    }

    /**
     * The exclusions, enforced on every plan: never BlueProtect together with BlueGuard (or PhotoFusion X, which
     * contains it); myopia management is never a lens choice; at most maxSecondPairs second pairs.
     */
    fun validate(p: LensPlan): LensPlan {
        val blue = p.materials.any { it.id == "blueguard" || it.id == "photofusion_x" }
        val coating = p.coating?.takeUnless { it.id == "dv_blueprotect" && blue }
        check(p.all.none { it.id == "myopia_management" }) { "myopia management is referral only" }
        return p.copy(coating = coating, secondPairs = p.secondPairs.take(cfg.maxSecondPairs))
    }

    companion object {
        const val LABEL = "Suggested ZEISS lens family – optician confirms"
    }
}
