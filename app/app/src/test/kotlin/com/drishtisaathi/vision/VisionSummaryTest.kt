package com.drishtisaathi.vision

import com.drishtisaathi.engine.RulesConfig
import com.drishtisaathi.i18n.SVision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VisionSummaryTest {
    private val rules = RulesConfig.parse(File("src/main/assets/rules.json").readText())
    private val cfg = rules.visionTests
    private val banned = Regex("""\b(prescription|prescribed|diagnos\w*|power is)\b""", RegexOption.IGNORE_CASE)
    private val bannedHi = listOf("पर्चा", "निदान", "पावर है")

    private val full = VisionSession(
        farPoint = mapOf(
            TestEye.RIGHT to FarPointAnalysis.analyse(TestEye.RIGHT, listOf(400.0, 400.0), listOf(550.0, 300.0), rules)!!,
            TestEye.LEFT to FarPointAnalysis.analyse(TestEye.LEFT, listOf(null, null), listOf(null, null), rules)!!,
        ),
        clockDial = mapOf(TestEye.RIGHT to ClockDialAnalysis.analyse(TestEye.RIGHT, listOf(3, 3), 30)!!),
        nearPoint = NearPointAnalysis.analyse(listOf(333.0, 333.0), null, 0.4, 50, rules),
        nearAcuity = mapOf(TestEye.RIGHT to NearAcuityAnalysis.analyse(TestEye.RIGHT, listOf(0.9, 0.9), cfg, true)!!),
        amsler = mapOf(TestEye.LEFT to AmslerAnalysis.analyse(TestEye.LEFT, listOf(true, true), emptyList(), cfg)!!),
        skipped = setOf(VisionStep.ClockDial(TestEye.LEFT)),
        simulatedDistance = true,
    )

    @Test fun `summary has one section per eye plus both eyes`() {
        val s = VisionSummary.sections(full)
        assertEquals(listOf(SVision.eyeRight, SVision.eyeLeft, SVision.eyeBoth), s.map { it.title })
        val right = s[0].lines.map { it.en }
        assertTrue(right.any { it.startsWith("Far point: blue 40 cm / 40 cm, white 55 cm / 30 cm") })
        assertTrue(right.any { it.contains("axis 90°") })
        assertTrue(SVision.sumAcuityRefer in s[0].lines)
        val left = s[1].lines
        assertTrue(left.any { it.en.contains("no myopia found") })
        assertTrue(left.any { it.en == "Clock dial test: skipped" })
        assertTrue(SVision.sumAmslerBad in left)
        val both = s[2].lines
        assertTrue(both.any { it.en.startsWith("Reading test: near point 33 cm") })
        assertTrue(SVision.nearNoSe in both)
        assertTrue(SVision.simulated in both)
    }

    @Test fun `summary lines are bilingual and avoid banned wording`() {
        val lines = VisionSummary.sections(full).flatMap { it.lines } + VisionSummary.sections(VisionSession()).flatMap { it.lines }
        lines.forEach { t ->
            assertTrue(t.en.isNotBlank() && t.hi.isNotBlank())
            assertFalse(t.en, banned.containsMatchIn(t.en))
            assertFalse(t.hi, bannedHi.any { it in t.hi })
            assertFalse(t.en, t.en.contains('%'))
        }
    }

    @Test fun `not run and out of reach`() {
        val s = VisionSummary.sections(VisionSession(nearPoint = NearPointAnalysis.analyse(listOf(null, null), 0.0, 0.4, 60, rules)))
        assertTrue(s[0].lines.any { it.en == "Far point test: not run" })
        assertTrue(SVision.sumNearOut in s[2].lines)
    }
}
