package com.drishtisaathi.engine.profile

import com.drishtisaathi.engine.Budget
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.QuickEstimate
import com.drishtisaathi.engine.QuickEye
import com.drishtisaathi.engine.QuickTarget
import com.drishtisaathi.engine.TestAssets
import com.drishtisaathi.engine.VisionProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase E: one test per catalogue trigger and per exclusion, the profile rings, and five full personas. */
class LensEngineTest {
    private val rules = TestAssets.rules
    private val cfg = rules.lensEngine
    private val catalog = LensCatalog.parse(TestAssets.text(LensCatalog.ASSET))
    private val engine = LensEngine(catalog, cfg)
    private val words = LensWords(L10n("far", "दूर"), L10n("near", "पास"), L10n("tired eyes", "आँखों की थकान"), L10n("headaches", "सिरदर्द"),
        L10n("neck pain", "गर्दन दर्द"), L10n("eye strain", "आँखों पर ज़ोर"))
    private val ok = SafetyGate()
    private val S = Freq.SOMETIMES
    private fun fires(id: String, i: ProfileInputs) = engine.triggers(i).getValue(id)
    private fun plan(i: ProfileInputs, gate: SafetyGate = ok) = engine.plan(i, gate, words)

    // ---- the catalogue ----

    @Test fun `every catalogue entry has a trigger and a display name, and nothing else has`() {
        assertEquals(catalog.ids, engine.triggers(ProfileInputs()).keys)
        assertEquals(27, catalog.ids.size)
        for (id in catalog.designs.map { it.id } + catalog.secondPairSun.map { it.id }) assertNotNull(id, cfg.names[id])
        assertEquals("Suggested ZEISS lens family – optician confirms", LensEngine.LABEL)
        assertEquals(6, catalog.selectionOrder.size)
    }

    // ---- lens type triggers ----

    @Test fun `single vision - under 30, or 30 to 44 without near or screen tiredness, and no near add`() {
        assertTrue(fires("single_vision", ProfileInputs(age = 25, screenHours = 9.0)))
        assertTrue(fires("single_vision", ProfileInputs(age = 35, screenHours = 2.0)))
        assertFalse(fires("single_vision", ProfileInputs(age = 35, screenHours = 4.0)))
        assertFalse(fires("single_vision", ProfileInputs(age = 35, tiredEyes = S)))
        assertFalse(fires("single_vision", ProfileInputs(age = 25, addD = 0.75)))
        assertFalse(fires("single_vision", ProfileInputs(age = 50)))
    }

    @Test fun `anti-fatigue - 30 to 44 with screen hours from 4 or tiredness, and an add under 0_75`() {
        assertTrue(fires("anti_fatigue", ProfileInputs(age = 30, screenHours = 4.0)))
        assertTrue(fires("anti_fatigue", ProfileInputs(age = 44, nearBlur = S)))
        assertTrue(fires("anti_fatigue", ProfileInputs(age = 40, tiredEyes = Freq.OFTEN, addD = 0.5)))
        assertFalse(fires("anti_fatigue", ProfileInputs(age = 29, screenHours = 8.0)))
        assertFalse(fires("anti_fatigue", ProfileInputs(age = 45, screenHours = 8.0)))
        assertFalse(fires("anti_fatigue", ProfileInputs(age = 40, screenHours = 3.0, tiredEyes = Freq.NEVER)))
        assertFalse(fires("anti_fatigue", ProfileInputs(age = 40, screenHours = 8.0, addD = 0.75)))
    }

    @Test fun `progressive - from 45, or a measured near add from 0_75`() {
        assertTrue(fires("progressive", ProfileInputs(age = 45)))
        assertTrue(fires("progressive", ProfileInputs(age = 41, addD = 0.75)))
        assertFalse(fires("progressive", ProfileInputs(age = 44, addD = 0.5)))
        assertEquals("progressive", plan(ProfileInputs(age = 41, addD = 1.0, screenHours = 8.0)).type!!.id)
    }

    @Test fun `office second pair - desk hours from 6 and (40 or older, or neck pain or headache)`() {
        assertTrue(fires("office", ProfileInputs(age = 40, deskHours = 6.0)))
        assertTrue(fires("office", ProfileInputs(age = 28, deskHours = 9.0, neckPain = S)))
        assertTrue(fires("office", ProfileInputs(age = 28, deskHours = 9.0, headache = Freq.OFTEN)))
        assertFalse(fires("office", ProfileInputs(age = 28, deskHours = 9.0)))
        assertFalse(fires("office", ProfileInputs(age = 50, deskHours = 5.0)))
    }

    @Test fun `myopia management - under 18 with minus power is a referral, never a lens choice`() {
        assertTrue(fires("myopia_management", ProfileInputs(age = 10, myopic = true)))
        assertFalse(fires("myopia_management", ProfileInputs(age = 10)))
        assertFalse(fires("myopia_management", ProfileInputs(age = 18, myopic = true)))
        val gate = SafetyGate(child = true, childMyopia = true, tag = ReportTag.CHILD_MYOPIA)
        val p = plan(ProfileInputs(age = 10, myopic = true), gate)
        assertFalse(p.allowed); assertTrue(p.all.isEmpty())
        assertEquals("myopia_management", p.referralInfo!!.id)
        assertTrue(p.referralInfo!!.why.single().en.startsWith("Your child's eyes are short-sighted"))
    }

    // ---- design triggers ----

    @Test fun `ClearView - low budget, single vision`() {
        assertTrue(fires("clearview", ProfileInputs(budget = Budget.LOW)))
        assertFalse(fires("clearview", ProfileInputs(budget = Budget.MID)))
        assertEquals("clearview", plan(ProfileInputs(age = 25, budget = Budget.LOW)).design!!.id)
    }

    @Test fun `Light 2 - low or mid budget, or a first progressive`() {
        assertTrue(fires("light2", ProfileInputs(budget = Budget.LOW))); assertTrue(fires("light2", ProfileInputs(budget = Budget.MID)))
        assertTrue(fires("light2", ProfileInputs(budget = Budget.HIGH, firstProgressive = true)))
        assertFalse(fires("light2", ProfileInputs(budget = Budget.HIGH)))
        assertEquals("light2", plan(ProfileInputs(age = 55, budget = Budget.LOW)).design!!.id)
        assertEquals("light2", plan(ProfileInputs(age = 55, budget = Budget.MID, firstProgressive = true)).design!!.id)
        assertEquals("only for progressives", "smartlife", plan(ProfileInputs(age = 25, budget = Budget.MID, firstProgressive = true)).design!!.id)
    }

    @Test fun `SmartLife - mid or high budget, the default premium design`() {
        assertTrue(fires("smartlife", ProfileInputs(budget = Budget.MID, screenHours = 5.0)))
        assertTrue(fires("smartlife", ProfileInputs(budget = Budget.HIGH, screenHours = 5.0)))
        assertFalse(fires("smartlife", ProfileInputs(budget = Budget.LOW, screenHours = 9.0)))
        assertEquals("smartlife", plan(ProfileInputs(age = 55, budget = Budget.MID)).design!!.id)
        assertEquals("an unknown budget counts as mid", "smartlife", plan(ProfileInputs(age = 25)).design!!.id)
        assertEquals("anti-fatigue has no value design", "smartlife", plan(ProfileInputs(age = 35, screenHours = 8.0, budget = Budget.LOW)).design!!.id)
    }

    @Test fun `ClearMind - high budget and busy visual work or focus complaints`() {
        assertTrue(fires("clearmind", ProfileInputs(budget = Budget.HIGH, focusStruggle = S)))
        assertTrue(fires("clearmind", ProfileInputs(budget = Budget.HIGH, workActivities = setOf("DESKTOP", "MEETINGS"))))
        assertFalse(fires("clearmind", ProfileInputs(budget = Budget.HIGH)))
        assertFalse(fires("clearmind", ProfileInputs(budget = Budget.MID, focusStruggle = Freq.OFTEN)))
        assertEquals("clearmind", plan(ProfileInputs(age = 50, budget = Budget.HIGH, focusStruggle = S)).design!!.id)
        assertEquals("smartlife", plan(ProfileInputs(age = 50, budget = Budget.HIGH)).design!!.id)
    }

    @Test fun `DriveSafe - drives daily or weekly and has glare or poor low-light vision`() {
        assertTrue(fires("drivesafe", ProfileInputs(drive = DriveFrequency.DAILY, glare = S)))
        assertTrue(fires("drivesafe", ProfileInputs(drive = DriveFrequency.WEEKLY, lowLightDriving = Freq.OFTEN)))
        assertTrue("a reduced contrast test counts as poor low-light vision", fires("drivesafe", ProfileInputs(drive = DriveFrequency.WEEKLY, contrastReduced = true)))
        assertFalse(fires("drivesafe", ProfileInputs(drive = DriveFrequency.MONTHLY, glare = Freq.OFTEN)))
        assertFalse(fires("drivesafe", ProfileInputs(drive = DriveFrequency.DAILY, glare = Freq.NEVER)))
        // daily drivers get it as the main lens with its own coating; weekly drivers as a second pair
        val daily = plan(ProfileInputs(age = 30, drive = DriveFrequency.DAILY, glare = S, budget = Budget.MID))
        assertEquals("drivesafe", daily.design!!.id); assertEquals("dv_drivesafe", daily.coating!!.id)
        assertFalse(daily.secondPairs.any { it.id == "drivesafe" })
        val weekly = plan(ProfileInputs(age = 30, drive = DriveFrequency.WEEKLY, glare = S, budget = Budget.MID))
        assertEquals("smartlife", weekly.design!!.id); assertTrue(weekly.secondPairs.any { it.id == "drivesafe" })
        assertEquals("not a design of anti-fatigue lenses", "smartlife",
            plan(ProfileInputs(age = 35, screenHours = 8.0, drive = DriveFrequency.DAILY, glare = S, budget = Budget.MID)).design!!.id)
    }

    @Test fun `SmartLife Young - under 18 without myopia, labelled optometrist confirms`() {
        assertTrue(fires("smartlife_young", ProfileInputs(age = 12)))
        assertFalse(fires("smartlife_young", ProfileInputs(age = 12, myopic = true)))
        assertFalse(fires("smartlife_young", ProfileInputs(age = 18)))
        val p = plan(ProfileInputs(age = 12, budget = Budget.HIGH, screenHours = 6.0), SafetyGate(child = true))
        assertTrue(p.allowed); assertTrue(p.optometristConfirms)
        assertEquals("single_vision", p.type!!.id); assertEquals("smartlife_young", p.design!!.id); assertEquals("dv_kids", p.coating!!.id)
    }

    // ---- material triggers ----

    @Test fun `UVProtect is always there`() {
        assertTrue(fires("uvprotect", ProfileInputs()))
        for (i in listOf(ProfileInputs(age = 25), ProfileInputs(age = 60, budget = Budget.LOW), ProfileInputs(age = 12)))
            assertEquals("uvprotect", plan(i).materials.first().id)
    }

    @Test fun `BlueGuard - screen hours from 4, or digital strain at least sometimes`() {
        assertTrue(fires("blueguard", ProfileInputs(screenHours = 4.0)))
        assertTrue(fires("blueguard", ProfileInputs(screenHours = 1.0, tiredEyes = S)))
        assertTrue(fires("blueguard", ProfileInputs(focusStruggle = Freq.OFTEN)))
        assertFalse(fires("blueguard", ProfileInputs(screenHours = 3.0, tiredEyes = Freq.NEVER)))
        val p = plan(ProfileInputs(age = 25, screenHours = 6.0, tiredEyes = S))
        assertEquals(listOf("uvprotect", "blueguard"), p.materials.map { it.id })
        assertEquals("You use screens 6 h a day and report tired eyes; BlueGuard cuts part of screen blue light with fewer reflections.", p.materials[1].why.single().en)
    }

    @Test fun `PhotoFusion X - outdoor hours from 2 and one pair wanted for indoors and outdoors`() {
        assertTrue(fires("photofusion_x", ProfileInputs(outdoorHours = 2.0, onePair = true)))
        assertFalse(fires("photofusion_x", ProfileInputs(outdoorHours = 6.0, onePair = false)))
        assertFalse(fires("photofusion_x", ProfileInputs(outdoorHours = 6.0)))
        assertFalse(fires("photofusion_x", ProfileInputs(outdoorHours = 1.0, onePair = true)))
        val p = plan(ProfileInputs(age = 30, outdoorHours = 5.0, onePair = true, screenHours = 8.0))
        assertEquals("the blue filter is already inside PhotoFusion X", listOf("uvprotect", "photofusion_x"), p.materials.map { it.id })
        assertTrue(p.materials[1].why.single().hi.contains("5"))
    }

    // ---- coating triggers ----

    @Test fun `coatings by budget - Chrome, Silver or Platinum, Gold`() {
        assertTrue(fires("dv_chrome", ProfileInputs(budget = Budget.LOW))); assertFalse(fires("dv_chrome", ProfileInputs(budget = Budget.MID)))
        assertTrue(fires("dv_silver", ProfileInputs(budget = Budget.MID))); assertFalse(fires("dv_silver", ProfileInputs(budget = Budget.HIGH)))
        assertTrue(fires("dv_platinum", ProfileInputs(budget = Budget.MID))); assertTrue(fires("dv_platinum", ProfileInputs(budget = Budget.HIGH)))
        assertFalse(fires("dv_platinum", ProfileInputs(budget = Budget.LOW)))
        assertTrue(fires("dv_gold", ProfileInputs(budget = Budget.HIGH))); assertFalse(fires("dv_gold", ProfileInputs(budget = Budget.MID)))
        assertEquals("dv_chrome", plan(ProfileInputs(age = 25, budget = Budget.LOW)).coating!!.id)
        assertEquals("dv_silver", plan(ProfileInputs(age = 25, budget = Budget.MID)).coating!!.id)
        assertEquals("dv_platinum", plan(ProfileInputs(age = 25, budget = Budget.MID, screenHours = 5.0)).coating!!.id)
        assertEquals("dv_gold", plan(ProfileInputs(age = 25, budget = Budget.HIGH)).coating!!.id)
    }

    @Test fun `AntiVirus Platinum UV - mid or high budget and public-facing work`() {
        assertTrue(fires("dv_antivirus_platinum_uv", ProfileInputs(budget = Budget.MID, workActivities = setOf("MEETINGS"))))
        assertFalse(fires("dv_antivirus_platinum_uv", ProfileInputs(budget = Budget.LOW, workActivities = setOf("MEETINGS"))))
        assertFalse(fires("dv_antivirus_platinum_uv", ProfileInputs(budget = Budget.MID, workActivities = setOf("DESKTOP"))))
        assertEquals("dv_antivirus_platinum_uv", plan(ProfileInputs(age = 50, budget = Budget.MID, workActivities = setOf("MEETINGS"))).coating!!.id)
    }

    @Test fun `DuraVision Kids for under 18, DriveSafe coating only with the DriveSafe design`() {
        assertTrue(fires("dv_kids", ProfileInputs(age = 17))); assertFalse(fires("dv_kids", ProfileInputs(age = 18)))
        assertTrue(fires("dv_drivesafe", ProfileInputs(drive = DriveFrequency.DAILY, glare = S)))
        assertFalse("a second-pair DriveSafe does not change the main lens's coating", fires("dv_drivesafe", ProfileInputs(drive = DriveFrequency.WEEKLY, glare = S)))
        assertFalse(fires("dv_drivesafe", ProfileInputs(drive = DriveFrequency.DAILY)))
    }

    // ---- exclusions ----

    @Test fun `exclusion - BlueProtect never together with BlueGuard`() {
        for (i in listOf(ProfileInputs(screenHours = 9.0), ProfileInputs(screenHours = 9.0, budget = Budget.LOW), ProfileInputs(screenHours = 5.0, outdoorHours = 4.0, onePair = true))) {
            assertFalse(fires("dv_blueprotect", i))
            assertFalse(plan(i.copy(age = 30)).ids.contains("dv_blueprotect"))
        }
        // even a plan put together by hand loses the coating
        val base = plan(ProfileInputs(age = 30, screenHours = 9.0))
        val forced = engine.validate(base.copy(coating = base.coating!!.copy(id = "dv_blueprotect")))
        assertNull(forced.coating)
        val photo = plan(ProfileInputs(age = 30, outdoorHours = 4.0, onePair = true))
        assertNull(engine.validate(photo.copy(coating = photo.coating!!.copy(id = "dv_blueprotect"))).coating)
    }

    @Test fun `exclusion - PhotoFusion X is never the answer to night glare`() {
        // strong night-glare complaints, daily driving, but no wish for one indoor + outdoor pair
        val glare = ProfileInputs(age = 50, drive = DriveFrequency.DAILY, glare = Freq.OFTEN, lowLightDriving = Freq.OFTEN, outdoorHours = 1.0)
        assertFalse(fires("photofusion_x", glare))
        assertFalse(plan(glare).ids.contains("photofusion_x"))
        assertEquals("drivesafe", plan(glare).design!!.id)
        // with PhotoFusion X chosen for the sun, DriveSafe is still there for the glare
        val both = plan(glare.copy(outdoorHours = 4.0, onePair = true, drive = DriveFrequency.WEEKLY))
        assertTrue(both.ids.contains("photofusion_x")); assertTrue(both.secondPairs.any { it.id == "drivesafe" })
    }

    @Test fun `exclusion - red flags and child myopia never get a lens, and at most two second pairs`() {
        val rich = ProfileInputs(age = 50, deskHours = 9.0, drive = DriveFrequency.WEEKLY, glare = S, leisure = setOf("WATER"), outdoorHours = 7.0)
        assertEquals(2, plan(rich).secondPairs.size)
        assertEquals(listOf("office", "drivesafe"), plan(rich).secondPairs.map { it.id })
        val urgent = plan(rich, SafetyGate(redFlags = listOf(RedFlag.NEW_FLASHES), emergency = true, tag = ReportTag.URGENT))
        assertFalse(urgent.allowed); assertTrue(urgent.all.isEmpty()); assertNull(urgent.referralInfo)
        assertEquals(ReportTag.URGENT, urgent.tag)
    }

    // ---- sun second pairs ----

    @Test fun `polarised sun - daytime driving, water or outdoor sport, field or farm work`() {
        assertTrue(fires("sun_polarised", ProfileInputs(drive = DriveFrequency.MONTHLY)))
        assertTrue(fires("sun_polarised", ProfileInputs(leisure = setOf("OUTDOOR_SPORT"))))
        assertTrue(fires("sun_polarised", ProfileInputs(leisure = setOf("WATER"))))
        assertTrue(fires("sun_polarised", ProfileInputs(workActivities = setOf("FIELD_FARM"))))
        assertFalse(fires("sun_polarised", ProfileInputs(drive = DriveFrequency.NEVER, leisure = setOf("CITY"))))
        assertEquals(listOf("sun_polarised"), plan(ProfileInputs(age = 25, leisure = setOf("OUTDOOR_SPORT"))).secondPairs.map { it.id })
    }

    @Test fun `AdaptiveSun, tints, mirror and Skylet`() {
        assertTrue(fires("sun_adaptivesun", ProfileInputs(indoorOutdoor = "BOTH", outdoorHours = 3.0)))
        assertFalse(fires("sun_adaptivesun", ProfileInputs(indoorOutdoor = "INDOOR", outdoorHours = 3.0)))
        assertFalse("one pair wanted: PhotoFusion X instead", fires("sun_adaptivesun", ProfileInputs(indoorOutdoor = "BOTH", outdoorHours = 3.0, onePair = true)))
        assertTrue(fires("sun_tint", ProfileInputs(budget = Budget.LOW, outdoorHours = 2.0)))
        assertFalse(fires("sun_tint", ProfileInputs(budget = Budget.MID, outdoorHours = 2.0)))
        assertTrue(fires("sun_mirror_flash", ProfileInputs(outdoorHours = 6.0))); assertFalse(fires("sun_mirror_flash", ProfileInputs(outdoorHours = 5.0)))
        assertTrue(fires("sun_skylet", ProfileInputs(leisure = setOf("WATER")))); assertFalse(fires("sun_skylet", ProfileInputs(leisure = setOf("CITY"))))
        assertEquals(listOf("sun_adaptivesun"), plan(ProfileInputs(age = 25, indoorOutdoor = "BOTH", outdoorHours = 3.0)).secondPairs.map { it.id })
        assertEquals(listOf("sun_mirror_flash"), plan(ProfileInputs(age = 25, outdoorHours = 7.0)).secondPairs.map { it.id })
    }

    // ---- profile rings ----

    @Test fun `profile rings - labels and scores from the answers`() {
        val vp = rules.visionProfile
        val desk = VisionProfile.from(ProfileInputs(workActivities = setOf("DESKTOP", "READING_WRITING"), screenHours = 8.0, drive = DriveFrequency.DAILY,
            glare = S, outdoorHours = 0.5), vp)
        assertEquals("NEAR_FOCUSED", desk.work.label); assertEquals(88, desk.work.score)
        assertEquals("DIGITAL_NATIVE", desk.digital.label); assertEquals(80, desk.digital.score)
        assertEquals("DAILY", desk.mobility.label); assertEquals(100, desk.mobility.score); assertTrue(desk.nightGlareSensitive)
        assertEquals("LOW", desk.leisure.label)

        val farmer = VisionProfile.from(ProfileInputs(workActivities = setOf("FIELD_FARM"), screenHours = 1.0, drive = DriveFrequency.NEVER,
            outdoorHours = 8.0, leisure = setOf("FARMING_FIELD")), vp)
        assertEquals("DISTANCE_FOCUSED", farmer.work.label); assertEquals("LIGHT", farmer.digital.label)
        assertEquals("RARELY", farmer.mobility.label); assertFalse(farmer.nightGlareSensitive)
        assertEquals("HIGH", farmer.leisure.label); assertEquals(100, farmer.leisure.score)

        // the digital thresholds: under 3 light, 3 to 6 connected, over 6 digital native
        fun digital(h: Double) = VisionProfile.from(ProfileInputs(screenHours = h), vp).digital.label
        assertEquals(listOf("LIGHT", "CONNECTED", "CONNECTED", "DIGITAL_NATIVE"), listOf(2.9, 3.0, 6.0, 6.5).map(::digital))
        assertEquals("WEEKLY", VisionProfile.from(ProfileInputs(drive = DriveFrequency.WEEKLY), vp).mobility.label)
        assertEquals("MEDIUM", VisionProfile.from(ProfileInputs(outdoorHours = 3.0), vp).leisure.label)
        assertEquals("BALANCED", VisionProfile.from(ProfileInputs(workActivities = setOf("MEETINGS")), vp).work.label)

        // nothing answered: neutral rings marked as not known
        val empty = VisionProfile.from(ProfileInputs(), vp)
        assertTrue(empty.rings.none { it.second.known })
        assertEquals(50, empty.work.score); assertEquals("LIGHT", empty.digital.label)
        assertTrue(desk.overall in 0..100)
    }

    // ---- five full personas, from interview and survey answers through the safety gate ----

    private fun eye(se: Double?) = QuickEye(se, se?.let { -100 / it }, QuickTarget.WHITE, noMyopiaAtReach = se == null)
    private fun survey(vararg kv: Pair<String, List<String>>) = kv.fold(SurveyAnswers()) { s, (k, v) -> s.with(k, v) }

    private fun persona(a: InterviewAnswers, s: SurveyAnswers, quick: QuickEstimate): Triple<LensPlan, VisionProfile, SafetyGate> {
        val gate = Safety.gate(a, s, rules.safety, quick)
        val i = ProfileInputs.from(a, s, cfg, quick, gate = gate)
        return Triple(engine.plan(i, gate, words), VisionProfile.from(i, rules.visionProfile), gate)
    }

    @Test fun `persona 1 - 25-year-old student`() {
        val a = InterviewAnswers(age = 25, visionProblem = VisionProblem.DISTANCE, distanceBlur = true, budget = Budget.LOW, redFlagFlashes = false, redFlagOther = false)
        val s = survey("tiredEyes" to listOf("SOMETIMES"), "workHours" to listOf("6"), "workActivities" to listOf("READING_WRITING"),
            "screenHoursDay" to listOf("6"), "driveFrequency" to listOf("NEVER"), "outdoorHours" to listOf("1"))
        val (p, v, _) = persona(a, s, QuickEstimate(eye(-1.5), eye(-1.25)))
        assertTrue(p.allowed)
        assertEquals("single_vision", p.type!!.id)
        assertEquals("Your blur is at one distance (far); one power fixes it.", p.type!!.why.single().en)
        assertEquals("clearview", p.design!!.id)
        assertEquals(listOf("uvprotect", "blueguard"), p.materials.map { it.id })
        assertEquals("dv_chrome", p.coating!!.id)
        assertTrue(p.secondPairs.isEmpty())
        assertEquals("NEAR_FOCUSED", v.work.label); assertEquals("CONNECTED", v.digital.label); assertEquals("RARELY", v.mobility.label)
    }

    @Test fun `persona 2 - 35-year-old IT worker`() {
        val a = InterviewAnswers(age = 35, visionProblem = VisionProblem.DISTANCE, distanceBlur = true, budget = Budget.MID, redFlagFlashes = false, redFlagOther = false)
        val s = survey("tiredEyes" to listOf("OFTEN"), "neckPain" to listOf("SOMETIMES"), "workHours" to listOf("9"), "workActivities" to listOf("DESKTOP"),
            "screenHoursDay" to listOf("10"), "focusStruggle" to listOf("SOMETIMES"), "driveFrequency" to listOf("WEEKLY"),
            "glareOncoming" to listOf("NEVER"), "lowLightDriving" to listOf("NEVER"), "outdoorHours" to listOf("1"))
        val (p, v, _) = persona(a, s, QuickEstimate(eye(-2.0), eye(-2.0)))
        assertEquals("anti_fatigue", p.type!!.id)
        assertEquals("You are 35 and your eyes get tired after 10 h of screens; the small boost at the bottom eases phone-to-far switching.", p.type!!.why.single().en)
        assertTrue(p.type!!.why.single().hi.contains("35") && p.type!!.why.single().hi.contains("10"))
        assertEquals("smartlife", p.design!!.id)
        assertEquals(listOf("uvprotect", "blueguard"), p.materials.map { it.id })
        assertEquals("dv_platinum", p.coating!!.id)
        assertEquals("office", p.secondPairs.first().id)
        assertEquals("You work 9 h at a screen and get neck pain; an office pair keeps the screen sharp without tilting your head.", p.secondPairs.first().why.single().en)
        assertEquals("DIGITAL_NATIVE", v.digital.label); assertEquals("WEEKLY", v.mobility.label); assertFalse(v.nightGlareSensitive)
    }

    @Test fun `persona 3 - 52-year-old shopkeeper who drives at night`() {
        val a = InterviewAnswers(age = 52, visionProblem = VisionProblem.BOTH, distanceBlur = true, nearBlur = true, budget = Budget.MID, redFlagFlashes = false, redFlagOther = false)
        val s = survey("workHours" to listOf("10"), "workActivities" to listOf("MEETINGS", "READING_WRITING"), "screenHoursDay" to listOf("2"),
            "driveFrequency" to listOf("DAILY"), "glareOncoming" to listOf("OFTEN"), "lowLightDriving" to listOf("SOMETIMES"),
            "outdoorHours" to listOf("1"), "firstProgressive" to listOf("NO"))
        val (p, v, _) = persona(a, s, QuickEstimate(eye(-1.0), eye(-0.75), addD = 1.75))
        assertEquals("progressive", p.type!!.id)
        assertEquals("You need +1.75 D extra for near; a progressive gives far, screen and reading in one pair.", p.type!!.why.single().en)
        assertEquals("drivesafe", p.design!!.id)
        assertTrue(p.design!!.reasons.map { it.code }.containsAll(listOf("DRIVES", "GLARE", "LOW_LIGHT")))
        assertEquals("dv_drivesafe", p.coating!!.id)
        assertFalse("never PhotoFusion X for night glare", p.ids.contains("photofusion_x"))
        assertEquals(listOf("sun_polarised"), p.secondPairs.map { it.id })
        assertEquals("DAILY", v.mobility.label); assertTrue(v.nightGlareSensitive)
    }

    @Test fun `persona 4 - 60-year-old farmer`() {
        val a = InterviewAnswers(age = 60, visionProblem = VisionProblem.NEAR, nearBlur = true, budget = Budget.LOW, redFlagFlashes = false, redFlagOther = false)
        val s = survey("workHours" to listOf("8"), "workActivities" to listOf("FIELD_FARM"), "indoorOutdoor" to listOf("OUTDOOR"), "screenHoursDay" to listOf("1"),
            "driveFrequency" to listOf("NEVER"), "outdoorHours" to listOf("8"), "leisureActivities" to listOf("FARMING_FIELD"),
            "onePair" to listOf("YES"), "firstProgressive" to listOf("YES"))
        val (p, v, _) = persona(a, s, QuickEstimate(eye(null), eye(null), addD = 2.25))
        assertEquals("progressive", p.type!!.id)
        assertEquals("light2", p.design!!.id)
        assertEquals(listOf("uvprotect", "photofusion_x"), p.materials.map { it.id })
        assertEquals("You are outdoors 8 h a day; one pair that darkens in sun and clears indoors.", p.materials[1].why.single().en)
        assertEquals("dv_chrome", p.coating!!.id)
        assertEquals(listOf("sun_polarised"), p.secondPairs.map { it.id })
        assertEquals("DISTANCE_FOCUSED", v.work.label); assertEquals("HIGH", v.leisure.label); assertEquals("LIGHT", v.digital.label)
    }

    @Test fun `persona 5 - 10-year-old child with minus 2_00`() {
        val a = InterviewAnswers(age = 10, visionProblem = VisionProblem.DISTANCE, distanceBlur = true, budget = Budget.MID, redFlagFlashes = false, redFlagOther = false)
        val s = survey("screenHoursDay" to listOf("4"), "outdoorHours" to listOf("1"))
        val (p, _, gate) = persona(a, s, QuickEstimate(eye(-2.0), eye(-2.0)))
        assertTrue(gate.childMyopia); assertEquals(ReportTag.CHILD_MYOPIA, gate.tag)
        assertFalse(p.allowed)
        assertNull(p.type); assertNull(p.design); assertNull(p.coating); assertTrue(p.materials.isEmpty()); assertTrue(p.secondPairs.isEmpty())
        assertEquals("myopia_management", p.referralInfo!!.id)
        assertEquals(ReportTag.CHILD_MYOPIA, p.tag)
    }

    @Test fun `without a survey the interview answers still drive the engine`() {
        val a = InterviewAnswers(age = 33, screenHours = com.drishtisaathi.engine.ScreenHours.H4_PLUS, headacheStrain = true, nightDriving = true,
            occupation = com.drishtisaathi.engine.Occupation.OFFICE_COMPUTER, outdoorWork = false)
        val i = ProfileInputs.from(a, SurveyAnswers(), cfg)
        assertEquals(6.0, i.screenHours!!, 0.0); assertEquals(Freq.SOMETIMES, i.tiredEyes); assertEquals(DriveFrequency.WEEKLY, i.drive)
        assertEquals(7.0, i.deskHours!!, 0.0); assertEquals(setOf("DESKTOP"), i.workActivities)
        assertEquals("anti_fatigue", engine.plan(i, ok, words).type!!.id)
    }
}
