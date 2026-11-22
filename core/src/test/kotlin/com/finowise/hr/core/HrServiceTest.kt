package com.finowise.hr.core

import com.finowise.hr.core.model.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.*

class HrServiceTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private var nowLocal = LocalDateTime.parse("2026-11-16T09:00:00") // a Monday
    private val clock = { nowLocal.atZone(zone).toInstant() }
    private lateinit var svc: HrService
    private lateinit var admin: String
    private lateinit var emp: String
    private lateinit var head: String
    private val gps = LocFix(true, "captured", 25.3176, 82.9739, 12)
    private val noGps = LocFix(false, "denied", why = "Location permission was refused.")

    private fun at(s: String) { nowLocal = LocalDateTime.parse(s) }

    @BeforeTest fun setUp() {
        svc = HrService(MemoryPersistence(), clock, zone)
        assertTrue(svc.setupFirm("Freedom LifeX", "Asha Admin", "admin@firm.in", "secret123").ok)
        admin = svc.data.team.first().id
        val e = svc.saveEmployee(admin, Employee(id = "", name = "Ravi Kumar", email = "ravi@firm.in", role = "Executive", branch = "Varanasi", parentUserId = admin), "pass123")
        assertTrue(e.ok, e.message); emp = e.id!!
        val h = svc.saveEmployee(admin, Employee(id = "", name = "Bina Head", email = "bina@firm.in", role = "Branch Head", branch = "Varanasi"), "pass123")
        head = h.id!!
    }

    @Test fun signInChecksPassword() {
        assertEquals(emp, svc.signIn("RAVI@firm.in", "pass123").id)
        assertFalse(svc.signIn("ravi@firm.in", "nope").ok)
    }

    @Test fun punchWindowAndOncePerDay() {
        at("2026-11-16T08:30:00")
        val early = svc.punchIn(emp, gps)
        assertFalse(early.ok); assertTrue(early.message.contains("Punch In opens at 08:45"))
        at("2026-11-16T09:05:00")
        assertTrue(svc.punchIn(emp, gps).ok)
        at("2026-11-16T09:06:00")
        assertTrue(svc.punchIn(emp, gps).message.contains("once a day"))
        at("2026-11-16T18:05:00")
        assertTrue(svc.punchOut(emp, gps).ok)
        val r = svc.rules(emp)
        assertEquals(540.0, r.workedMinutes(r.recordOf(emp, "2026-11-16")))
        assertEquals(100.0, r.dayPct(emp, "2026-11-16"))
        // Punch out again replaces the earlier one, which is kept.
        at("2026-11-16T19:00:00")
        assertTrue(svc.punchOut(emp, gps).ok)
        val s = svc.data.hrAttendance.single().sessions.single()
        assertEquals(1, s.outHistory.size)
        at("2026-11-16T20:50:00")
        assertFalse(svc.punchOut(emp, gps).ok)
    }

    @Test fun locationRequiredRefusesAndOptionalRecordsWhy() {
        at("2026-11-16T09:05:00")
        assertFalse(svc.punchIn(emp, noGps).ok)
        assertTrue(svc.saveConfig(admin, svc.data.hrConfig.copy(location = LocationCfg(required = false))).ok)
        val ok = svc.punchIn(emp, noGps)
        assertTrue(ok.ok); assertTrue(ok.message.contains("not recorded"))
        assertEquals("denied", svc.data.hrAttendance.single().sessions.single().inLoc!!.status)
        assertNull(svc.data.hrAttendance.single().sessions.single().inLoc!!.lat)
    }

    @Test fun halfDayIsHalfAndOverlapCountedOnce() {
        at("2026-11-16T09:00:00"); svc.punchIn(emp, gps)
        at("2026-11-16T13:30:00"); svc.punchOut(emp, gps)
        val r = svc.rules(emp)
        assertEquals(50.0, r.dayPct(emp, "2026-11-16"))
        // A regularized period overlapping the punches only adds the uncovered part.
        assertEquals(270.0 + 60.0, r.eligibleMinutes(emp, "2026-11-16", extra = listOf(Period("13:00", "14:30"))))
    }

    @Test fun sundayClosedWithoutCompOff() {
        at("2026-11-22T10:00:00") // Sunday
        svc.saveConfig(admin, svc.data.hrConfig.copy(annualLeave = AnnualLeaveCfg(compOffEnabled = false)))
        assertTrue(svc.punchIn(emp, gps).message.contains("Sundays"))
    }

    private fun leave(from: String, to: String, cat: String, dur: String = "Full Day") =
        svc.submitLeave(emp, LeaveForm("Casual Leave", from, to, dur, cat, "family", "No"))

    @Test fun leaveGoesToBranchHeadAndBalanceComesTo7() {
        at("2026-11-02T09:00:00")
        val a = leave("2026-11-03", "2026-11-06", CAT_PAID) // Tue–Fri: 4 days
        assertTrue(a.ok, a.message)
        val row = svc.data.hrLeave.single { it.id == a.id }
        assertEquals(head, row.approverId) // Branch Head for Varanasi
        assertFalse(svc.decideLeave(emp, a.id!!, true, "").ok) // not theirs
        assertTrue(svc.decideLeave(head, a.id, true, "").ok)
        val b = leave("2026-11-09", "2026-11-09", CAT_PAID, "Half Day (First Half)")
        val c = leave("2026-11-10", "2026-11-10", CAT_PAID, "Half Day (Second Half)")
        svc.decideLeave(head, b.id!!, true, ""); svc.decideLeave(head, c.id!!, true, "")
        val bal = svc.rules(emp).balances(emp, "2026")
        assertEquals(5.0, bal.paidUsed)
        assertEquals(7.0, bal.paidLeft)
        // Decline needs a reason
        val d = leave("2026-11-11", "2026-11-11", CAT_PAID)
        assertFalse(svc.decideLeave(head, d.id!!, false, "").ok)
        assertTrue(svc.decideLeave(head, d.id, false, "Audit week").ok)
        assertEquals(7.0, svc.rules(emp).balances(emp, "2026").paidLeft)
    }

    @Test fun spanAcrossSundayChargesWorkingDaysAndOverlapRefused() {
        at("2026-11-02T09:00:00")
        val a = leave("2026-11-07", "2026-11-09", CAT_PAID) // Sat, Sun, Mon
        assertTrue(a.ok)
        val r = svc.rules(emp)
        assertEquals(2.0, r.chargeDays(svc.data.hrLeave.single()))
        assertFalse(leave("2026-11-09", "2026-11-10", CAT_UNPAID).ok)
    }

    @Test fun morePaidThanFreeIsRefused() {
        at("2026-01-05T09:00:00")
        val r = leave("2026-01-05", "2026-01-20", CAT_PAID) // 14 working days
        assertFalse(r.ok); assertTrue(r.message.contains("Unpaid"))
        assertTrue(leave("2026-01-05", "2026-01-20", CAT_UNPAID).ok)
    }

    @Test fun compOffEarnedVerifiedAndUsed() {
        // Sunday 9h → 1 day; Sunday 5h → ½; holiday 9h → 1
        svc.saveConfig(admin, svc.data.hrConfig.copy(holidays = listOf(Holiday("2026-11-04", "Diwali"))))
        fun work(date: String, inT: String, outT: String) {
            at("${date}T$inT"); assertTrue(svc.punchIn(emp, gps).ok, "in $date")
            at("${date}T$outT"); assertTrue(svc.punchOut(emp, gps).ok, "out $date")
        }
        work("2026-11-01", "09:00:00", "18:00:00")
        work("2026-11-08", "09:00:00", "14:00:00")
        work("2026-11-04", "09:00:00", "18:30:00")
        assertEquals(3, svc.data.hrCompOff.size)
        assertEquals(0.0, svc.rules(emp).balances(emp, "2026").compEarned)
        // The person cannot approve their own
        val first = svc.data.hrCompOff.first()
        assertFalse(svc.decideCompOff(emp, first.id, true, "").ok)
        svc.data.hrCompOff.forEach { assertTrue(svc.decideCompOff(admin, it.id, true, "").ok) }
        assertEquals(2.5, svc.rules(emp).balances(emp, "2026").compEarned)
        // A decided record cannot change (guard)
        val decided = svc.data.hrCompOff.first()
        assertNotNull(Guards.compOff(svc.data.hrCompOff, svc.data.hrCompOff.map { if (it.id == decided.id) it.copy(credit = 5.0) else it }))
        at("2026-11-12T09:00:00")
        val a = leave("2026-11-16", "2026-11-16", CAT_COMP)
        val b = leave("2026-11-17", "2026-11-17", CAT_COMP, "Half Day (First Half)")
        svc.decideLeave(head, a.id!!, true, ""); svc.decideLeave(head, b.id!!, true, "")
        val bal = svc.rules(emp).balances(emp, "2026")
        assertEquals(1.0, bal.compLeft)
        assertEquals(12.0, bal.paidLeft)
    }

    @Test fun partialCancellationKeepsRemainders() {
        at("2026-11-02T09:00:00")
        val a = leave("2026-11-03", "2026-11-06", CAT_PAID)
        svc.decideLeave(head, a.id!!, true, "")
        val c = svc.cancelLeave(emp, a.id, listOf("2026-11-04"), "plans changed")
        assertTrue(c.ok, c.message)
        val rows = svc.data.hrLeave
        assertEquals("Partially Cancelled", rows.first { it.id == a.id }.status)
        val rest = rows.filter { it.remainderOf == a.id }
        assertEquals(listOf("2026-11-03" to "2026-11-03", "2026-11-05" to "2026-11-06"), rest.map { it.from to it.to })
        assertEquals(3.0, svc.rules(emp).balances(emp, "2026").paidUsed)
    }

    @Test fun regularizationApprovedRaisesPercent() {
        at("2026-11-16T09:00:00"); svc.punchIn(emp, gps)
        at("2026-11-16T13:30:00"); svc.punchOut(emp, gps)
        at("2026-11-17T10:00:00")
        val r = svc.submitRegularization(emp, "2026-11-16", listOf(Period("13:30", "18:00")), "client visit")
        assertTrue(r.ok, r.message)
        assertEquals(50.0, svc.rules(emp).dayPct(emp, "2026-11-16"))
        assertFalse(svc.decideRegularization(emp, r.id!!, true, "").ok)
        assertTrue(svc.decideRegularization(admin, r.id, true, "").ok)
        assertEquals(100.0, svc.rules(emp).dayPct(emp, "2026-11-16"))
    }

    @Test fun attendanceIsAppendOnly() {
        at("2026-11-16T09:00:00"); svc.punchIn(emp, gps)
        val old = svc.data.hrAttendance
        val tampered = old.map { it.copy(sessions = it.sessions.map { s -> s.copy(`in` = "2026-11-16T02:00:00Z") }) }
        assertNotNull(Guards.attendance(old, tampered))
        val withExtra = old + AttendanceRecord("x", emp, "2026-11-15")
        assertNull(Guards.attendance(old, withExtra))
        assertNotNull(Guards.attendance(withExtra, old)) // a dropped day is refused
    }

    @Test fun permissionsDefaultToOwnAttendance() {
        val r = svc.rules(emp)
        assertTrue(r.moduleOpen("hrattendance"))
        assertFalse(r.moduleOpen("hrincentive"))
        assertFalse(r.isStaff())
        assertEquals(listOf(emp), r.people().map { it.id })
        assertTrue(svc.rules(admin).isHrAdmin())
        assertFalse(svc.saveConfig(emp, svc.data.hrConfig).ok)
    }

    @Test fun backupRoundTrips() {
        at("2026-11-16T09:00:00"); svc.punchIn(emp, gps)
        val json = svc.exportJson()
        val other = HrService(MemoryPersistence(), clock, zone)
        assertTrue(other.importJson(null, json).ok)
        assertEquals(svc.data, other.data)
        assertTrue(json.contains("\"hrAttendance\""))
    }

    @Test fun reminders() {
        at("2026-11-16T09:30:00")
        assertEquals("in", svc.rules(emp).nudgeDue()?.key)
        svc.punchIn(emp, gps)
        assertNull(svc.rules(emp).nudgeDue())
        at("2026-11-16T19:15:00")
        assertEquals("out", svc.rules(emp).nudgeDue()?.key)
    }
}
