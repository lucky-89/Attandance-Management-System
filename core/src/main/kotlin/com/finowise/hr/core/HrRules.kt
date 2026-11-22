package com.finowise.hr.core

import com.finowise.hr.core.model.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/* Shared number/text helpers, matching the web app's output. */
object Fmt {
    /** JavaScript's Math.round: halves go up. */
    fun jsRound(x: Double): Double = floor(x + 0.5)
    fun r2(n: Double): Double = jsRound(n * 100) / 100
    /** Number as JS String(n) prints it: 7 not 7.0, 1.5 as 1.5. */
    fun num(n: Double): String {
        val r = r2(n)
        return if (r == floor(r) && !r.isInfinite()) r.toLong().toString() else r.toString()
    }
    fun daysLabel(n: Double): String {
        if (n == 0.5) return "½ day"
        val s = if (n == floor(n)) n.toLong().toString() else String.format(Locale.US, "%.1f", n)
        return "$s day" + if (n == 1.0) "" else "s"
    }
    fun daysText(n: Double): String = num(n) + " day" + if (n == 1.0) "" else "s"
    fun duration(mins: Double?): String {
        if (mins == null || !mins.isFinite()) return "—"
        val m = max(0.0, jsRound(mins)).toLong()
        return "${m / 60}h ${(m % 60).toString().padStart(2, '0')}m"
    }
    fun ms(ms: Long): String = duration(ms / 60000.0)
    private val dateFmt = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
    fun date(d: String?): String = try { if (d.isNullOrBlank()) "—" else LocalDate.parse(d.take(10)).format(dateFmt) } catch (e: Exception) { "—" }
}

object Grants {
    val ALL = listOf(
        Triple("attendance", "Attendance Management", "Their own punch clock, history and leave."),
        Triple("incentive", "Incentive Management", "The incentive module."),
        Triple("appraisal", "Appraisal Management", "The appraisal module."),
        Triple("staff", "See everybody’s HR records", "Other people’s attendance and leave, not only their own."),
        Triple("admin", "Administer HR", "HR Settings: the working day, leave types, holidays, employees and access."),
    )
    /** Marking your own attendance is not a privilege: it starts on. */
    val DEFAULT_ON = mapOf("attendance" to 1)
}

const val CAT_PAID = "Paid"
const val CAT_UNPAID = "Unpaid"
const val CAT_COMP = "Comp-Off"
val LEAVE_CATEGORIES = listOf(CAT_PAID, CAT_UNPAID, CAT_COMP)
val LEAVE_CATEGORY_LABEL = mapOf(CAT_PAID to "Paid Leave", CAT_UNPAID to "Unpaid Leave", CAT_COMP to "Comp-Off")
val LEAVE_ACTIVE = listOf("Pending", "Approved")
val LEAVE_DURATIONS = listOf("Full Day", "Half Day (First Half)", "Half Day (Second Half)")

data class DayStatus(val code: String, val label: String, val tone: String)
data class PunchRules(val inOk: Boolean, val outOk: Boolean, val inWhy: String, val outWhy: String)
data class DayCredit(val credit: Double, val leave: Double, val worked: Double, val unpaid: Double = 0.0)
data class MonthDays(val days: Double, val full: Int, val leave: Double, val unpaid: Double, val month: String)
data class Approver(val id: String, val why: String)
data class Balances(
    val year: String, val entitled: Double,
    val paidUsed: Double, val paidPending: Double,
    val compEarned: Double, val compCreditPending: Double,
    val compUsed: Double, val compPending: Double,
    val unpaid: Double, val unpaidPending: Double,
) {
    val paidLeft get() = Fmt.r2(entitled - paidUsed)
    val compLeft get() = Fmt.r2(compEarned - compUsed)
    val paidFree get() = Fmt.r2(paidLeft - paidPending)
    val compFree get() = Fmt.r2(compLeft - compPending)
}
data class LedgerEntry(
    val date: String, val what: String, val cat: String, val status: String,
    val paid: Double, val comp: Double, val note: String = "",
    var paidBal: Double = 0.0, var compBal: Double = 0.0,
)
data class AhDay(
    val date: String, val rec: AttendanceRecord?, val bucket: String, val units: Double,
    val chip: String, val tone: String, val remarks: String, val reason: String, val category: String = "",
)
data class MonthCell(val c: String, val title: String)
data class MonthRow(
    val person: Employee, val codes: List<MonthCell>, val present: Int, val absent: Int, val leave: Double,
    val unpaid: Double, val late: Int, val mins: Double, val credit: Double, val pendingLeave: Int,
    val pendingReg: Int, val open: Int, val extraMins: Double, val pct: Double,
)
data class Nudge(val key: String, val title: String, val body: String)
data class AlCfg(val paidPerYear: Double, val compOffEnabled: Boolean, val compOffFullHours: Double, val compOffHalfHours: Double, val compOffApprover: String)

/**
 * Read-only HR rules over one snapshot of the data, as of [now], in [zone].
 * Ported function by function from the BOSS HR section; the comments there
 * explain the why, and the behaviour here is meant to be identical.
 */
class HrRules(val data: HrData, val currentUserId: String?, val now: Instant = Instant.now(), val zone: ZoneId = ZoneId.systemDefault()) {
    val cfg: HrConfig get() = data.hrConfig
    val me: Employee? get() = data.team.find { it.id == currentUserId }

    // ---------- dates ----------
    fun dateKey(instant: Instant): String = instant.atZone(zone).toLocalDate().toString()
    fun dateKey(iso: String?): String = parseInstant(iso)?.let { dateKey(it) } ?: ""
    val today: String get() = dateKey(now)
    fun hhmm(instant: Instant): String = instant.atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
    fun clock(iso: String?): String = parseInstant(iso)?.let { hhmm(it) } ?: "—"
    fun at(date: String, hhmm: String): Long = LocalDate.parse(date).atTime(LocalTime.parse(hhmm)).atZone(zone).toInstant().toEpochMilli()
    fun minutesBetween(a: String?, b: String?): Double {
        val x = parseInstant(a) ?: return 0.0
        val y = parseInstant(b) ?: return 0.0
        return max(0.0, (y.toEpochMilli() - x.toEpochMilli()) / 60000.0)
    }
    fun dow(date: String): DayOfWeek? = try { LocalDate.parse(date).dayOfWeek } catch (e: Exception) { null }
    fun datesBack(n: Int, from: String = today): List<String> {
        var d = LocalDate.parse(from); val out = ArrayList<String>()
        repeat(n) { out.add(d.toString()); d = d.minusDays(1) }
        return out
    }
    fun monthDates(month: String): List<String> {
        val out = ArrayList<String>()
        var d = try { LocalDate.parse("$month-01") } catch (e: Exception) { return out }
        while (d.toString().startsWith(month)) { out.add(d.toString()); d = d.plusDays(1) }
        return out
    }

    // ---------- access ----------
    fun grants(u: Employee?): Map<String, Int> {
        if (u == null) return emptyMap()
        if (u.isAdmin) return Grants.ALL.associate { it.first to 1 }
        return u.hrAccess
    }
    fun has(grant: String, u: Employee? = me): Boolean {
        val g = grants(u)
        g[grant]?.let { return it != 0 }
        return (Grants.DEFAULT_ON[grant] ?: 0) != 0
    }
    fun isHrAdmin(u: Employee? = me) = has("admin", u)
    fun isStaff(u: Employee? = me) = has("admin", u) || has("staff", u)
    fun sectionOpen(u: Employee? = me) = listOf("attendance", "incentive", "appraisal", "staff", "admin").any { has(it, u) }
    fun moduleOpen(view: String, u: Employee? = me): Boolean {
        if (!sectionOpen(u)) return false
        return when (view) {
            "hrdashboard" -> has("staff", u) || has("admin", u) || has("incentive", u) || has("appraisal", u)
            "hrattendance" -> has("attendance", u) || has("staff", u)
            "hrincentive" -> has("incentive", u)
            "hrappraisal" -> has("appraisal", u)
            else -> false
        }
    }
    fun maySeeUser(userId: String) = userId == currentUserId || isStaff()
    fun people(): List<Employee> {
        val all = data.team.filter { it.isActive }
        return if (isStaff()) all else all.filter { it.id == currentUserId }
    }
    fun name(id: String?): String = data.team.find { it.id == id }?.let { it.name.ifBlank { it.id } } ?: (id ?: "")
    fun manager(u: Employee): Employee? = data.team.find { it.id == u.parentUserId && u.parentUserId.isNotBlank() }

    // ---------- configuration ----------
    fun leaveTypeUnit(name: String?): String = cfg.leaveTypes.find { it.name == name }?.unit ?: "full"
    private fun okTime(v: String?) = v != null && Regex("^\\d{2}:\\d{2}$").matches(v)
    fun punchWindow(): Pair<String, String> =
        (if (okTime(cfg.day.punchInFrom)) cfg.day.punchInFrom else "08:45") to (if (okTime(cfg.day.punchOutUntil)) cfg.day.punchOutUntil else "20:45")
    fun fullDayMinutes(): Double {
        val h = cfg.day.fullDayHours.toDoubleOrNull()
        return (if (h != null && h.isFinite() && h > 0) h else 9.0) * 60
    }
    fun alCfg(): AlCfg {
        val a = cfg.annualLeave
        fun num(v: Double, d: Double) = if (!v.isFinite() || v < 0) d else v
        val full = num(a.compOffFullHours, 9.0); val half = num(a.compOffHalfHours, 4.5)
        return AlCfg(num(a.paidPerYear, 12.0), a.compOffEnabled, full, min(half, full), a.compOffApprover.ifBlank { "line" })
    }

    // ---------- attendance records ----------
    fun recordOf(userId: String, date: String) = data.hrAttendance.find { it.userId == userId && it.date == date }
    fun recordsFor(userId: String) = data.hrAttendance.filter { it.userId == userId }.sortedByDescending { it.date }
    fun openSession(rec: AttendanceRecord?): Session? = rec?.sessions?.find { it.`in` != null && it.out == null }
    fun workedMinutes(rec: AttendanceRecord?): Double =
        rec?.sessions?.sumOf { if (it.`in` != null && it.out != null) minutesBetween(it.`in`, it.out) else 0.0 } ?: 0.0
    fun firstIn(rec: AttendanceRecord?): String = rec?.sessions?.find { it.`in` != null }?.`in` ?: ""
    fun lastOut(rec: AttendanceRecord?): String = rec?.sessions?.lastOrNull { it.out != null }?.out ?: ""
    /** An approved leave covering this date. */
    fun leaveOn(userId: String, date: String): LeaveRequest? =
        data.hrLeave.find { it.userId == userId && it.status == "Approved" && it.from <= date && date <= it.to.ifBlank { it.from } }
    fun leaveIsHalf(l: LeaveRequest) = (l.unit.ifBlank { leaveTypeUnit(l.type) }) == "half"

    /** Null (not 0) until the office start time is configured. */
    fun lateMinutes(rec: AttendanceRecord?): Int? {
        val start = cfg.day.start
        if (!okTime(start)) return null
        val inAt = parseInstant(firstIn(rec)) ?: return null
        val local = inAt.atZone(zone)
        val due = local.toLocalDate().atTime(LocalTime.parse(start)).atZone(zone)
        val grace = cfg.day.graceMinutes.toDoubleOrNull()
        val allowed = if (grace != null && grace.isFinite() && cfg.day.graceMinutes.isNotBlank()) grace else 0.0
        val over = (local.toInstant().toEpochMilli() - due.toInstant().toEpochMilli()) / 60000.0 - allowed
        return if (over > 0) Fmt.jsRound(over).toInt() else 0
    }
    fun isWeeklyOff(date: String): Boolean {
        val d = dow(date) ?: return false
        return cfg.weeklyOff.contains(DAY_NAMES[d.value % 7])
    }
    fun holidayOn(date: String): Holiday? = cfg.holidays.find { it.date == date }
    fun isSunday(date: String) = dow(date) == DayOfWeek.SUNDAY
    fun closedDayWhy(date: String): String {
        if (isSunday(date)) return "There is no attendance on Sundays."
        val h = holidayOn(date)
        if (h != null) return "It is a holiday" + (if (h.name.isNotBlank()) " — " + h.name else "") + "."
        return ""
    }
    fun dayStatus(userId: String, date: String, rec: AttendanceRecord? = recordOf(userId, date)): DayStatus {
        val leave = leaveOn(userId, date)
        if (leave != null) return DayStatus("leave", "On approved leave" + (if (leave.type.isNotBlank()) " — " + leave.type else ""), "info")
        if (openSession(rec) != null) return DayStatus("in", "Punched in", "ok")
        if (rec != null && workedMinutes(rec) > 0) return DayStatus("present", "Present", "ok")
        if (rec != null && rec.sessions.isNotEmpty()) return DayStatus("incomplete", "Incomplete record", "warn")
        val h = holidayOn(date)
        if (h != null) return DayStatus("holiday", "Holiday" + (if (h.name.isNotBlank()) " — " + h.name else ""), "muted")
        if (isWeeklyOff(date)) return DayStatus("off", "Weekly off", "muted")
        if (isSunday(date)) return DayStatus("off", "Sunday", "muted")
        if (date == today) return DayStatus("none", "Not punched in yet", "muted")
        if (date > today) return DayStatus("future", "—", "muted")
        return DayStatus("absent", "No attendance recorded", "warn")
    }

    // ---------- the punch clock ----------
    fun punchRules(rec: AttendanceRecord?, userId: String? = currentUserId): PunchRules {
        val date = today; val atT = hhmm(now); val (from, until) = punchWindow()
        val closed = closedDayWhy(date)
        if (closed.isNotEmpty() && !alCfg().compOffEnabled) return PunchRules(false, false, closed, closed)
        val leave = leaveOn(rec?.userId ?: userId ?: "", date)
        if (leave != null && !leaveIsHalf(leave)) {
            val why = "You are on approved leave today" + (if (leave.type.isNotBlank()) " (" + leave.type + ")" else "") + "."
            val open = openSession(rec) != null
            return PunchRules(false, open, why, if (open) "" else why)
        }
        val first = firstIn(rec)
        var inOk = true; var outOk = true; var inWhy = ""; var outWhy = ""
        if (first.isNotEmpty()) { inOk = false; inWhy = "You punched in at " + clock(first) + " today. Punch In works once a day." }
        else if (atT < from) { inOk = false; inWhy = "Punch In opens at $from." }
        else if (atT >= until) { inOk = false; inWhy = "The punch clock closed at $until today." }
        if (first.isEmpty()) { outOk = false; outWhy = "Punch in first." }
        else if (atT >= until) { outOk = false; outWhy = "Punch Out closed at $until. Your last punch out stands; ask for a regularization if it is wrong." }
        return PunchRules(inOk, outOk, inWhy, outWhy)
    }

    // ---------- how much of a day counts ----------
    fun regsOn(userId: String, date: String) = data.hrRegularizations.filter { it.userId == userId && it.date == date }
    fun recordedSpans(rec: AttendanceRecord?): List<Pair<Long, Long>> =
        (rec?.sessions ?: emptyList()).filter { it.`in` != null && it.out != null }
            .mapNotNull { s -> val a = parseInstant(s.`in`); val b = parseInstant(s.out); if (a != null && b != null) a.toEpochMilli() to b.toEpochMilli() else null }
            .filter { it.second > it.first }
    fun spanMinutes(spans: List<Pair<Long, Long>>, lo: Long, hi: Long): Double {
        val cut = spans.map { max(it.first, lo) to min(it.second, hi) }.filter { it.second > it.first }.sortedBy { it.first }
        var total = 0L; var curA: Long? = null; var curB: Long? = null
        for ((a, b) in cut) {
            if (curB == null || a > curB) { if (curB != null) total += curB - curA!!; curA = a; curB = b }
            else if (b > curB) curB = b
        }
        if (curB != null) total += curB - curA!!
        return total / 60000.0
    }
    /** Punch in → latest punch out, plus approved regularized periods, clipped to the punch window, overlaps counted once. */
    fun eligibleMinutes(userId: String, date: String, rec: AttendanceRecord? = recordOf(userId, date), extra: List<Period> = emptyList()): Double {
        val (from, until) = punchWindow()
        val periods = regsOn(userId, date).filter { it.status == "Approved" }.flatMap { it.periods } + extra
        val spans = recordedSpans(rec) + periods.filter { okTime(it.from) && okTime(it.to) }.map { at(date, it.from) to at(date, it.to) }
        return spanMinutes(spans, at(date, from), at(date, until))
    }
    fun pctOf(minutes: Double) = min(100.0, Fmt.jsRound(minutes * 1000 / fullDayMinutes()) / 10)
    fun dayPct(userId: String, date: String, rec: AttendanceRecord? = recordOf(userId, date)) = pctOf(eligibleMinutes(userId, date, rec))
    fun dayCredit(userId: String, date: String, rec: AttendanceRecord? = recordOf(userId, date)): DayCredit {
        if (closedDayWhy(date).isNotEmpty()) return DayCredit(0.0, 0.0, 0.0)
        val worked = min(1.0, dayPct(userId, date, rec) / 100)
        val lv = leaveOn(userId, date)
        val units = if (lv != null) (if (leaveIsHalf(lv)) 0.5 else 1.0) else 0.0
        val unpaid = if (lv != null && categoryOf(lv) == CAT_UNPAID) units else 0.0
        val leave = units - unpaid
        return DayCredit(min(1.0, worked + leave), leave, worked, unpaid)
    }
    fun monthDays(userId: String, month: String = today.take(7)): MonthDays {
        var days = 0.0; var full = 0; var leave = 0.0; var unpaid = 0.0
        for (d in monthDates(month)) {
            if (d > today) break
            val c = dayCredit(userId, d)
            days += c.credit; if (c.credit >= 1) full++; leave += c.leave; unpaid += c.unpaid
        }
        return MonthDays(Fmt.r2(days), full, leave, Fmt.r2(unpaid), month)
    }

    // ---------- leave ----------
    fun branchHeadRole(): String = cfg.access.branchHeadRole.ifBlank { cfg.roles.find { Regex("branch", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: "" }
    fun canAnswerLeave(t: Employee?) = t != null && (t.isAdmin || moduleOpen("hrattendance", t) || isStaff(t))
    fun approverFor(userId: String): Approver? {
        val u = data.team.find { it.id == userId } ?: return null
        val live = data.team.filter { it.isActive && it.id != userId && canAnswerLeave(it) }
        val role = branchHeadRole()
        if (role.isNotEmpty()) {
            live.find { it.role == role && it.branch.isNotBlank() && it.branch == u.branch }?.let { return Approver(it.id, role + " for " + u.branch.ifBlank { "this branch" }) }
            live.find { it.role == role }?.let { return Approver(it.id, role) }
        }
        if (u.parentUserId.isNotBlank()) live.find { it.id == u.parentUserId }?.let { return Approver(it.id, "reporting manager") }
        live.find { isHrAdmin(it) }?.let { return Approver(it.id, "HR — no branch head or reporting manager on file") }
        return null
    }
    fun mayDecideLeave(row: LeaveRequest?) = row != null && row.status == "Pending" && (isHrAdmin() || row.approverId == currentUserId)
    fun leaveOf(userId: String) = data.hrLeave.filter { it.userId == userId }.sortedByDescending { it.requestedAt.ifBlank { it.at } }
    fun leavePendingForMe() = data.hrLeave.filter { it.status == "Pending" && (isHrAdmin() || it.approverId == currentUserId) }.sortedBy { it.from }
    fun leaveDatesOf(l: LeaveRequest): List<String> {
        val out = ArrayList<String>()
        var d = try { LocalDate.parse(l.from.take(10)) } catch (e: Exception) { return out }
        val end = l.to.ifBlank { l.from }.take(10)
        while (d.toString() <= end && out.size < 1000) { out.add(d.toString()); d = d.plusDays(1) }
        return out
    }
    /** Days asked for: the inclusive span, half for a half-day unit. */
    fun leaveDays(l: LeaveRequest): Double {
        if (l.from.isBlank()) return 0.0
        val span = max(1, leaveDatesOf(l).size).toDouble()
        return if (l.unit.ifBlank { leaveTypeUnit(l.type) } == "half") span * 0.5 else span
    }
    fun leaveClash(userId: String, from: String, to: String, exceptId: String? = null) = data.hrLeave.find {
        it.userId == userId && it.id != exceptId && it.status in LEAVE_ACTIVE && it.from.isNotBlank() &&
            it.from.take(10) <= to && from <= it.to.ifBlank { it.from }.take(10)
    }
    fun mayCancelLeave(l: LeaveRequest?): Boolean {
        if (l == null || l.status !in LEAVE_ACTIVE) return false
        if (l.userId != currentUserId && !isHrAdmin()) return false
        return isHrAdmin() || l.to.ifBlank { l.from } >= today
    }
    fun categoryOf(l: LeaveRequest): String =
        if (l.category in LEAVE_CATEGORIES) l.category else if (Regex("comp\\s*-?\\s*off", RegexOption.IGNORE_CASE).containsMatchIn(l.type)) CAT_COMP else CAT_PAID
    /** A Sunday, weekly off or holiday — not a leave day, and a day that can earn comp-off. */
    fun offDayKind(date: String): String {
        val h = holidayOn(date)
        if (h != null) return "Holiday" + (if (h.name.isNotBlank()) " — " + h.name else "")
        if (isSunday(date)) return "Sunday"
        if (isWeeklyOff(date)) return "Weekly off"
        return ""
    }
    fun chargeDays(l: LeaveRequest, year: String? = null): Double {
        if (l.from.isBlank()) return 0.0
        val n = leaveDatesOf(l).count { (year == null || it.take(4) == year) && offDayKind(it).isEmpty() }
        return Fmt.r2(n * if (l.unit.ifBlank { leaveTypeUnit(l.type) } == "half") 0.5 else 1.0)
    }
    fun leaveYearsOf(l: LeaveRequest) = leaveDatesOf(l).map { it.take(4) }.distinct()
    fun balances(userId: String, year: String = today.take(4), exceptId: String? = null): Balances {
        val rows = data.hrLeave.filter { it.userId == userId && it.id != exceptId }
        fun sum(cat: String, st: String) = Fmt.r2(rows.filter { categoryOf(it) == cat && it.status == st }.sumOf { chargeDays(it, year) })
        val credits = data.hrCompOff.filter { it.userId == userId && it.date.take(4) == year }
        return Balances(
            year, alCfg().paidPerYear,
            sum(CAT_PAID, "Approved"), sum(CAT_PAID, "Pending"),
            Fmt.r2(credits.filter { it.status == "Approved" }.sumOf { it.credit }),
            Fmt.r2(credits.filter { it.status == "Pending" }.sumOf { it.credit }),
            sum(CAT_COMP, "Approved"), sum(CAT_COMP, "Pending"),
            sum(CAT_UNPAID, "Approved"), sum(CAT_UNPAID, "Pending"),
        )
    }
    /** Why this request cannot be met from its balance, or "". */
    fun balanceProblem(l: LeaveRequest, approving: Boolean): String {
        val cat = categoryOf(l)
        if (cat == CAT_UNPAID) return ""
        for (y in leaveYearsOf(l)) {
            val need = chargeDays(l, y)
            if (need == 0.0) continue
            val b = balances(l.userId, y, l.id)
            val have = if (cat == CAT_PAID) (if (approving) b.paidLeft else b.paidFree) else (if (approving) b.compLeft else b.compFree)
            if (need > have + 1e-9) {
                val pending = if (cat == CAT_PAID) b.paidPending else b.compPending
                return (if (cat == CAT_PAID) "Paid Leave" else "Comp-Off") + " for " + y + ": this needs " + Fmt.daysLabel(need) +
                    " and you have " + Fmt.daysLabel(max(0.0, have)) + " " + (if (approving) "left" else "free") +
                    (if (!approving && pending > 0) " (" + Fmt.daysLabel(pending) + " is already requested and waiting)" else "") + "."
            }
        }
        return ""
    }
    fun categoriesFor(userId: String, year: String): List<String> {
        val b = balances(userId, year)
        return buildList { if (b.paidFree > 0) add(CAT_PAID); add(CAT_UNPAID); if (b.compFree > 0) add(CAT_COMP) }
    }
    fun categoryHelp(userId: String, year: String): String {
        val b = balances(userId, year)
        return "Paid Leave: " + Fmt.num(b.paidLeft) + " of " + Fmt.num(b.entitled) + " left in " + b.year +
            (if (b.paidPending > 0) " (" + Fmt.num(b.paidPending) + " waiting for approval)" else "") +
            " · Comp-Off: " + Fmt.num(b.compLeft) + " left" + (if (b.compPending > 0) " (" + Fmt.num(b.compPending) + " waiting)" else "") +
            " · Unpaid Leave: always available."
    }
    /** The leave types offered on the request form. */
    fun leaveFormTypes(): List<String> {
        val out = mutableListOf("Sick Leave", "Casual Leave")
        cfg.leaveTypes.forEach { t ->
            if (t.name.isNotBlank() && !Regex("^(full|half)\\s*day$", RegexOption.IGNORE_CASE).matches(t.name) &&
                !Regex("comp\\s*-?\\s*off", RegexOption.IGNORE_CASE).containsMatchIn(t.name) && t.name !in out) out.add(t.name)
        }
        return out
    }
    /** The older per-type allowance, only where the firm configured one. */
    fun typeBalanceLine(userId: String, type: String): String {
        val c = cfg.leaveBalance
        if (!c.enabled) return "Leave balance tracking is not configured, so no balance is shown."
        if (type.isBlank()) return "Choose a type to see the balance."
        val allowed = c.perYear[type] ?: return "No balance is configured for $type."
        val year = today.take(4)
        val used = data.hrLeave.filter { it.userId == userId && it.type == type && it.status == "Approved" && it.from.take(4) == year }.sumOf { leaveDays(it) }
        return Fmt.daysLabel(allowed - used) + " of " + Fmt.num(allowed) + " left for " + year + " (" + Fmt.daysLabel(used) + " taken)."
    }
    fun ledger(userId: String, year: String = today.take(4)): List<LedgerEntry> {
        val out = mutableListOf(LedgerEntry("$year-01-01", "Annual Paid Leave allocated", CAT_PAID, "Allocated", alCfg().paidPerYear, 0.0))
        data.hrCompOff.filter { it.userId == userId && it.date.take(4) == year }.forEach { c ->
            out.add(LedgerEntry(c.date, "Comp-Off earned — " + c.kind + ", " + Fmt.duration(c.minutes.toDouble()) + " worked", CAT_COMP, c.status, 0.0,
                if (c.status == "Approved") c.credit else 0.0,
                (if (c.status == "Approved") "+" else "") + Fmt.daysLabel(c.credit) +
                    (if (c.decidedBy.isNotBlank()) " · " + c.status.lowercase() + " by " + name(c.decidedBy) else "") + (if (c.remarks.isNotBlank()) " — " + c.remarks else "")))
        }
        data.hrLeave.filter { it.userId == userId && year in leaveYearsOf(it) }.forEach { l ->
            val cat = categoryOf(l); val days = chargeDays(l, year); val counts = l.status == "Approved"
            out.add(LedgerEntry(l.from.take(10), LEAVE_CATEGORY_LABEL[cat] + (if (l.type.isNotBlank()) " — " + l.type else "") +
                " (" + Fmt.date(l.from) + (if (l.to.isNotBlank() && l.to != l.from) " – " + Fmt.date(l.to) else "") + ")",
                cat, l.status.ifBlank { "Pending" }, if (counts && cat == CAT_PAID) -days else 0.0, if (counts && cat == CAT_COMP) -days else 0.0,
                (if (counts && cat != CAT_UNPAID) "−" else "") + Fmt.daysLabel(days) + (if (l.decidedBy.isNotBlank()) " · by " + name(l.decidedBy) else "") +
                    (if (l.remarks.isNotBlank()) " — " + l.remarks else "")))
        }
        out.sortWith(compareBy<LedgerEntry> { it.date }.thenBy { if (it.status == "Allocated") 0 else 1 })
        var paid = 0.0; var comp = 0.0
        out.forEach { paid = Fmt.r2(paid + it.paid); comp = Fmt.r2(comp + it.comp); it.paidBal = paid; it.compBal = comp }
        return out
    }

    // ---------- comp-off ----------
    fun compOffOf(userId: String) = data.hrCompOff.filter { it.userId == userId }.sortedByDescending { it.date }
    fun compOffCreditFor(minutes: Double): Double {
        val a = alCfg()
        if (minutes >= a.compOffFullHours * 60) return 1.0
        if (a.compOffHalfHours > 0 && minutes >= a.compOffHalfHours * 60) return 0.5
        return 0.0
    }
    fun compOffApproverFor(userId: String): Approver? {
        val a = alCfg()
        val live = data.team.filter { it.isActive && it.id != userId && canAnswerLeave(it) }
        if (a.compOffApprover == "hr") live.find { isHrAdmin(it) }?.let { return Approver(it.id, "HR") }
        else if (a.compOffApprover != "line") live.find { it.id == a.compOffApprover }?.let { return Approver(it.id, "comp-off approver") }
        return approverFor(userId)
    }
    fun mayDecideCompOff(row: CompOff?): Boolean {
        if (row == null || row.status != "Pending") return false
        if (row.userId == currentUserId && !isHrAdmin()) return false
        return isHrAdmin() || row.approverId == currentUserId
    }
    fun compOffPendingForMe() = data.hrCompOff.filter { mayDecideCompOff(it) }.sortedBy { it.date }

    // ---------- regularization ----------
    fun regApprovers(userId: String): List<Employee> {
        val hr = data.team.filter { it.isActive && it.id != userId && (isStaff(it) || isHrAdmin(it)) }
        if (hr.isNotEmpty()) return hr
        val a = approverFor(userId) ?: return emptyList()
        return listOfNotNull(data.team.find { it.id == a.id })
    }
    fun mayDecideReg(row: Regularization?) = row != null && row.status == "Pending" && row.userId != currentUserId &&
        (isStaff() || isHrAdmin() || regApprovers(row.userId).any { it.id == currentUserId })
    fun regPendingForMe() = data.hrRegularizations.filter { mayDecideReg(it) }.sortedBy { it.date }
    fun regsOf(userId: String) = data.hrRegularizations.filter { it.userId == userId }.sortedByDescending { it.requestedAt }
    /** Why these periods cannot be asked for, or "". */
    fun regProblem(userId: String, date: String, periods: List<Period>): String {
        val (from, until) = punchWindow()
        if (periods.isEmpty()) return "Add at least one period with a start and an end time."
        for (p in periods) {
            if (p.from.isBlank() || p.to.isBlank()) return "Every period needs both a start and an end time."
            if (p.to <= p.from) return "A period ends before it starts (${p.from} to ${p.to})."
            if (p.from < from || p.to > until) return "Periods have to fall between $from and $until."
        }
        if (date == today && at(date, periods.maxOf { it.to }) > now.toEpochMilli()) return "A period cannot end later than now."
        val before = eligibleMinutes(userId, date); val after = eligibleMinutes(userId, date, extra = periods)
        if (after - before < 1) return "Those times are already counted on this day, so there is nothing to add."
        return ""
    }

    // ---------- a date, read once (history and month board) ----------
    fun leaveOverlapping(userId: String, date: String, statuses: List<String>) =
        data.hrLeave.filter { it.userId == userId && it.status in statuses && it.from <= date && date <= it.to.ifBlank { it.from } }
    fun ahDay(userId: String, date: String): AhDay {
        val r = recordOf(userId, date)
        val st = dayStatus(userId, date, r)
        val lv = leaveOn(userId, date)
        val regs = regsOn(userId, date)
        val regPending = regs.any { it.status == "Pending" }
        val pendingLeave = leaveOverlapping(userId, date, listOf("Pending")).firstOrNull()
        val declined = leaveOverlapping(userId, date, listOf("Rejected", "Declined")).firstOrNull()
        val worked = eligibleMinutes(userId, date, r)
        var bucket = ""; var units = 0.0; var chip = st.label; var tone = "muted"; var remarks = ""; var reason = ""; var category = ""
        if (lv != null) {
            val half = leaveIsHalf(lv); val cat = categoryOf(lv)
            bucket = "leave"; units = if (half) 0.5 else 1.0; category = cat
            chip = lv.type.ifBlank { "Leave" } + (if (cat == CAT_PAID) "" else " ($cat)")
            tone = if (cat == CAT_UNPAID) "danger" else if (half) "warn" else "info"
            remarks = "Approved " + LEAVE_CATEGORY_LABEL[cat]!!.lowercase() + (if (cat == CAT_UNPAID) " (not a working day)" else "") + (if (lv.reason.isNotBlank()) ": " + lv.reason else "")
        } else if (st.code in listOf("in", "present", "incomplete") || worked > 0) {
            bucket = "present"; units = 1.0
            chip = when (st.code) { "in" -> "Punched in"; "incomplete" -> "Incomplete"; else -> "Present" }
            tone = if (st.code == "incomplete") "warn" else "ok"
        } else if (st.code == "absent") {
            bucket = "absent"; units = 1.0; chip = "Absent"; tone = "danger"
            reason = if (pendingLeave != null) "Leave request pending" + (if (pendingLeave.reason.isNotBlank()) ": " + pendingLeave.reason else "")
            else if (declined != null) "Leave declined" + (if (declined.remarks.isNotBlank()) ": " + declined.remarks else "")
            else if (regPending) "Regularization pending" else ""
        }
        val notes = mutableListOf<String>()
        if (remarks.isNotEmpty()) notes.add(remarks)
        if (regPending) notes.add("Regularization pending") else if (regs.any { it.status == "Approved" }) notes.add("Regularized")
        if (lv == null && pendingLeave != null) notes.add("Leave pending")
        if (bucket == "absent" && declined != null) notes.add("Leave declined")
        val late = if (r != null) lateMinutes(r) else null
        if (late != null && late > 0) notes.add("Late by $late min")
        return AhDay(date, r, bucket, units, chip, tone, notes.distinct().joinToString(" · "), reason, category)
    }
    fun closedCode(date: String) = if (isSunday(date)) "S" else if (holidayOn(date) != null) "H" else if (isWeeklyOff(date)) "W" else ""
    fun monthRow(p: Employee, month: String): MonthRow {
        val dates = monthDates(month)
        val codes = ArrayList<MonthCell>()
        var present = 0; var absent = 0; var leave = 0.0; var unpaid = 0.0; var late = 0; var mins = 0.0; var credit = 0.0
        var pendingLeave = 0; var pendingReg = 0; var open = 0; var extra = 0.0
        for (date in dates) {
            val closed = closedCode(date)
            val closedTitle = { Fmt.date(date) + " — " + when (closed) { "S" -> "Sunday"; "H" -> "Holiday" + (holidayOn(date)?.name?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""); else -> "Weekly off" } }
            if (date > today) { codes.add(MonthCell(closed, if (closed.isNotEmpty()) closedTitle() else "")); continue }
            if (closed.isNotEmpty()) {
                val w0 = workedMinutes(recordOf(p.id, date))
                val co = data.hrCompOff.find { it.userId == p.id && it.date == date }
                extra += w0
                codes.add(MonthCell(closed, closedTitle() + if (w0 > 0) " (worked " + Fmt.duration(w0) + " — extra day" +
                    (if (co != null) ", comp-off " + Fmt.daysLabel(co.credit) + " " + co.status.lowercase() else "") + ")" else ""))
                continue
            }
            val d = ahDay(p.id, date)
            val m = eligibleMinutes(p.id, date, d.rec)
            val lt = if (d.rec != null) lateMinutes(d.rec) else null
            mins += m; credit += dayCredit(p.id, date, d.rec).credit
            if (lt != null && lt > 0) late++
            if (leaveOverlapping(p.id, date, listOf("Pending")).isNotEmpty()) pendingLeave++
            if (regsOn(p.id, date).any { it.status == "Pending" }) pendingReg++
            var c = "·"; var t = d.chip
            when (d.bucket) {
                "leave" -> { leave += d.units; if (d.category == CAT_UNPAID) { unpaid += d.units; c = "U" } else c = if (d.units < 1) "½" else "L" }
                "present" -> {
                    present++
                    c = when (d.chip) { "Punched in" -> { open++; "IN" }; "Incomplete" -> "I"; else -> "P" }
                    t = d.chip + " · " + Fmt.duration(m) + (if (lt != null && lt > 0) " · late $lt min" else "")
                }
                "absent" -> { absent++; c = "A" }
            }
            if (d.remarks.isNotEmpty() && !t.contains(d.remarks)) t += " · " + d.remarks
            codes.add(MonthCell(c, Fmt.date(date) + " — " + t))
        }
        val elapsed = dates.count { closedCode(it).isEmpty() && it <= today }
        val cr = Fmt.r2(credit)
        return MonthRow(p, codes, present, absent, Fmt.r2(leave), Fmt.r2(unpaid), late, mins, cr, pendingLeave, pendingReg, open, extra,
            if (elapsed > 0) Fmt.jsRound(cr * 1000 / elapsed) / 10 else 0.0)
    }

    // ---------- reminders ----------
    fun nudgeDue(): Nudge? {
        val r = cfg.reminders
        if (!r.enabled) return null
        val u = me ?: return null
        if (!moduleOpen("hrattendance", u)) return null
        val inAfter = if (Regex("^\\d{1,2}:\\d{2}$").matches(r.inAfter)) r.inAfter else "09:10"
        val outAfter = if (Regex("^\\d{1,2}:\\d{2}$").matches(r.outAfter)) r.outAfter else "19:00"
        val t = today
        if (leaveOn(u.id, t) != null) return null
        if (isWeeklyOff(t) || holidayOn(t) != null || isSunday(t)) return null
        val local = now.atZone(zone)
        val mins = local.hour * 60 + local.minute
        val rec = recordOf(u.id, t)
        val open = openSession(rec)
        val rules = punchRules(rec)
        if (firstIn(rec).isEmpty() && rules.inOk && mins >= minutesOfDay(inAfter))
            return Nudge("in", "You have not punched in", "It is past $inAfter and there is no punch in on your record for today.")
        if (open != null && rules.outOk && mins >= minutesOfDay(outAfter))
            return Nudge("out", "You are still punched in", "It is past $outAfter and your session from " + clock(open.`in`) + " is still open.")
        return null
    }

    companion object {
        val DAY_NAMES = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        fun minutesOfDay(hhmm: String): Int { val b = hhmm.split(":"); return b[0].toInt() * 60 + b[1].toInt() }
        fun parseInstant(iso: String?): Instant? = if (iso.isNullOrBlank()) null else try { Instant.parse(iso) } catch (e: Exception) { null }
    }
}
