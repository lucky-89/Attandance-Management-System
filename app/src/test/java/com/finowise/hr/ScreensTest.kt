package com.finowise.hr

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.finowise.hr.core.*
import com.finowise.hr.core.incentive.IncentiveConfig
import com.finowise.hr.core.incentive.IncentivePerson
import com.finowise.hr.core.incentive.IncentiveSheets
import com.finowise.hr.core.incentive.SheetEdit
import com.finowise.hr.core.appraisal.*
import com.finowise.hr.core.model.*
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * Drives the real app on the JVM with a realistic firm's data and saves a
 * screenshot of each main screen to app/build/screens/. A crash, a missing
 * route or a broken screen fails the test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreensTest {
    init { TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata")) }
    val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(object : org.junit.rules.ExternalResource() { override fun before() = seed() }).around(rule)

    private lateinit var adminId: String
    private lateinit var raviId: String

    private fun seed() {
        val zone = ApplicationProvider.getApplicationContext<HrApp>().service.zone
        var at = LocalDateTime.now(zone).withHour(9).withMinute(0)
        val svc = HrService(MemoryPersistence(), { at.atZone(zone).toInstant() }, zone)
        val today = LocalDate.now(zone)
        fun set(d: LocalDate, h: Int, m: Int) { at = d.atTime(h, m) }
        set(today.minusDays(40), 9, 0)
        svc.setupFirm("Freedom LifeX", "Asha Verma", "asha@freedomlifex.in", "secret123")
        adminId = svc.data.team.first().id
        svc.saveConfig(adminId, svc.data.hrConfig.copy(day = DayCfg(start = "09:30", graceMinutes = "10"),
            holidays = listOf(Holiday(today.minusDays(12).toString(), "Guru Nanak Jayanti")),
            branches = listOf("Varanasi", "Lucknow"), departments = listOf("Sales", "Operations"),
            location = LocationCfg(required = false)))
        fun add(name: String, role: String, branch: String, dept: String, ctc: Double, mgr: String = adminId) =
            svc.saveEmployee(adminId, Employee(id = "", name = name, email = name.substringBefore(' ').lowercase() + "@freedomlifex.in",
                employeeId = "EMP" + (100 + svc.data.team.size), role = role, designation = role, branch = branch, department = dept,
                parentUserId = mgr, ctc = ctc), "pass123").id!!
        val bina = add("Bina Singh", "Branch Head", "Varanasi", "Sales", 900000.0)
        raviId = add("Ravi Kumar", "Executive", "Varanasi", "Sales", 480000.0, bina)
        val meera = add("Meera Joshi", "Executive", "Lucknow", "Operations", 420000.0)
        val arjun = add("Arjun Patel", "Manager", "Lucknow", "Sales", 650000.0)
        val gps = LocFix(true, "captured", 25.317645, 82.973914, 14)
        // Three weeks of punches.
        for (back in 21 downTo 1) {
            val d = today.minusDays(back.toLong())
            if (d.dayOfWeek == DayOfWeek.SUNDAY || svc.rules(adminId).holidayOn(d.toString()) != null) continue
            listOf(raviId to (9 to 20 + back % 5), meera to (9 to 35 + back % 7), arjun to (9 to 5), bina to (9 to 15)).forEach { (u, t) ->
                if (u == meera && back == 3) return@forEach // a missed day
                set(d, t.first, t.second); svc.punchIn(u, gps)
                set(d, if (u == meera && back == 5) 13 else 18, 10 + back % 20); svc.punchOut(u, gps)
            }
        }
        // Ravi worked last Sunday: an extra day, comp-off waiting on the branch head.
        val sun = today.minusDays(((today.dayOfWeek.value % 7) + 0).toLong().coerceAtLeast(1))
        if (sun.dayOfWeek == DayOfWeek.SUNDAY) { set(sun, 10, 0); svc.punchIn(raviId, gps); set(sun, 19, 30); svc.punchOut(raviId, gps) }
        // Leave: one approved last week, one pending next week.
        set(today.minusDays(15), 10, 0)
        val past = svc.submitLeave(meera, LeaveForm("Sick Leave", today.minusDays(9).toString(), today.minusDays(9).toString(), "Full Day", CAT_PAID, "Fever", "No"))
        past.id?.let { svc.decideLeave(adminId, it, true, "Get well soon") }
        set(today, 9, 0)
        svc.submitLeave(raviId, LeaveForm("Casual Leave", today.plusDays(3).toString(), today.plusDays(4).toString(), "Full Day", CAT_PAID,
            "Sister's wedding", "Yes", arjun, "arjun@freedomlifex.in", listOf("Follow up Sharma SIP", "Send NJ statements", "Call new leads")))
        // A regularization waiting on HR.
        set(today.minusDays(4), 19, 0)
        svc.submitRegularization(meera, today.minusDays(5).toString(), listOf(Period("13:10", "18:00")), "Client visit at Hazratganj")
        // Today Ravi and Bina are in.
        if (today.dayOfWeek != DayOfWeek.SUNDAY) { set(today, 9, 12); svc.punchIn(raviId, gps); set(today, 9, 41); svc.punchIn(bina, gps) }
        // Incentive: a sales sheet for Ravi this month.
        val month = today.toString().take(7)
        val savedCfg = IncentiveConfig.setPointValue(IncentiveConfig.setSalesDepartments(null, "Sales"), "50")
        svc.updateModules(adminId, "Incentive Grid", "incentiveConfig") { it.copy(incentiveConfig = savedCfg) }
        val cfg = IncentiveConfig.merge(savedCfg)
        val ravi = svc.data.team.first { it.id == raviId }
        val person = IncentivePerson(ravi.id, ravi.name, ravi.role, ravi.department, ravi.branch, ravi.ctc!!)
        var sheet: JsonObject = IncentiveSheets.blankSheet(cfg, person, month, adminId, at.atZone(zone).toInstant(), "INC-DEMO1")
        fun ed(e: SheetEdit) { sheet = (e as SheetEdit.Applied).sheet }
        listOf(Triple("Equity", 1800000, "Self"), Triple("Hybrid", 950000, "Company"), Triple("Debt", 400000, "Self")).forEachIndexed { i, (sub, amt, src) ->
            ed(IncentiveSheets.addRow(sheet, "netSalesRows"))
            ed(IncentiveSheets.setCell(sheet, "netSalesRows", i, "cat", "Mutual Fund")); ed(IncentiveSheets.setCell(sheet, "netSalesRows", i, "sub", sub))
            ed(IncentiveSheets.setCell(sheet, "netSalesRows", i, "source", src)); ed(IncentiveSheets.setCell(sheet, "netSalesRows", i, "meeting", "Independent meeting"))
            ed(IncentiveSheets.setCell(sheet, "netSalesRows", i, "amount", amt.toString()))
        }
        ed(IncentiveSheets.addRow(sheet, "sipRows"))
        ed(IncentiveSheets.setCell(sheet, "sipRows", 0, "cat", "Mutual Fund")); ed(IncentiveSheets.setCell(sheet, "sipRows", 0, "sub", "Equity"))
        ed(IncentiveSheets.setCell(sheet, "sipRows", 0, "source", "Self")); ed(IncentiveSheets.setCell(sheet, "sipRows", 0, "meeting", "Independent meeting"))
        ed(IncentiveSheets.setCell(sheet, "sipRows", 0, "amount", "125000"))
        ed(IncentiveSheets.addRow(sheet, "meetingRows")); ed(IncentiveSheets.setCell(sheet, "meetingRows", 0, "label", "Client meetings"))
        ed(IncentiveSheets.setCell(sheet, "meetingRows", 0, "n", "38"))
        svc.updateModules(adminId, "Incentive Sheet Opened", "INC-DEMO1") { it.copy(hrIncentive = listOf(sheet)) }
        // Appraisal: role mapping, growth, and Ravi's ratings.
        val viewer = Viewer(adminId, isAdmin = true)
        var s: AppraisalSettings? = null
        s = Appraisals.setRoleMap(s, viewer, RoleMapKind.DEPARTMENT, "Sales", "clientsRM").getOrNull()
        s = Appraisals.setRoleMap(s, viewer, RoleMapKind.DEPARTMENT, "Operations", "operations").getOrNull()
        val year = AppraisalEngine.currentAppraisalYear(Cycle.FY, today).toString()
        s = Appraisals.setGrowth(s, viewer, year, GrowthField.ABS, 18.0).getOrNull()
        s = Appraisals.setGrowth(s, viewer, year, GrowthField.SIP, 22.0).getOrNull()
        s = Appraisals.setGrowth(s, viewer, year, GrowthField.LUMP, 9.0).getOrNull()
        val ap = AppraisalPerson(ravi.id, ravi.name, ravi.role, ravi.department, ravi.branch, ravi.ctc!!, ravi.parentUserId)
        var rec = Appraisals.blank(ravi.id, year, adminId, "APR-DEMO1")
        listOf(RatingField.CURIOSITY to 80.0, RatingField.COMPETENCE to 75.0, RatingField.COMMITMENT to 90.0, RatingField.CREATIVITY to 70.0).forEach { (f, v) ->
            rec = Appraisals.setRating(rec, viewer, ap, f, v).getOrNull()!! }
        listOf(92.0, 110.0, 64.0).forEachIndexed { i, v -> rec = Appraisals.setKpi(rec, viewer, ap, i, v).getOrNull()!! }
        val recJson = AppraisalJson.encodeToJsonElement(AppraisalRecord.serializer(), rec).jsonObject
        val sJson = AppraisalJson.encodeToJsonElement(AppraisalSettings.serializer(), s!!).jsonObject
        svc.updateModules(adminId, "Appraisal Seeded", rec.id) { it.copy(hrAppraisals = listOf(recJson), appraisalConfig = sJson) }

        val app = ApplicationProvider.getApplicationContext<HrApp>()
        check(app.service.importJson(null, svc.exportJson()).ok)
        app.session.userId = adminId
    }

    private fun shot(name: String) { rule.waitForIdle(); rule.onRoot().captureRoboImage("build/screens/$name.png") }
    private fun tap(text: String) { rule.onAllNodesWithText(text, substring = false).onFirst().performScrollTo().performClick(); rule.waitForIdle() }
    private fun tapTab(text: String) { rule.onAllNodesWithText(text).onLast().performClick(); rule.waitForIdle() }
    private fun back() { rule.onNodeWithContentDescription("Back").performClick(); rule.waitForIdle() }

    @Test fun mainScreens() {
        shot("01-dashboard-admin")
        tapTab("Attendance"); shot("02-attendance")
        tapTab("Leave"); shot("03-leave")
        tapTab("Approvals"); shot("04-approvals")
        tapTab("More"); shot("05-more")
        tap("Month board"); shot("06-month-board"); back()
        tap("Remaining leave balance"); shot("07-leave-balances"); back()
        tap("Employees"); shot("08-employees"); back()
        tap("HR Settings"); shot("09-hr-settings"); back()
        tap("Incentive management"); shot("10-incentive")
        tap("Ravi Kumar"); shot("11-incentive-sheet")
        rule.onAllNodesWithText("Total incentive", substring = true).onLast().performScrollTo(); shot("11b-incentive-result"); back(); back()
        tap("Appraisal management"); shot("12-appraisal")
        tap("Ravi Kumar"); shot("13-appraisal-edit")
        rule.onNodeWithText("New annual CTC", substring = true).performScrollTo(); shot("13b-appraisal-result"); back(); back()
        tapTab("Home")
        rule.onNodeWithText("+ Leave Request").performScrollTo().performClick(); rule.waitForIdle(); shot("14-leave-request"); back()
        tapTab("More"); tap("Sign out"); shot("15-sign-in")
    }
}
