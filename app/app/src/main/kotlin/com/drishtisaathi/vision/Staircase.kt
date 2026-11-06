package com.drishtisaathi.vision

import com.drishtisaathi.engine.config.VisionTestConfig
import kotlin.math.min
import kotlin.random.Random

/** Where the three bars of the tumbling E point. */
enum class EDirection { UP, RIGHT, DOWN, LEFT;
    /** Clockwise rotation from the upright letter "E" (bars pointing right). */
    val rotationDeg: Float get() = when (this) { RIGHT -> 0f; DOWN -> 90f; LEFT -> 180f; UP -> 270f }

    companion object {
        /** Random direction, never the same as [previous] twice in a row. */
        fun random(rnd: Random, previous: EDirection? = null): EDirection {
            val choices = entries.filter { it != previous }
            return choices[rnd.nextInt(choices.size)]
        }
    }
}

/** Parameters of one staircase, taken from [VisionTestConfig]. */
data class StaircaseParams(
    val startLogMar: Double,
    val minLogMar: Double,
    val maxLogMar: Double,
    val initialStep: Double,
    val step: Double,
    val correctToStepDown: Int,
    val reversalsToStop: Int,
    val thresholdLastReversals: Int,
    val maxTrials: Int,
    val boundaryRuns: Int,
) {
    companion object {
        fun from(c: VisionTestConfig) = StaircaseParams(
            c.staircaseStartLogMar, c.staircaseMinLogMar, c.staircaseMaxLogMar, c.staircaseInitialStepLogMar,
            c.staircaseStepLogMar, c.staircaseCorrectToStepDown, c.staircaseReversals, c.staircaseThresholdLastReversals,
            c.staircaseMaxTrials, c.staircaseBoundaryRuns,
        )
    }
}

/**
 * Immutable adaptive staircase in logMAR (larger = bigger letter = easier).
 *
 * Before the first reversal it runs 1-down/1-up with the initial (large) step so it reaches the threshold quickly;
 * after it, n-down/1-up with the fine step (n = 2 converges near 71 % correct, well above the 25 % guess rate of a
 * 4-direction E). A reversal is recorded at the level where the direction of travel changes. It stops after
 * [StaircaseParams.reversalsToStop] reversals, [StaircaseParams.maxTrials] trials, or when the customer keeps
 * failing at the largest size / keeps succeeding at the smallest size ([StaircaseParams.boundaryRuns] in a row).
 */
data class Staircase(
    val p: StaircaseParams,
    val level: Double = p.startLogMar,
    val trials: Int = 0,
    val correctRun: Int = 0,
    /** +1 = last move made the letter bigger, −1 = smaller, 0 = no move yet. */
    val lastMove: Int = 0,
    val reversals: List<Double> = emptyList(),
    val wrongAtMaxRun: Int = 0,
    val rightAtMinRun: Int = 0,
) {
    enum class Stop { REVERSALS, MAX_TRIALS, CEILING, FLOOR }

    val stop: Stop? get() = when {
        wrongAtMaxRun >= p.boundaryRuns -> Stop.CEILING
        rightAtMinRun >= p.boundaryRuns -> Stop.FLOOR
        reversals.size >= p.reversalsToStop -> Stop.REVERSALS
        trials >= p.maxTrials -> Stop.MAX_TRIALS
        else -> null
    }
    val isDone: Boolean get() = stop != null

    private val downAfter: Int get() = if (reversals.isEmpty()) 1 else p.correctToStepDown

    fun answer(correct: Boolean): Staircase {
        check(!isDone) { "staircase already finished" }
        val atMax = level >= p.maxLogMar - EPS
        val atMin = level <= p.minLogMar + EPS
        val t = trials + 1
        if (correct) {
            val run = correctRun + 1
            if (run < downAfter) return copy(trials = t, correctRun = run, wrongAtMaxRun = 0)
            if (atMin) return copy(trials = t, correctRun = 0, wrongAtMaxRun = 0, rightAtMinRun = rightAtMinRun + 1)
            return move(-1, t).copy(wrongAtMaxRun = 0, rightAtMinRun = 0)
        } else {
            if (atMax) return copy(trials = t, correctRun = 0, rightAtMinRun = 0, wrongAtMaxRun = wrongAtMaxRun + 1)
            return move(+1, t).copy(wrongAtMaxRun = 0, rightAtMinRun = 0)
        }
    }

    private fun move(dir: Int, t: Int): Staircase {
        val reversed = lastMove != 0 && dir != lastMove
        val revs = if (reversed) reversals + level else reversals
        // Coarse steps until the first reversal; the reversing move itself already uses the fine step.
        val step = if (revs.isEmpty()) p.initialStep else p.step
        val next = (level + dir * step).coerceIn(p.minLogMar, p.maxLogMar)
        return copy(level = round(next), trials = t, correctRun = 0, lastMove = dir, reversals = revs)
    }

    /**
     * Threshold in logMAR: mean of the last reversals; at a boundary stop, that boundary; with too few reversals
     * (max trials), the mean of all reversals and the final level.
     */
    fun threshold(): Double = when (stop) {
        Stop.CEILING -> p.maxLogMar
        Stop.FLOOR -> p.minLogMar
        else -> {
            if (reversals.size >= 2) round(reversals.takeLast(min(p.thresholdLastReversals, reversals.size)).average())
            else round((reversals + level).average())
        }
    }

    private companion object {
        const val EPS = 1e-9
        fun round(v: Double) = Math.round(v * 1000.0) / 1000.0
    }
}
