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

            RankedFrame(
                frame = f, score = score, fitScore = fitScore, thinScore = thinScore,
                styleScore = styleScore, budgetScore = budgetScore,
                frameWidthMm = width, fitDeltaMm = delta, fit = fitVerdict(delta),
                thickestMm = thick?.maxMm, thickestIsEdge = thick?.isPlus == false,
                materialId = material.id, thicknessConfidence = powerConf,
                withinBudget = within, styleMatch = styleMatch,
            )
        }
            .sortedWith(compareByDescending<RankedFrame> { it.score }.thenBy { it.frame.priceInr }.thenBy { it.frame.id })
            .take(topN)
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

    companion object {
        fun fromJson(rulesJson: String, priorsJson: String, framesJson: String) =
            DrishtiEngine(RulesConfig.parse(rulesJson), AnthroPriors.parse(priorsJson), FrameCatalog.parse(framesJson))
    }
}
