package com.drishtisaathi.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

@Serializable enum class FrameShape { RECTANGLE, SQUARE, ROUND, OVAL, CAT_EYE, AVIATOR, WAYFARER }
@Serializable enum class RimType { FULL, HALF, RIMLESS }

/** One catalogue entry. Sizes come from the printed "A-DBL-temple" mark, e.g. 52-18-140. */
@Serializable
data class Frame(
    val id: String,
    val name: String,
    val lensWidthMm: Double,
    val bridgeMm: Double,
    val templeMm: Double,
    val shape: FrameShape,
    val rim: RimType,
    val material: String,
    val priceInr: Int,
    val styles: List<StylePref>,
    // Addition 4. Defaults exist only so sessions stored by earlier builds still decode;
    // FrameCatalog.parse requires real values in frames.json.
    /** Colours this frame comes in (ANY is not used here). */
    val colours: List<ColourPref> = emptyList(),
    val weightGrams: Int = 0,
    /** PROFESSIONAL and/or CASUAL. */
    val looks: List<LookPref> = emptyList(),
    /** Vertical lens size (B measurement); progressives need a minimum. */
    val lensHeightMm: Double = 0.0,
    val image: String? = null,
    /** Try-on cut-out placement data (Track E); null for frames without a bundled PNG or older stored sessions. */
    val overlay: FrameOverlay? = null,
) {
    val sizeMark: String get() = "${lensWidthMm.toInt()}-${bridgeMm.toInt()}-${templeMm.toInt()}"
}

/**
 * Where things sit in a frame's try-on PNG (pixels of that PNG, origin top-left, y down).
 * "Left" and "right" are as seen in the picture, not the wearer's sides.
 */
@Serializable
data class FrameOverlay(
    /** Asset path, e.g. "frames/pm0569.png". */
    val png: String,
    val widthPx: Int,
    val heightPx: Int,
    /** [x, y] centroid of the lens opening on the picture's left / right. */
    val leftLensCenterPx: List<Double>,
    val rightLensCenterPx: List<Double>,
    /** Total width of the frame front, hinge to hinge. */
    val frontWidthPx: Double,
    /** Average height of the lens openings. */
    val lensHeightPx: Double,
) {
    val lensCentreDistancePx: Double
        get() = kotlin.math.hypot(rightLensCenterPx[0] - leftLensCenterPx[0], rightLensCenterPx[1] - leftLensCenterPx[1])

    fun validate(id: String) {
        require(png.isNotBlank() && widthPx > 0 && heightPx > 0) { "frames.json: $id overlay needs png and size" }
        require(leftLensCenterPx.size == 2 && rightLensCenterPx.size == 2) { "frames.json: $id lens centres must be [x, y]" }
        listOf(leftLensCenterPx, rightLensCenterPx).forEach { (x, y) ->
            require(x in 0.0..widthPx.toDouble() && y in 0.0..heightPx.toDouble()) { "frames.json: $id lens centre outside the PNG" }
        }
        require(leftLensCenterPx[0] < rightLensCenterPx[0]) { "frames.json: $id left lens centre must be left of the right one" }
        require(frontWidthPx > lensCentreDistancePx && frontWidthPx <= widthPx) { "frames.json: $id bad frontWidthPx" }
        require(lensHeightPx > 0 && lensHeightPx <= heightPx) { "frames.json: $id bad lensHeightPx" }
    }
}

@Serializable
data class FrameCatalog(@SerialName("_comment") val comment: String? = null, val frames: List<Frame>) {
    companion object {
        private val json = Json { ignoreUnknownKeys = false }
        fun parse(text: String): FrameCatalog = json.decodeFromString(serializer(), text).also { c ->
            require(c.frames.map { it.id }.toSet().size == c.frames.size) { "Duplicate frame ids in frames.json" }
            c.frames.forEach { f ->
                require(f.colours.isNotEmpty() && f.looks.isNotEmpty() && f.weightGrams > 0 && f.lensHeightMm > 0) {
                    "frames.json: ${f.id} needs colours, looks, weightGrams and lensHeightMm"
                }
                require(ColourPref.ANY !in f.colours && LookPref.BOTH !in f.looks) {
                    "frames.json: ${f.id} must use concrete colours/looks, not ANY/BOTH"
                }
                f.overlay?.validate(f.id)
            }
        }
    }
}

@Serializable enum class FitVerdict { GOOD, SLIGHTLY_NARROW, SLIGHTLY_WIDE, TOO_NARROW, TOO_WIDE }

/** Addition 2: label from the total score; cut-offs in rules.json. */
@Serializable enum class MatchLabel { HIGHLY_RECOMMENDED, MODERATE, LOWER }

@Serializable
enum class ReasonCode {
    FITS_FACE, NARROW_FOR_FACE, WIDE_FOR_FACE,
    THIN_LENS, THICK_LENS,
    IN_BUDGET, OVER_BUDGET,
    STYLE_MATCH, STYLE_MISMATCH,
    // Addition 4
    COLOUR_MATCH, COLOUR_MISMATCH,
    LIGHTWEIGHT, HEAVIER,
    LOOK_MATCH, LOOK_MISMATCH,
    LENS_HEIGHT_OK, LENS_HEIGHT_LOW,
    // Addition 5
    BRIDGE_FITS, BRIDGE_MISMATCH,
}

/**
 * One bullet explaining a ranking, generated from a score component. Numbers only; the app owns the wording.
 * [a]/[b] by [code]: fit → frame width, face width; thickness → mm; budget → price or amount over;
 * weight → grams; lens height → height, required minimum.
 */
@Serializable
data class FrameReason(
    val code: ReasonCode,
    val positive: Boolean,
    val a: Double? = null,
    val b: Double? = null,
    val materialId: String? = null,
    val isEdge: Boolean = true,
    /** weight × how strongly this component pulled the score up (positive) or down (negative). */
    val impact: Double = 0.0,
    /** Fit reasons only: face width or PD is a population average, not measured. */
    val assumed: Boolean = false,
    /**
     * Fit and bridge reasons only (rules_update.md rule 6): measured by the camera without the card, so the app must
     * use neutral wording and never say the frame "fits". Ignored when [assumed] (population-average wording wins).
     */
    val uncalibrated: Boolean = false,
)

@Serializable
data class RankedFrame(
    val frame: Frame,
    val score: Double,
    val fitScore: Double,
    val thinScore: Double,
    val styleScore: Double,
    val budgetScore: Double,
    /** Approximate total frame width: 2·A + DBL + both end pieces. */
    val frameWidthMm: Double,
    /** frame width − ideal width for this face (positive = wider). */
    val fitDeltaMm: Double,
    val fit: FitVerdict,
    /** Thickest point of the thicker lens in the recommended material; null when no power is known. */
    val thickestMm: Double?,
    val thickestIsEdge: Boolean,
    val materialId: String,
    val thicknessConfidence: Confidence,
    val withinBudget: Boolean,
    val styleMatch: Boolean,
    /** Null only for sessions stored before labels existed. */
    val label: MatchLabel? = null,
    val reasons: List<FrameReason> = emptyList(),
    // Addition 4 components (defaults keep older stored sessions readable)
    val colourScore: Double = 1.0,
    val weightScore: Double = 1.0,
    val lookScore: Double = 1.0,
    val lensHeightPenalty: Double = 0.0,
)

/** Customer preferences that drive frame scoring (from the interview). */
data class FramePrefs(
    val style: StylePref? = null,
    val budget: Budget? = null,
    val colour: ColourPref? = null,
    val lightweight: Boolean? = null,
    val look: LookPref? = null,
    val firstTimeWearer: Boolean? = null,
    /** Lens type from the rules engine; a progressive needs a minimum lens height. */
    val lensType: LensType? = null,
) {
    /** Light frames matter when asked for, or for a first pair (comfort while adapting). */
    val wantsLight: Boolean get() = lightweight == true || firstTimeWearer == true

    companion object {
        fun from(a: InterviewAnswers, lensType: LensType?) = FramePrefs(
            a.style, a.budget, a.colourPreference, a.lightweightPreference, a.lookPreference, a.firstTimeWearer, lensType,
        )
    }
}

/** M5 frame ranking: weighted fit, thinness, style, budget, colour, weight, look; weights from rules.json. */
class FrameRanker(private val rules: RulesConfig) {
    private val c = rules.frameScoring

    fun frameWidthMm(f: Frame): Double = 2 * f.lensWidthMm + f.bridgeMm + 2 * c.endpieceMmEachSide

    /** Kept for callers that only know style and budget. */
    fun rank(
        frames: List<Frame>,
        faceWidthMm: Estimate<Double>,
        pdMm: Estimate<Double>,
        lensPowersD: List<Estimate<Double>>,
        materialId: String,
        style: StylePref?,
        budget: Budget?,
        topN: Int = c.topN,
    ): List<RankedFrame> = rank(frames, faceWidthMm, pdMm, lensPowersD, materialId, FramePrefs(style, budget), topN)

    fun rank(
        frames: List<Frame>,
        faceWidthMm: Estimate<Double>,
        pdMm: Estimate<Double>,
        lensPowersD: List<Estimate<Double>>,
        materialId: String,
        prefs: FramePrefs,
        topN: Int = c.topN,
        /** Addition 5: measured nose width at the bridge; null = bridge not scored. */
        bridgeWidthMm: Double? = null,
        /** Rule 6: face width / bridge are camera measures without the card → neutral fit wording. */
        fitUncalibrated: Boolean = false,
    ): List<RankedFrame> {
        val material = rules.material(materialId)
        val powerConf = lensPowersD.map { it.confidence }.fold(pdMm.confidence) { a, b -> a.min(b) }
        val faceW = faceWidthMm.value * c.idealFrameToFaceWidthRatio
        val fitAssumed = faceWidthMm.source == Source.POPULATION_AVERAGE || pdMm.source == Source.POPULATION_AVERAGE
        val cap = budgetCap(prefs.budget)

        return frames
            // Frames far over budget are not shown at all.
            .filter { cap == Int.MAX_VALUE || it.priceInr <= cap * (1 + c.hideOverBudgetFraction) }
            .map { f -> score(f, faceW, fitAssumed, pdMm, lensPowersD, material, powerConf, prefs, bridgeWidthMm, fitUncalibrated && !fitAssumed) }
            // In-budget frames always rank above over-budget ones; score decides within each group.
            .sortedWith(
                compareByDescending<RankedFrame> { it.withinBudget }
                    .thenByDescending { it.score }.thenBy { it.frame.priceInr }.thenBy { it.frame.id },
            )
            .take(topN)
    }

    private fun budgetCap(budget: Budget?): Int = when (budget) {
        Budget.LOW -> c.budgetMaxPriceInrLow
        Budget.MID -> c.budgetMaxPriceInrMid
        Budget.HIGH, null -> Int.MAX_VALUE
    }

    private fun score(
        f: Frame, faceW: Double, fitAssumed: Boolean, pdMm: Estimate<Double>, lensPowersD: List<Estimate<Double>>,
        material: MaterialSpec, powerConf: Confidence, p: FramePrefs, bridgeWidthMm: Double? = null,
        uncal: Boolean = false,
    ): RankedFrame {
        val reasons = mutableListOf<FrameReason>()

        // ---- fit ----
        val width = frameWidthMm(f)
        val delta = width - faceW
        val widthFit = (1.0 - abs(delta) / c.fitToleranceMm).coerceIn(0.0, 1.0)
        val fit = fitVerdict(delta)
        reasons += when {
            fit == FitVerdict.GOOD -> FrameReason(ReasonCode.FITS_FACE, true, width, faceW, impact = c.weightFit * widthFit, assumed = fitAssumed, uncalibrated = uncal)
            delta < 0 -> FrameReason(ReasonCode.NARROW_FOR_FACE, false, width, faceW, impact = c.weightFit * (1 - widthFit), assumed = fitAssumed, uncalibrated = uncal)
            else -> FrameReason(ReasonCode.WIDE_FOR_FACE, false, width, faceW, impact = c.weightFit * (1 - widthFit), assumed = fitAssumed, uncalibrated = uncal)
        }
        // Addition 5: when the nose bridge was measured, part of the fit score is bridge (DBL) vs nose width.
        val fitScore = if (bridgeWidthMm == null) widthFit else {
            val d = f.bridgeMm - bridgeWidthMm
            val bridgeFit = (1.0 - abs(d) / c.bridgeFitToleranceMm).coerceIn(0.0, 1.0)
            val ok = abs(d) <= c.bridgeGoodWithinMm
            reasons += FrameReason(
                if (ok) ReasonCode.BRIDGE_FITS else ReasonCode.BRIDGE_MISMATCH, ok, f.bridgeMm, bridgeWidthMm,
                impact = c.weightFit * c.bridgeFitShare * if (ok) bridgeFit else 1 - bridgeFit,
                uncalibrated = uncal,
            )
            (1 - c.bridgeFitShare) * widthFit + c.bridgeFitShare * bridgeFit
        }

        // ---- thinness ----
        val thick = lensPowersD.map { Thickness.estimate(it.value, f.lensWidthMm, f.bridgeMm, pdMm.value, material) }
            .maxByOrNull { it.maxMm }
        val thinScore = thick?.let {
            (1.0 - (it.maxMm - c.thinnestMm) / (c.thickestMm - c.thinnestMm)).coerceIn(0.0, 1.0)
        } ?: 1.0 // no power known: neutral, identical for all frames
        thick?.let {
            val thin = thinScore >= c.thinPositiveFrom
            reasons += FrameReason(
                if (thin) ReasonCode.THIN_LENS else ReasonCode.THICK_LENS, thin, it.maxMm,
                materialId = material.id, isEdge = !it.isPlus,
                impact = c.weightThinness * if (thin) thinScore else 1 - thinScore,
            )
        }

        // ---- style ----
        val styleMatch = p.style == null || p.style == StylePref.ANY || p.style in f.styles
        val styleScore = if (styleMatch) 1.0 else c.styleMismatchScore
        if (p.style != null && p.style != StylePref.ANY) reasons += FrameReason(
            if (styleMatch) ReasonCode.STYLE_MATCH else ReasonCode.STYLE_MISMATCH, styleMatch,
            impact = c.weightStyle * if (styleMatch) 1.0 else 1 - styleScore,
        )

        // ---- budget ----
        val maxPrice = budgetCap(p.budget)
        val within = f.priceInr <= maxPrice
        val budgetScore = if (within) 1.0
        else (1.0 - (f.priceInr - maxPrice).toDouble() / (maxPrice * c.overBudgetFalloffFraction)).coerceIn(0.0, 1.0)
        if (p.budget != null) reasons += if (within) {
            FrameReason(ReasonCode.IN_BUDGET, true, f.priceInr.toDouble(), impact = c.weightBudget * budgetScore)
        } else {
            FrameReason(ReasonCode.OVER_BUDGET, false, (f.priceInr - maxPrice).toDouble(), impact = c.weightBudget * (1 - budgetScore))
        }

        // ---- addition 4: colour ----
        val colourPicked = p.colour != null && p.colour != ColourPref.ANY
        val colourMatch = !colourPicked || p.colour in f.colours
        val colourScore = if (colourMatch) 1.0 else c.colourMismatchScore
        if (colourPicked) reasons += FrameReason(
            if (colourMatch) ReasonCode.COLOUR_MATCH else ReasonCode.COLOUR_MISMATCH, colourMatch,
            impact = c.weightColour * if (colourMatch) 1.0 else 1 - colourScore,
        )

        // ---- addition 4: weight (lightweight preference or first-time wearer) ----
        val weightScore = if (p.wantsLight) {
            (1.0 - (f.weightGrams - c.lightestGrams) / (c.heaviestGrams - c.lightestGrams)).coerceIn(0.0, 1.0)
        } else 1.0
        if (p.wantsLight) {
            val light = weightScore >= c.lightPositiveFrom
            reasons += FrameReason(
                if (light) ReasonCode.LIGHTWEIGHT else ReasonCode.HEAVIER, light, f.weightGrams.toDouble(),
                impact = c.weightWeight * if (light) weightScore else 1 - weightScore,
            )
        }

        // ---- addition 4: look (work/formal vs casual) ----
        val lookPicked = p.look != null && p.look != LookPref.BOTH
        val lookMatch = !lookPicked || p.look in f.looks
        val lookScore = if (lookMatch) 1.0 else c.lookMismatchScore
        if (lookPicked) reasons += FrameReason(
            if (lookMatch) ReasonCode.LOOK_MATCH else ReasonCode.LOOK_MISMATCH, lookMatch,
            impact = c.weightLook * if (lookMatch) 1.0 else 1 - lookScore,
        )

        // ---- addition 4: lens height for progressives (penalty, not a weighted component) ----
        var heightPenalty = 0.0
        if (p.lensType == LensType.PROGRESSIVE) {
            val ok = f.lensHeightMm >= c.progressiveMinLensHeightMm
            if (!ok) heightPenalty = c.shortLensHeightPenalty
            reasons += FrameReason(
                if (ok) ReasonCode.LENS_HEIGHT_OK else ReasonCode.LENS_HEIGHT_LOW, ok,
                f.lensHeightMm, c.progressiveMinLensHeightMm,
                // a too-short lens is a hard practical problem: always surface it
                impact = if (ok) 0.0 else 1.0,
            )
        }

        val score = (c.weightFit * fitScore + c.weightThinness * thinScore + c.weightStyle * styleScore +
            c.weightBudget * budgetScore + c.weightColour * colourScore + c.weightWeight * weightScore +
            c.weightLook * lookScore - heightPenalty).coerceIn(0.0, 1.0)
        val label = when {
            score >= c.labelHighFrom -> MatchLabel.HIGHLY_RECOMMENDED
            score >= c.labelModerateFrom -> MatchLabel.MODERATE
            else -> MatchLabel.LOWER
        }

        return RankedFrame(
            frame = f, score = score, fitScore = fitScore, thinScore = thinScore,
            styleScore = styleScore, budgetScore = budgetScore,
            frameWidthMm = width, fitDeltaMm = delta, fit = fit,
            thickestMm = thick?.maxMm, thickestIsEdge = thick?.isPlus == false,
            materialId = material.id, thicknessConfidence = powerConf,
            withinBudget = within, styleMatch = styleMatch,
            label = label, reasons = pickReasons(reasons),
            colourScore = colourScore, weightScore = weightScore, lookScore = lookScore, lensHeightPenalty = heightPenalty,
        )
    }

    /** Strongest reasons (fit always kept), positives first. */
    private fun pickReasons(all: List<FrameReason>): List<FrameReason> {
        val fit = all.first()
        val rest = all.drop(1).sortedByDescending { it.impact }.take(c.maxReasons - 1)
        return (listOf(fit) + rest).sortedByDescending { it.positive }
    }

    private fun fitVerdict(delta: Double): FitVerdict = when {
        abs(delta) <= c.goodFitWithinMm -> FitVerdict.GOOD
        abs(delta) <= c.fitToleranceMm -> if (delta < 0) FitVerdict.SLIGHTLY_NARROW else FitVerdict.SLIGHTLY_WIDE
        else -> if (delta < 0) FitVerdict.TOO_NARROW else FitVerdict.TOO_WIDE
    }
}

/** Everything the app stores and displays for one session. */
@Serializable
data class SessionResult(val assessment: Assessment, val frames: List<RankedFrame>)

/** Facade used by the app: one call from answers to a complete, confidence-tagged result. */
class DrishtiEngine(private val rules: RulesConfig, priors: AnthroPriors, private val catalog: FrameCatalog) {
    private val rulesEngine = RulesEngine(rules, priors)
    private val ranker = FrameRanker(rules)

    fun evaluate(
        answers: InterviewAnswers,
        staff: StaffMeasurements = StaffMeasurements(),
        tests: VisionTests = VisionTests(),
        face: MeasuredFace? = null,
        /** This phone model's learning-loop fit (null: none yet, or a demo session). */
        correction: com.drishtisaathi.engine.vision.DeviceFit? = null,
        /** Power path quick estimate stored with the session. */
        quick: QuickEstimate? = null,
    ): SessionResult {
        val a = rulesEngine.assess(answers, staff, tests, face, correction, quick)
        val advice = a.advice ?: return SessionResult(a, emptyList()) // referral only: no frames either
        val powers = advice.eyes.map { Estimate(it.thicknessPowerD, it.startingTrialLens.confidence, it.startingTrialLens.source) }
        val prefs = FramePrefs.from(answers, advice.lensType.value)
        val frames = ranker.rank(
            catalog.frames, a.faceWidthMm, a.pdMm, powers, advice.materialId.value, prefs,
            bridgeWidthMm = face?.bridgeWidthMm?.value,
            fitUncalibrated = a.fitMeasurementsUncalibrated,
        )
        return SessionResult(a, frames)
    }

    /**
     * Ranks other frames (the 3D catalogue) with the same face width, PD, lens power, budget and style inputs as
     * [evaluate]; all frames that pass the budget filter are returned, best first.
     */
    fun rankFrames(frames: List<Frame>, answers: InterviewAnswers, a: Assessment, face: MeasuredFace? = null): List<RankedFrame> {
        val adv = a.advice
        val powers = adv?.eyes?.map { Estimate(it.thicknessPowerD, it.startingTrialLens.confidence, it.startingTrialLens.source) }.orEmpty()
        val prefs = FramePrefs.from(answers, adv?.lensType?.value)
        return ranker.rank(
            frames, a.faceWidthMm, a.pdMm, powers, adv?.materialId?.value ?: rules.materials.first().id, prefs,
            topN = maxOf(1, frames.size), bridgeWidthMm = face?.bridgeWidthMm?.value,
            fitUncalibrated = a.fitMeasurementsUncalibrated,
        )
    }

    /**
     * Addition 3: thickest point of the thicker lens for [frame] in every material, for side-by-side comparison.
     * Null when no power is known or advice was withheld.
     */
    fun thicknessByMaterial(frame: Frame, a: Assessment): Map<String, Double>? {
        val adv = a.advice ?: return null
        if (adv.eyes.isEmpty()) return null
        return rules.materials.associate { m ->
            m.id to adv.eyes.maxOf { e ->
                Thickness.estimate(e.thicknessPowerD, frame.lensWidthMm, frame.bridgeMm, a.pdMm.value, m).maxMm
            }
        }
    }

    companion object {
        fun fromJson(rulesJson: String, priorsJson: String, framesJson: String) =
            DrishtiEngine(RulesConfig.parse(rulesJson), AnthroPriors.parse(priorsJson), FrameCatalog.parse(framesJson))

        /** All 30 frames: frames.json (F01–F10) plus frames/more_frames.json (F11–F30, sizes estimated from photos). */
        fun fromJson(rulesJson: String, priorsJson: String, framesJson: String, moreFramesJson: String) =
            DrishtiEngine(
                RulesConfig.parse(rulesJson), AnthroPriors.parse(priorsJson),
                com.drishtisaathi.engine.tryon.TryOnCatalog.parseCombined(framesJson, moreFramesJson),
            )
    }
}
