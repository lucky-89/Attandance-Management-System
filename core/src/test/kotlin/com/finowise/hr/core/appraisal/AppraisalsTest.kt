package com.finowise.hr.core.appraisal

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BOSS's appraisal-boss.js / appraisal-growth.js behaviour, on the standalone integration layer. */
class AppraisalsTest {
    private val manager = AppraisalPerson("USR-APR1", "ZZ Manager", role = "Relationship Manager", ctc = 900000.0)
    private val appraisee = AppraisalPerson("USR-APR0", "ZZ Appraisee", role = "Relationship  manager ",
        department = "Sales", ctc = 650000.0, reportingManagerId = manager.id)
    private val stranger = AppraisalPerson("USR-APR2", "ZZ Stranger", role = "Ops", ctc = 400000.0)
    private val people = listOf(manager, appraisee, stranger)
    private val admin = Viewer("USR-ADMIN", isAdmin = true)
    private val asManager = Viewer(manager.id)
    private val asSelf = Viewer(appraisee.id)
    private val asStranger = Viewer(stranger.id)

    private val settings = AppraisalSettings(
        roleMap = mapOf("role:relationship manager" to "clientsRM"),
        growth = mapOf("2026" to GrowthEntry(JsonPrimitive(18), JsonPrimitive(22), JsonPrimitive(15))),
    )

    private fun <T> AppraisalOutcome<T>.ok(): T = (this as? AppraisalOutcome.Ok<T>)?.value
        ?: throw AssertionError("expected Ok, got $this")

    private fun rated(): AppraisalRecord {
        var rec = Appraisals.open(emptyList(), asManager, appraisee, "2026", id = "APPR-TEST1", now = "2026-05-01T00:00:00Z").ok()
        rec = Appraisals.setRating(rec, admin, appraisee, RatingField.CURIOSITY, 85.0).ok()
        rec = Appraisals.setRating(rec, admin, appraisee, RatingField.COMPETENCE, 82.0).ok()
        rec = Appraisals.setRating(rec, admin, appraisee, RatingField.COMMITMENT, 90.0).ok()
        rec = Appraisals.setRating(rec, admin, appraisee, RatingField.CREATIVITY, 70.0).ok()
        rec = Appraisals.setKpi(rec, asManager, appraisee, 0, AppraisalEngine.achievementPct(240.0, 300.0)).ok()
        rec = Appraisals.setKpi(rec, asManager, appraisee, 1, 80.0).ok()
        rec = Appraisals.setKpi(rec, asManager, appraisee, 2, 75.0).ok()
        return rec
    }

    @Test
    fun handWorkedCaseThroughTheIntegrationLayer() {
        assertEquals("clientsRM", Appraisals.roleKeyOf(appraisee, settings))
        assertEquals(RoleSource.ROLE, Appraisals.roleSourceOf(appraisee, settings))
        val out = Appraisals.compute(rated(), appraisee, settings)
        assertEquals(40.0, out.breakdown.contribution)
        assertEquals(82.15, Math.round(out.score * 100) / 100.0)
        assertEquals(18.25, out.pool)
        assertEquals(97450.0, out.amount)
        assertEquals(747450.0, out.newSalary)
    }

    @Test
    fun managerWorksKpisButNotTheFourCs() {
        val rec = Appraisals.open(emptyList(), asManager, appraisee, "2026").ok()
        for (f in RatingField.entries.filter { it.adminOnly }) {
            val r = Appraisals.setRating(rec, asManager, appraisee, f, 85.0)
            assertIs<AppraisalOutcome.Denied>(r)
            assertTrue("administrator" in r.reason)
            assertFalse(Appraisals.mayEdit(asManager, appraisee, f))
            assertTrue(Appraisals.mayEdit(admin, appraisee, f))
        }
        assertEquals(JsonPrimitive(80), Appraisals.setKpi(rec, asManager, appraisee, 0, 80.0).ok().rating.kpi[0])
        assertEquals(JsonPrimitive(85), Appraisals.setRating(rec, admin, appraisee, RatingField.CURIOSITY, 85.0).ok().rating.curiosity)
        // BOSS lets the reporting manager set the score override (it is not in APPR_ADMIN_ONLY)
        assertTrue(Appraisals.mayEdit(asManager, appraisee, RatingField.SCORE_OVERRIDE))
        assertFalse(Appraisals.mayJudge(asManager)); assertTrue(Appraisals.mayJudge(admin))
    }

    @Test
    fun seeingAndRatingFollowTheReportingLine() {
        assertTrue(Appraisals.maySee(asManager, appraisee.id, people))
        assertTrue(Appraisals.mayRate(asManager, appraisee))
        assertTrue(Appraisals.maySee(asSelf, appraisee.id, people))
        assertFalse(Appraisals.mayRate(asSelf, appraisee))
        assertFalse(Appraisals.maySee(asSelf, manager.id, people))
        assertFalse(Appraisals.maySee(asStranger, appraisee.id, people))
        assertFalse(Appraisals.mayRate(asStranger, appraisee))
        assertTrue(Appraisals.maySee(Viewer("hr", isHrStaff = true), appraisee.id, people))
        assertFalse(Appraisals.mayRate(Viewer("hr", isHrStaff = true), appraisee))
        assertTrue(Appraisals.mayRate(admin, appraisee))
        // transitive: a manager's manager sees, but does not rate
        val boss = AppraisalPerson("BOSS")
        val chain = people.map { if (it.id == manager.id) it.copy(reportingManagerId = "BOSS") else it } + boss
        assertTrue(Appraisals.maySee(Viewer("BOSS"), appraisee.id, chain))
        assertFalse(Appraisals.mayRate(Viewer("BOSS"), appraisee))
        assertEquals(listOf(manager.id, appraisee.id), Appraisals.visiblePeople(asManager, people).map { it.id })
        assertIs<AppraisalOutcome.Denied>(Appraisals.open(emptyList(), asStranger, appraisee, "2026"))
        assertIs<AppraisalOutcome.Denied>(Appraisals.setKpi(rated(), asStranger, appraisee, 0, 50.0))
    }

    @Test
    fun aFinalisedAppraisalIsNotRestatedByLaterChanges() {
        val fin = Appraisals.finalise(rated(), asManager, appraisee, settings, now = "2027-04-02T00:00:00Z").ok()
        assertEquals(AppraisalStatus.FINALISED, fin.status)
        assertEquals(747450.0, Appraisals.compute(fin, appraisee, settings).newSalary)
        val boom = Appraisals.setGrowth(settings, admin, "2026", GrowthField.ABS, 90.0).ok()
            .let { Appraisals.setGrowth(it, admin, "2026", GrowthField.SIP, 90.0).ok() }
        assertEquals(747450.0, Appraisals.compute(fin, appraisee.copy(ctc = 2_000_000.0), boom).newSalary)
        assertTrue(Appraisals.guard(fin, admin, appraisee)!!.contains("finalised"))
        assertIs<AppraisalOutcome.Denied>(Appraisals.setKpi(fin, admin, appraisee, 1, 100.0))
        assertIs<AppraisalOutcome.Denied>(Appraisals.reopen(fin, asManager))
        val reopened = Appraisals.reopen(fin, admin).ok()
        assertEquals(AppraisalStatus.DRAFT, reopened.status)
        assertTrue(Appraisals.compute(reopened, appraisee, boom).newSalary > 747450.0)
    }

    @Test
    fun finaliseNeedsRoleSalaryAndAsksAboutZeroGrowth() {
        val rec = rated()
        assertIs<AppraisalOutcome.Denied>(Appraisals.finalise(rec, asManager, appraisee.copy(ctc = 0.0), settings))
        val noGrowth = settings.copy(growth = null)
        assertIs<AppraisalOutcome.NeedsConfirmation>(Appraisals.finalise(rec, asManager, appraisee, noGrowth))
        val fin = Appraisals.finalise(rec, asManager, appraisee, noGrowth, confirmZeroGrowth = true).ok()
        assertEquals(650000.0, fin.snapshot!!.newSalary)
        val noRoles = AppraisalSettings(roles = emptyMap())
        // roles merge over the defaults, so an empty override still leaves the defaults
        assertEquals("operations", Appraisals.defaultRoleKey(noRoles))
    }

    @Test
    fun roleKeyResolutionOrder() {
        val fresh = AppraisalPerson("NEW", role = "Intern")
        assertEquals("operations", Appraisals.roleKeyOf(fresh, null))
        assertEquals(RoleSource.DEFAULT, Appraisals.roleSourceOf(fresh, null))
        val s = AppraisalSettings(defaultRoleKey = "partnersRM", roleMap = mapOf("role:intern" to "clientsRM", "dept:ops desk" to "operations"))
        assertEquals("clientsRM", Appraisals.roleKeyOf(fresh, s))
        assertEquals("partnersRM", Appraisals.roleKeyOf(AppraisalPerson("X", role = "Other"), s))
        assertEquals("operations", Appraisals.roleKeyOf(AppraisalPerson("X", role = "Other", department = " Ops   Desk"), s))
        assertEquals(RoleSource.DEPARTMENT, Appraisals.roleSourceOf(AppraisalPerson("X", role = "Other", department = "ops desk"), s))
        assertEquals("partnersRM", Appraisals.roleKeyOf(fresh.copy(appraisalRoleKey = "partnersRM"), s))
        assertEquals("operations", Appraisals.defaultRoleKey(AppraisalSettings(defaultRoleKey = "nope")))
        val mapped = Appraisals.setRoleMap(null, admin, RoleMapKind.ROLE, "Senior  RM", "clientsRM").ok()
        assertEquals(mapOf("role:senior rm" to "clientsRM"), mapped.roleMap)
        assertEquals(emptyMap(), Appraisals.setRoleMap(mapped, admin, RoleMapKind.ROLE, "senior rm", null).ok().roleMap)
        assertIs<AppraisalOutcome.Denied>(Appraisals.setRoleMap(null, asManager, RoleMapKind.ROLE, "x", "clientsRM"))
    }

    @Test
    fun growthHelpersAndPermissions() {
        assertEquals(18.0, Appraisals.growthPct(1.0e7, 1.18e7))
        assertEquals(22.0, Appraisals.growthPct(40000.0, 48800.0))
        assertEquals(15.0, Appraisals.growthPct(1_000_000.0, 1_150_000.0))
        assertNull(Appraisals.growthPct(0.0, 5.0))
        assertIs<AppraisalOutcome.Denied>(Appraisals.setGrowth(null, asManager, "2026", GrowthField.ABS, 10.0))
        val s = Appraisals.setGrowth(null, admin, "2026", GrowthField.LUMP, 12.5).ok()
        assertEquals(GrowthEntry(JsonPrimitive(""), JsonPrimitive(""), JsonPrimitive(12.5)), Appraisals.growthFor(s, 2026))
        assertEquals(JsonPrimitive(""), Appraisals.setGrowth(s, admin, "2026", GrowthField.LUMP, null).ok().growth!!["2026"]!!.lump)
        val range = Appraisals.yearRange(2026, Cycle.FY)
        assertEquals("2026-04-01" to "2027-03-31", range.from.toString() to range.to.toString())
    }

    @Test
    fun readinessNamesWhatIsMissing() {
        val blank = Appraisals.readiness(appraisee, "2025", settings)
        assertTrue(blank.missing.containsAll(listOf("Company growth % (absolute)", "Curiosity", "Meetings Target Achieved")))
        val full = Appraisals.readiness(appraisee, "2026", settings, rated())
        assertEquals(emptyList(), full.missing)
        assertEquals("Clients RM", full.role!!.label)
    }

    @Test
    fun recordAndSettingsRoundTripBossJson() {
        val bossRecord = """{"id":"APPR-AB12C3456","userId":"USR-APR0","year":"2026","rating":{"curiosity":85,"competence":"","commitment":"90","creativity":70,"kpi":[null,80,""],"scoreOverride":null,"kpiFrom":{"0":"BOSS"}},"status":"Draft","snapshot":null,"createdAt":"2026-05-01T00:00:00.000Z","createdBy":"USR-APR1","updatedAt":"2026-05-02T00:00:00.000Z"}"""
        val rec = AppraisalJson.decodeFromString(AppraisalRecord.serializer(), bossRecord)
        assertEquals(JsonNull, rec.rating.kpi[0])
        assertEquals(bossRecord, AppraisalJson.encodeToString(AppraisalRecord.serializer(), rec))

        val blank = Appraisals.blank("U1", "2026", "M1", id = "APPR-X", createdAt = "t")
        assertEquals("""{"id":"APPR-X","userId":"U1","year":"2026","rating":{"curiosity":"","competence":"","commitment":"","creativity":"","kpi":[],"scoreOverride":null},"status":"Draft","snapshot":null,"createdAt":"t","createdBy":"M1"}""",
            AppraisalJson.encodeToString(AppraisalRecord.serializer(), blank))

        val fin = Appraisals.finalise(rated(), asManager, appraisee, settings, now = "n").ok()
        val json = AppraisalJson.encodeToString(AppraisalRecord.serializer(), fin)
        assertTrue("\"newSalary\":747450," in json, json)
        assertEquals(fin, AppraisalJson.decodeFromString(AppraisalRecord.serializer(), json))

        val bossConfig = """{"roleMap":{"role:relationship manager":"clientsRM"},"growth":{"2026":{"abs":18,"sip":22,"lump":""}},"defaultRoleKey":"operations","cWeights":{"curiosity":10}}"""
        val s = AppraisalJson.decodeFromString(AppraisalSettings.serializer(), bossConfig)
        assertEquals(10.0, Appraisals.effectiveConfig(s).cWeights.curiosity)
        assertEquals(20.0, Appraisals.effectiveConfig(s).cWeights.competence)
        val again = AppraisalJson.decodeFromString(AppraisalSettings.serializer(), AppraisalJson.encodeToString(AppraisalSettings.serializer(), s))
        assertEquals(s, again)
        assertEquals(AppraisalJson.parseToJsonElement(bossConfig), AppraisalJson.encodeToJsonElement(AppraisalSettings.serializer(), s))
    }
}
