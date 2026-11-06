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
    val image: String? = null,
) {
    val sizeMark: String get() = "${lensWidthMm.toInt()}-${bridgeMm.toInt()}-${templeMm.toInt()}"
}

@Serializable
data class FrameCatalog(@SerialName("_comment") val comment: String? = null, val frames: List<Frame>) {
    companion object {
        private val json = Json { ignoreUnknownKeys = false }
        fun parse(text: String): FrameCatalog = json.decodeFromString(serializer(), text).also { c ->
            require(c.frames.map { it.id }.toSet().size == c.frames.size) { "Duplicate frame ids in frames.json" }
        }
    }
}

@Serializable enum class FitVerdict { GOOD, SLIGHTLY_NARROW, SLIGHTLY_WIDE, TOO_NARROW, TOO_WIDE }

/** Addition 2: label from the total score; cut-offs in rules.json. */
@Serializable enum class MatchLabel { HIGHLY_RECOMMENDED, MODERATE, LOWER }

@Serializable
enum class ReasonCode { FITS_FACE, NARROW_FOR_FACE, WIDE_FOR_FACE, THIN_LENS, THICK_LENS, IN_BUDGET, OVER_BUDGET, STYLE_MATCH, STYLE_MISMATCH }

/**
 * One bullet explaining a ranking, generated from a score component. Numbers only; the app owns the wording.
 * [a]/[b] meaning depends on [code]: fit → frame width, face width; thickness → mm; budget → price or amount over.
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
)

/** M5 frame ranking: weighted fit + thinness + style + budget, weights from rules.json. */
class FrameRanker(private val rules: RulesConfig) {
    private val c = rules.frameScoring

    fun frameWidthMm(f: Frame): Double = 2 * f.lensWidthMm + f.bridgeMm + 2 * c.endpieceMmEachSide

    fun rank(
        frames: List<Frame>,
        faceWidthMm: Estimate<Double>,
        pdMm: Estimate<Double>,
        lensPowersD: List<Estimate<Double>>,
        materialId: String,
        style: StylePref?,
        budget: Budget?,
        topN: Int = c.topN,
    ): List<RankedFrame> {
        val material = rules.material(materialId)
        val powerConf = lensPowersD.map { it.confidence }.fold(pdMm.confidence) { a, b -> a.min(b) }

        return frames.map { f ->
            val width = frameWidthMm(f)
            val delta = width - faceWidthMm.value * c.idealFrameToFaceWidthRatio
            val fitScore = (1.0 - abs(delta) / c.fitToleranceMm).coerceIn(0.0, 1.0)

            val thick = lensPowersD.map { Thickness.estimate(it.value, f.lensWidthMm, f.bridgeMm, pdMm.value, material) }
                .maxByOrNull { it.maxMm }
            val thinScore = thick?.let {
                (1.0 - (it.maxMm - c.thinnestMm) / (c.thickestMm - c.thinnestMm)).coerceIn(0.0, 1.0)
            } ?: 1.0 // no power known: neutral, identical for all frames

            val styleMatch = style == null || style == StylePref.ANY || style in f.styles
            val styleScore = if (styleMatch) 1.0 else c.styleMismatchScore

            val maxPrice = when (budget) {
                Budget.LOW -> c.budgetMaxPriceInrLow
                Budget.MID -> c.budgetMaxPriceInrMid
                Budget.HIGH, null -> Int.MAX_VALUE
            }
            val within = f.priceInr <= maxPrice
            val budgetScore = if (within) 1.0
            else (1.0 - (f.priceInr - maxPrice).toDouble() / (maxPrice * c.overBudgetFalloffFraction)).coerceIn(0.0, 1.0)

            val score = c.weightFit * fitScore + c.weightThinness * thinScore + c.weightStyle * styleScore + c.weightBudget * budgetScore

            // ---- Addition 2: reasons from the score components ----
            val fit = fitVerdict(delta)
            val reasons = mutableListOf<FrameReason>()
            val faceW = faceWidthMm.value * c.idealFrameToFaceWidthRatio
            reasons += when {
                fit == FitVerdict.GOOD -> FrameReason(ReasonCode.FITS_FACE, true, width, faceW, impact = c.weightFit * fitScore)
                delta < 0 -> FrameReason(ReasonCode.NARROW_FOR_FACE, false, width, faceW, impact = c.weightFit * (1 - fitScore))
                else -> FrameReason(ReasonCode.WIDE_FOR_FACE, false, width, faceW, impact = c.weightFit * (1 - fitScore))
            }
            thick?.let {
                val thin = thinScore >= c.thinPositiveFrom
                reasons += FrameReason(
                    if (thin) ReasonCode.THIN_LENS else ReasonCode.THICK_LENS, thin, it.maxMm,
                    materialId = material.id, isEdge = !it.isPlus,
                    impact = c.weightThinness * if (thin) thinScore else 1 - thinScore,
                )
            }
            if (budget != null) reasons += if (within) {
                FrameReason(ReasonCode.IN_BUDGET, true, f.priceInr.toDouble(), impact = c.weightBudget * budgetScore)
            } else {
                FrameReason(ReasonCode.OVER_BUDGET, false, (f.priceInr - maxPrice).toDouble(), impact = c.weightBudget * (1 - budgetScore))
            }
            if (style != null && style != StylePref.ANY) reasons += FrameReason(
                if (styleMatch) ReasonCode.STYLE_MATCH else ReasonCode.STYLE_MISMATCH, styleMatch,
                impact = c.weightStyle * if (styleMatch) 1.0 else 1 - styleScore,
            )
            val label = when {
                score >= c.labelHighFrom -> MatchLabel.HIGHLY_RECOMMENDED
                score >= c.labelModerateFrom -> MatchLabel.MODERATE
                else -> MatchLabel.LOWER
            }

            RankedFrame(
                frame = f, score = score, fitScore = fitScore, thinScore = thinScore,
                styleScore = styleScore, budgetScore = budgetScore,
                frameWidthMm = width, fitDeltaMm = delta, fit = fit,
                thickestMm = thick?.maxMm, thickestIsEdge = thick?.isPlus == false,
                materialId = material.id, thicknessConfidence = powerConf,
                withinBudget = within, styleMatch = styleMatch,
                label = label, reasons = pickReasons(reasons),
            )
        }
            .sortedWith(compareByDescending<RankedFrame> { it.score }.thenBy { it.frame.priceInr }.thenBy { it.frame.id })
            .take(topN)
    }

    /** Strongest [FrameRanker] reasons (fit always kept), positives first. */
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
    ): SessionResult {
        val a = rulesEngine.assess(answers, staff, tests)
        val advice = a.advice ?: return SessionResult(a, emptyList()) // referral only: no frames either
        val powers = advice.eyes.map { Estimate(it.thicknessPowerD, it.startingTrialLens.confidence, it.startingTrialLens.source) }
        val frames = ranker.rank(catalog.frames, a.faceWidthMm, a.pdMm, powers, advice.materialId.value, answers.style, answers.budget)
        return SessionResult(a, frames)
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
    }
}
