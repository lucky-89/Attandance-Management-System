package com.finowise.hr.core

import com.finowise.hr.core.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Where the data lives between launches. The app writes a JSON file; tests keep it in memory. */
interface Persistence {
    fun load(): HrData?
    fun save(data: HrData)
}

class MemoryPersistence(var stored: HrData? = null) : Persistence {
    override fun load() = stored
    override fun save(data: HrData) { stored = data }
}

data class Outcome(val ok: Boolean, val message: String = "", val id: String? = null) {
    companion object {
        fun fail(msg: String) = Outcome(false, msg)
        fun ok(msg: String = "", id: String? = null) = Outcome(true, msg, id)
    }
}

/** What the device reported when asked for a location: a reading, or why not. */
data class LocFix(
    val ok: Boolean, val status: String, val lat: Double? = null, val lng: Double? = null,
    val accuracy: Int? = null, val why: String = "",
)

data class LeaveForm(
    val type: String, val from: String, val to: String, val duration: String, val category: String,
    val reason: String, val delegated: String, val handoverTo: String = "", val delegateEmail: String = "",
    val tasks: List<String> = emptyList(), val lockDate: String? = null,
)

val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; isLenient = true }

/**
 * Every change the HR module makes. Each one acts as the signed-in person,
 * checks the same things the web app and its server checked, writes the
 * record, an audit line and a notification, and saves.
 */
class HrService(
    private val persistence: Persistence,
    private val clock: () -> Instant = { Instant.now() },
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    @Volatile var data: HrData = persistence.load() ?: HrData()
        private set
    private val listeners = mutableListOf<(HrData) -> Unit>()
    fun addListener(l: (HrData) -> Unit) { listeners.add(l) }
    fun removeListener(l: (HrData) -> Unit) { listeners.remove(l) }

    fun now(): Instant = clock()
    fun rules(userId: String?) = HrRules(data, userId, clock(), zone)
    val isSetUp: Boolean get() = data.team.any { it.isAdmin && it.passwordHash.isNotBlank() }

    @Synchronized
    private fun commit(next: HrData): String? {
        Guards.attendance(data.hrAttendance, next.hrAttendance)?.let { return it }
        Guards.leave(data.hrLeave, next.hrLeave)?.let { return it }
        Guards.compOff(data.hrCompOff, next.hrCompOff)?.let { return it }
        persistence.save(next)
        data = next
        listeners.toList().forEach { it(next) }
        return null
    }

    private val rnd = SecureRandom()
    fun uid(prefix: String): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val r = (1..5).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        return "$prefix-$r" + clock().toEpochMilli().toString().takeLast(4)
    }
    private fun iso() = clock().toString()

    private fun audit(d: HrData, by: String, recordId: String, action: String, vararg f: Pair<String, String>, old: Map<String, String> = emptyMap()): HrData =
        d.copy(auditLog = (d.auditLog + AuditEntry(uid("AUD"), iso(), by, "HR", recordId, action,
            f.map { AuditField(it.first, old[it.first] ?: "", it.second) })).takeLast(5000))
    private fun notify(d: HrData, userIds: List<String>, title: String, body: String, kind: String): HrData =
        d.copy(notifications = (d.notifications + userIds.filter { it.isNotBlank() }.distinct()
            .map { AppNotification(uid("NTF"), it, title, body, kind, iso()) }).takeLast(3000))

    // =================================================================
    // Sign-in and accounts
    // =================================================================
    fun hashPassword(pw: String, salt: ByteArray = ByteArray(16).also { rnd.nextBytes(it) }): Pair<String, String> {
        val spec = PBEKeySpec(pw.toCharArray(), salt, 60000, 256)
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val b64 = Base64.getEncoder()
        return b64.encodeToString(hash) to b64.encodeToString(salt)
    }
    private fun passwordOk(e: Employee, pw: String): Boolean {
        if (e.passwordHash.isBlank()) return false
        val salt = try { Base64.getDecoder().decode(e.passwordSalt) } catch (x: Exception) { return false }
        return hashPassword(pw, salt).first == e.passwordHash
    }
    private fun pwProblem(pw: String) = if (pw.length < 6) "A password needs at least 6 characters." else null

    fun setupFirm(firmName: String, adminName: String, email: String, password: String): Outcome {
        if (isSetUp) return Outcome.fail("This device is already set up.")
        if (firmName.isBlank() || adminName.isBlank()) return Outcome.fail("Enter the firm's name and your name.")
        if (!EMAIL.matches(email.trim())) return Outcome.fail("Enter a valid email address.")
        pwProblem(password)?.let { return Outcome.fail(it) }
        val (h, s) = hashPassword(password)
        val admin = Employee(id = uid("TM"), name = adminName.trim(), email = email.trim().lowercase(), employeeId = "EMP001",
            role = "Admin", designation = "Administrator", passwordHash = h, passwordSalt = s, createdAt = iso())
        val next = data.copy(team = data.team + admin, hrConfig = data.hrConfig.copy(firmName = firmName.trim()))
        commit(audit(next, admin.id, admin.id, "Firm Set Up", "Firm" to firmName.trim()))?.let { return Outcome.fail(it) }
        return Outcome.ok("Welcome.", admin.id)
    }

    fun signIn(email: String, password: String): Outcome {
        val e = data.team.find { it.email.equals(email.trim(), ignoreCase = true) }
        if (e == null || !e.isActive || !passwordOk(e, password)) return Outcome.fail("That email and password do not match.")
        return Outcome.ok(id = e.id)
    }

    fun changePassword(userId: String, current: String, next: String): Outcome {
        val e = data.team.find { it.id == userId } ?: return Outcome.fail("Sign in first.")
        if (!passwordOk(e, current)) return Outcome.fail("Your current password is not right.")
        pwProblem(next)?.let { return Outcome.fail(it) }
        val (h, s) = hashPassword(next)
        val d = data.copy(team = data.team.map { if (it.id == userId) it.copy(passwordHash = h, passwordSalt = s, mustChangePassword = false) else it })
        commit(audit(d, userId, userId, "Password Changed"))?.let { return Outcome.fail(it) }
        return Outcome.ok("Password changed.")
    }

    /** Add or edit a person. Only an HR administrator manages people. */
    fun saveEmployee(byId: String, e: Employee, newPassword: String?): Outcome {
        val r = rules(byId)
        if (!r.isHrAdmin()) return Outcome.fail("Only an HR administrator manages employees.")
        if (e.name.isBlank()) return Outcome.fail("Enter the person's name.")
        if (!EMAIL.matches(e.email.trim())) return Outcome.fail("Enter a valid email address — it is how they sign in.")
        if (data.team.any { it.id != e.id && it.email.equals(e.email.trim(), true) }) return Outcome.fail("Somebody else already signs in with that email.")
        if (e.employeeId.isNotBlank() && data.team.any { it.id != e.id && it.employeeId == e.employeeId }) return Outcome.fail("That Employee ID is already used.")
        if (e.parentUserId == e.id && e.id.isNotBlank()) return Outcome.fail("Somebody cannot report to themselves.")
        val existing = data.team.find { it.id == e.id }
        if (existing == null && newPassword.isNullOrBlank()) return Outcome.fail("Give the new account a first password.")
        newPassword?.takeIf { it.isNotBlank() }?.let { pw -> pwProblem(pw)?.let { return Outcome.fail(it) } }
        if (existing != null && existing.isAdmin && (!e.isAdmin || !e.isActive) && data.team.count { it.isAdmin && it.isActive } <= 1)
            return Outcome.fail("This is the only administrator. Make somebody else an administrator first.")
        var rec = e.copy(email = e.email.trim().lowercase(), name = e.name.trim(),
            id = e.id.ifBlank { uid("TM") }, createdAt = existing?.createdAt ?: iso(),
            passwordHash = existing?.passwordHash ?: "", passwordSalt = existing?.passwordSalt ?: "",
            mustChangePassword = existing?.mustChangePassword ?: false)
        if (!newPassword.isNullOrBlank()) {
            val (h, s) = hashPassword(newPassword)
            rec = rec.copy(passwordHash = h, passwordSalt = s, mustChangePassword = true)
        }
        val team = if (existing == null) data.team + rec else data.team.map { if (it.id == rec.id) rec else it }
        val d = audit(data.copy(team = team), byId, rec.id, if (existing == null) "Employee Added" else "Employee Updated",
            "Name" to rec.name, "Role" to rec.role, "Status" to rec.status)
        commit(d)?.let { return Outcome.fail(it) }
        return Outcome.ok(if (existing == null) "${rec.name} added. They sign in with ${rec.email} and the password you gave." else "Saved.", rec.id)
    }

    fun saveConfig(byId: String, cfg: HrConfig): Outcome {
        if (!rules(byId).isHrAdmin()) return Outcome.fail("HR configuration is an administrator’s to change.")
        val bad = cfg.holidays.firstOrNull { runCatching { LocalDate.parse(it.date) }.isFailure }
        if (bad != null) return Outcome.fail("Holiday date \"${bad.date}\" is not a date (use YYYY-MM-DD).")
        val tre = Regex("^\\d{2}:\\d{2}$")
        listOf(cfg.day.start, cfg.day.end).forEach { if (it.isNotBlank() && !tre.matches(it)) return Outcome.fail("Times are written HH:MM, e.g. 09:30.") }
        if (!tre.matches(cfg.day.punchInFrom) || !tre.matches(cfg.day.punchOutUntil)) return Outcome.fail("The punch window needs two times written HH:MM.")
        if (cfg.day.punchOutUntil <= cfg.day.punchInFrom) return Outcome.fail("The punch clock must close after it opens.")
        val d = audit(data.copy(hrConfig = cfg), byId, "config", "HR Configuration Changed", "Changed by" to rules(byId).name(byId))
        commit(d)?.let { return Outcome.fail(it) }
        return Outcome.ok("HR settings saved.")
    }

    // =================================================================
    // Punching in and out
    // =================================================================
    private fun locStamp(fix: LocFix) = if (fix.ok) LocStamp("captured", fix.lat, fix.lng, fix.accuracy, iso())
        else LocStamp(status = fix.status, at = iso(), why = fix.why)
    private fun locText(fix: LocFix) = if (fix.ok) "${fix.lat}, ${fix.lng}" + (fix.accuracy?.let { " (±${it}m)" } ?: "") else "not captured — " + fix.status
    /** The refusal when a location is required and could not be read, or null. */
    fun locationBlocks(fix: LocFix): String? {
        if (fix.ok || !data.hrConfig.location.required) return null
        val note = data.hrConfig.location.note
        return "Your location is needed to punch, and it could not be read.\n\n" + fix.why +
            "\n\nAllow location for this app and turn on the device's location, then try again." + (if (note.isNotBlank()) "\n\n$note" else "")
    }

    fun punchIn(userId: String, fix: LocFix): Outcome {
        val r = rules(userId)
        val u = r.me ?: return Outcome.fail("Sign in first.")
        if (!r.moduleOpen("hrattendance")) return Outcome.fail("Attendance has not been granted to this account.")
        val date = r.today
        val rec = r.recordOf(u.id, date)
        val rules = r.punchRules(rec)
        if (!rules.inOk) return Outcome.fail("You cannot punch in now.\n\n" + rules.inWhy)
        locationBlocks(fix)?.let { return Outcome.fail(it) }
        val at = iso()
        val session = Session(`in` = at, out = null, inBy = u.id, inLoc = locStamp(fix))
        val newRec = (rec ?: AttendanceRecord(uid("HRA"), u.id, date, emptyList(), at, u.id)).let { it.copy(sessions = it.sessions + session, updatedAt = at) }
        val list = if (rec == null) data.hrAttendance + newRec else data.hrAttendance.map { if (it.id == newRec.id) newRec else it }
        val d = audit(data.copy(hrAttendance = list), u.id, newRec.id, "Punched In",
            "Person" to u.name, "Date" to date, "At" to r.clock(at), "Location" to locText(fix))
        commit(d)?.let { return Outcome.fail(it) }
        return Outcome.ok(if (fix.ok) "Punched in at ${r.clock(at)}." else
            "Punched in at ${r.clock(at)}.\n\nYour location was not recorded: ${fix.why.ifBlank { fix.status }}\n\nThe punch stands and says so — nothing was guessed.")
    }

    fun punchOut(userId: String, fix: LocFix): Outcome {
        val r = rules(userId)
        val u = r.me ?: return Outcome.fail("Sign in first.")
        val date = r.today
        val rec = r.recordOf(u.id, date) ?: return Outcome.fail("You cannot punch out now.\n\nPunch in first.")
        val rules = r.punchRules(rec)
        if (!rules.outOk) return Outcome.fail("You cannot punch out now.\n\n" + rules.outWhy)
        locationBlocks(fix)?.let { return Outcome.fail(it) }
        val idx = rec.sessions.indexOfFirst { it.`in` != null && it.out == null }.takeIf { it >= 0 }
            ?: rec.sessions.indexOfLast { it.`in` != null }
        val open = rec.sessions[idx]
        val again = open.out != null
        val previous = if (again) r.clock(open.out) else ""
        val at = iso()
        val hist = if (again) open.outHistory + OutHistory(open.out, open.outBy, open.outLoc) else open.outHistory
        val closed = open.copy(out = at, outBy = u.id, outLoc = locStamp(fix), outHistory = hist)
        val newRec = rec.copy(sessions = rec.sessions.toMutableList().also { it[idx] = closed }, updatedAt = at)
        var d = data.copy(hrAttendance = data.hrAttendance.map { if (it.id == newRec.id) newRec else it })
        val worked = HrRules(d, userId, clock(), zone).workedMinutes(newRec)
        d = audit(d, u.id, newRec.id, "Punched Out", "Person" to u.name, "Date" to date, "At" to r.clock(at),
            "Location" to locText(fix), "Worked today" to Fmt.duration(worked), old = mapOf("At" to previous))
        commit(d)?.let { return Outcome.fail(it) }
        var msg = "Punched out at ${r.clock(at)}. Worked today: ${Fmt.duration(worked)}."
        if (!fix.ok) msg += "\n\nYour location was not recorded: ${fix.why.ifBlank { fix.status }}"
        // Worked a Sunday, a weekly off or a holiday: that is an extra day.
        if (r.offDayKind(date).isNotEmpty()) compOffSync(u.id, date, userId)?.let { if (it.isNotBlank()) msg += "\n\n$it" }
        return Outcome.ok(msg)
    }

    // =================================================================
    // Leave
    // =================================================================
    private fun leaveEmployee(r: HrRules, u: Employee) = LeaveEmployee(u.name, u.employeeId.ifBlank { u.id }, u.department,
        u.designation.ifBlank { u.role }, u.branch, r.manager(u)?.name ?: "", u.email, u.mobile)

    fun submitLeave(userId: String, f: LeaveForm): Outcome {
        val r = rules(userId)
        val u = r.me ?: return Outcome.fail("Sign in first.")
        val to = f.to.ifBlank { f.from }
        if (f.type.isBlank()) return Outcome.fail("Choose the Leave Type.")
        if (f.from.isBlank()) return Outcome.fail("A start and an end date are needed.")
        if (runCatching { LocalDate.parse(f.from); LocalDate.parse(to) }.isFailure) return Outcome.fail("Those are not valid dates.")
        if (to < f.from) return Outcome.fail("The end date is before the start date.")
        if (f.lockDate != null && (f.from != f.lockDate || to != f.lockDate)) return Outcome.fail("This request is for ${Fmt.date(f.lockDate)} only.")
        if (f.lockDate == null && f.from < r.today) return Outcome.fail("Leave cannot start before today.")
        if (f.duration.isBlank()) return Outcome.fail("Choose the Duration: Full Day or a Half Day.")
        val half = f.duration.startsWith("Half")
        if (half && f.from != to) return Outcome.fail("A half day is for one date. Make the Start Date and End Date the same, or choose Full Day.")
        val session = if (half) (if (f.duration.contains("Second")) "Second Half" else "First Half") else ""
        if (f.category !in LEAVE_CATEGORIES) return Outcome.fail("Choose the Leave Category: Paid, Unpaid or Comp-Off.")
        val unit = if (half) "half" else r.leaveTypeUnit(f.type)
        val probe = LeaveRequest(id = "probe", userId = u.id, from = f.from, to = to, category = f.category, unit = if (half) "half" else "full")
        if (f.category != CAT_UNPAID && r.chargeDays(probe) == 0.0)
            return Outcome.fail("Every date you chose is a Sunday, a weekly off or a holiday, so there is nothing to charge to ${LEAVE_CATEGORY_LABEL[f.category]}.")
        r.balanceProblem(probe, false).takeIf { it.isNotEmpty() }?.let { return Outcome.fail("$it\n\nChoose fewer days, or apply as Unpaid Leave.") }
        if (f.delegated.isBlank()) return Outcome.fail("Answer \"Have you delegated your pending tasks?\"")
        var tasks = emptyList<String>(); var handover = ""; var email = ""
        if (f.delegated == "Yes") {
            handover = f.handoverTo; email = f.delegateEmail.trim(); tasks = (0..2).map { f.tasks.getOrElse(it) { "" }.trim() }
            if (handover.isBlank()) return Outcome.fail("Choose who you delegated your pending tasks to.")
            if (!EMAIL.matches(email)) return Outcome.fail("Enter a valid Delegated Person Email.")
            tasks.indexOfFirst { it.isBlank() }.takeIf { it >= 0 }?.let { return Outcome.fail("Fill in Delegated Task #${it + 1}.") }
        }
        val approver = r.approverFor(u.id) ?: return Outcome.fail("There is nobody on file to approve this. An HR administrator needs to set a reporting manager or branch head.")
        r.leaveClash(u.id, f.from, to)?.let { c ->
            return Outcome.fail("You already have " + (if (c.status == "Approved") "approved" else "pending") + " leave from " + Fmt.date(c.from) + " to " +
                Fmt.date(c.to.ifBlank { c.from }) + ", which overlaps the dates you chose.\n\nChoose dates that do not overlap, or cancel the earlier request first.")
        }
        val now = iso()
        val row = LeaveRequest(id = uid("HRL"), userId = u.id, from = f.from, to = to, type = f.type, category = f.category, unit = unit,
            reason = f.reason.trim(), status = "Pending", approverId = approver.id, approverWhy = approver.why, requestedAt = now,
            requestedBy = u.id, at = now, duration = f.duration, session = session, delegated = f.delegated, handoverTo = handover,
            delegateEmail = email, delegatedTasks = tasks, employee = leaveEmployee(r, u))
        var d = data.copy(hrLeave = data.hrLeave + row)
        val days = r.leaveDays(row)
        d = audit(d, u.id, row.id, "Leave Requested", "Person" to u.name, "Dates" to "${f.from} to $to (${Fmt.daysLabel(days)})",
            "Type" to f.type, "Leave Category" to LEAVE_CATEGORY_LABEL[f.category]!!, "With" to r.name(approver.id) + " — " + approver.why)
        d = notify(d, listOf(approver.id), u.name.ifBlank { "A colleague" } + " has requested leave",
            LEAVE_CATEGORY_LABEL[f.category] + " · " + f.type + " — " + Fmt.date(f.from) + " to " + Fmt.date(to) + " (" + Fmt.daysLabel(days) + "). " + f.reason.take(120),
            "hr-leave-request")
        commit(d)?.let { return Outcome.fail(it) }
        return Outcome.ok("Requested.\n\nIt is with ${r.name(approver.id)} (${approver.why}) and shows as Pending until they answer.", row.id)
    }

    fun decideLeave(byId: String, id: String, approved: Boolean, note: String): Outcome {
        val r = rules(byId)
        val row = data.hrLeave.find { it.id == id } ?: return Outcome.fail("That request is gone.")
        if (!r.mayDecideLeave(row)) return Outcome.fail("That request is not yours to decide.")
        val n = note.trim()
        if (!approved && n.isEmpty()) return Outcome.fail("A declined request needs a reason.\n\nThe person is being told no, and they are owed the why.")
        if (approved) r.balanceProblem(row, true).takeIf { it.isNotEmpty() }?.let {
            return Outcome.fail("This cannot be approved as ${LEAVE_CATEGORY_LABEL[r.categoryOf(row)]}.\n\n$it\n\nDecline it with a reason, and they can apply again as Unpaid Leave.")
        }
        val cat = r.categoryOf(row); val yr = row.from.take(4)
        val before = r.balances(row.userId, yr)
        val next = row.copy(category = cat, status = if (approved) "Approved" else "Declined", decidedBy = byId, decidedAt = iso(), remarks = n,
            charged = if (approved) (if (cat == CAT_UNPAID) 0.0 else r.chargeDays(row)) else row.charged)
        var d = data.copy(hrLeave = data.hrLeave.map { if (it.id == id) next else it })
        val after = HrRules(d, byId, clock(), zone).balances(row.userId, yr)
        val key: (Balances) -> Double = when (cat) { CAT_PAID -> { b -> b.paidLeft }; CAT_COMP -> { b -> b.compLeft }; else -> { b -> b.unpaid } }
        val balField = if (cat == CAT_UNPAID) "Unpaid Leave taken ($yr)" else LEAVE_CATEGORY_LABEL[cat] + " balance ($yr)"
        d = audit(d, byId, id, "Leave " + next.status, "Person" to r.name(row.userId), "Dates" to row.from + " to " + row.to.ifBlank { row.from },
            "Leave Category" to LEAVE_CATEGORY_LABEL[cat]!!, balField to Fmt.num(key(after)), "Decided by" to r.name(byId), "Reason" to n.ifBlank { "—" },
            old = mapOf(balField to Fmt.num(key(before))))
        d = notify(d, listOf(row.userId), "Your leave was " + next.status.lowercase(),
            Fmt.date(row.from) + " to " + Fmt.date(row.to.ifBlank { row.from }) + if (n.isNotEmpty()) " — \"" + n.take(120) + "\"" else "", "hr-leave-decided")
        if (approved && row.handoverTo.isNotBlank() && row.delegatedTasks.any { it.isNotBlank() })
            d = notify(d, listOf(row.handoverTo), r.name(row.userId) + " has delegated tasks to you",
                "While they are on leave, " + Fmt.date(row.from) + " to " + Fmt.date(row.to.ifBlank { row.from }) + ": " +
                    row.delegatedTasks.filter { it.isNotBlank() }.joinToString("; ").take(200), "hr-leave-delegated")
        commit(d)?.let { return Outcome.fail(it) }
        return Outcome.ok("Leave ${next.status.lowercase()}. ${r.name(row.userId)} has been told.")
    }

    /** Cancel all of a leave or chosen dates; kept dates become new Approved/Pending requests pointing back at it. */
    fun cancelLeave(byId: String, id: String, picked: List<String>, reason: String): Outcome {
        val r = rules(byId)
        val l = data.hrLeave.find { it.id == id }
        if (!r.mayCancelLeave(l)) return Outcome.fail("This leave cannot be cancelled.")
        l!!
        val all = r.leaveDatesOf(l)
        if (picked.isEmpty()) return Outcome.fail("Tick the dates to cancel.")
        if (reason.isBlank()) return Outcome.fail("Say why you are cancelling.")
        if (!r.isHrAdmin()) {
            val past = picked.filter { it < r.today }
            if (past.isNotEmpty()) return Outcome.fail("Dates that have already passed cannot be cancelled: " + past.joinToString(", ") { Fmt.date(it) } + ". Ask HR.")
        }
        val dates = all.filter { it in picked }
        val keep = all.filter { it !in dates }
        val now = iso()
        val runs = mutableListOf<Pair<String, String>>()
        keep.forEach { d ->
            val last = runs.lastOrNull()
            if (last != null && LocalDate.parse(last.second).plusDays(1).toString() == d) runs[runs.size - 1] = last.first to d else runs.add(d to d)
        }
        val rest = runs.map { (a, b) -> l.copy(id = uid("HRL"), from = a, to = b, remainderOf = l.id, at = now, cancellation = null, remainders = emptyList()) }
        val updated = l.copy(status = if (keep.isNotEmpty()) "Partially Cancelled" else "Cancelled",
            cancellation = Cancellation(dates, keep, reason.trim(), now, byId, l.status), remainders = rest.map { it.id })
        var d = data.copy(hrLeave = data.hrLeave.map { if (it.id == id) updated else it } + rest)
        d = audit(d, byId, id, if (keep.isNotEmpty()) "Leave Partly Cancelled" else "Leave Cancelled", "Person" to r.name(l.userId),
            "Cancelled dates" to dates.joinToString(", "), "Still on leave" to keep.joinToString(", ").ifBlank { "—" }, "Reason" to reason.trim(),
            old = mapOf("Still on leave" to all.joinToString(", ")))
        val tell = if (l.status == "Approved") l.decidedBy.ifBlank { l.approverId } else l.approverId
        if (tell.isNotBlank() && tell != byId) d = notify(d, listOf(tell), r.name(l.userId) + " cancelled " + (if (keep.isNotEmpty()) "part of their" else "their") + " leave",
            "Cancelled: " + dates.joinToString(", ") { Fmt.date(it) } + (if (keep.isNotEmpty()) ". Still on leave: " + keep.joinToString(", ") { Fmt.date(it) } else "") + ". " + reason.take(120),
            "hr-leave-cancelled")
        if (l.userId != byId) d = notify(d, listOf(l.userId), "Your leave was cancelled by ${r.name(byId)}", dates.joinToString(", ") { Fmt.date(it) } + " — " + reason.take(120), "hr-leave-cancelled")
        commit(d)?.let { return Outcome.fail(it) }
        return Outcome.ok(if (keep.isNotEmpty()) "Cancelled ${Fmt.daysLabel(dates.size * if (l.unit == "half") 0.5 else 1.0)}. Still on leave on " + keep.joinToString(", ") { Fmt.date(it) } + "."
            else "The leave is cancelled.")
    }

    // =================================================================
    // Comp-off
    // =================================================================
    /** Make or refresh the eligibility record for an extra day. Returns a message for the person, or null. */
    fun compOffSync(userId: String, date: String, byId: String = userId): String? {
        val r = rules(byId)
        if (!r.alCfg().compOffEnabled) return null
        val kind = r.offDayKind(date)
        if (kind.isEmpty()) return null
        val rec = r.recordOf(userId, date)
        if (rec == null || r.openSession(rec) != null) return null
        val minutes = Fmt.jsRound(r.workedMinutes(rec)).toInt()
        val credit = r.compOffCreditFor(minutes.toDouble())
        val have = data.hrCompOff.find { it.userId == userId && it.date == date }
        val now = iso()
        if (have != null) {
            if (have.status != "Pending" || (have.minutes == minutes && have.credit == credit)) return null
            var hist = have.history + HistoryEntry(at = now, by = byId, what = "Hours updated from the punches", from = Fmt.duration(have.minutes.toDouble()), to = Fmt.duration(minutes.toDouble()))
            var status = have.status
            if (credit == 0.0) { status = "Not Eligible"; hist = hist + HistoryEntry(at = now, by = byId, what = "Below the half-day threshold") }
            val next = have.copy(minutes = minutes, hours = Fmt.r2(minutes / 60.0), credit = credit, updatedAt = now, history = hist, status = status)
            commit(data.copy(hrCompOff = data.hrCompOff.map { if (it.id == have.id) next else it }))
            return null
        }
        if (credit == 0.0) return null
        val approver = r.compOffApproverFor(userId)
        val row = CompOff(uid("HRC"), userId, date, kind, minutes, Fmt.r2(minutes / 60.0), credit, "Pending", approver?.id ?: "", approver?.why ?: "",
            rec.id, now, byId, history = listOf(HistoryEntry(at = now, by = byId, what = "Eligibility recorded", to = Fmt.duration(minutes.toDouble()) + " worked → " + Fmt.daysLabel(credit))))
        var d = data.copy(hrCompOff = data.hrCompOff + row)
        d = audit(d, byId, row.id, "Comp-Off Eligibility Recorded", "Person" to r.name(userId), "Day" to "$date ($kind)",
            "Worked" to Fmt.duration(minutes.toDouble()), "Comp-Off" to Fmt.daysLabel(credit),
            "With" to (approver?.let { r.name(it.id) + " — " + it.why } ?: "nobody on file"))
        if (approver != null) d = notify(d, listOf(approver.id), r.name(userId) + " worked on " + kind.split(" — ")[0].lowercase(),
            Fmt.date(date) + ": " + Fmt.duration(minutes.toDouble()) + " worked, " + Fmt.daysLabel(credit) + " comp-off to verify.", "hr-compoff-request")
        if (commit(d) != null) return null
        return "You worked ${Fmt.duration(minutes.toDouble())} on a ${kind.split(" — ")[0].lowercase()}. " +
            "${Fmt.daysLabel(credit)} of Comp-Off is waiting for ${approver?.let { r.name(it.id) } ?: "an approver"} to verify. It is credited once they approve it."
    }

    /** The person's own extra days of the last two months with no record yet. */
    fun compOffSweep(userId: String) {
        val r = rules(userId)
        if (!r.alCfg().compOffEnabled) return
        val since = r.datesBack(62).last()
        r.recordsFor(userId).filter { it.date >= since && it.date < r.today && r.offDayKind(it.date).isNotEmpty() }.forEach { rec ->
            val have = data.hrCompOff.find { it.userId == userId && it.date == rec.date }
            if (have == null || have.status == "Pending") compOffSync(userId, rec.date, userId)
        }
    }

    fun decideCompOff(byId: String, id: String, approved: Boolean, note: String): Outcome {
        val r = rules(byId)
        val row = data.hrCompOff.find { it.id == id }
        if (!r.mayDecideCompOff(row)) return Outcome.fail("That comp-off is not yours to decide.")
        row!!
        val n = note.trim()
        if (!approved && n.isEmpty()) return Outcome.fail("A rejected comp-off needs a reason.")
        val now = iso()
        val next = row.copy(status = if (approved) "Approved" else "Rejected", decidedBy = byId, decidedAt = now, remarks = n,
            creditedAt = if (approved) now else null,
            history = row.history + HistoryEntry(at = now, by = byId, what = if (approved) "Approved — credited" else "Rejected", to = if (approved) "+" + Fmt.daysLabel(row.credit) else n))
        var d = data.copy(hrCompOff = data.hrCompOff.map { if (it.id == id) next else it })
        val b = HrRules(d, byId, clock(), zone).balances(row.userId, row.date.take(4))
        d = audit(d, byId, id, if (approved) "Comp-Off Approved and Credited" else "Comp-Off Rejected", "Person" to r.name(row.userId),
            "Day" to row.date + " (" + row.kind + ")", "Worked" to Fmt.duration(row.minutes.toDouble()),
            "Comp-Off" to (if (approved) "+" + Fmt.daysLabel(row.credit) else "none"), "Comp-Off balance" to Fmt.num(b.compLeft), "Remarks" to n.ifBlank { "—" })
        d = notify(d, listOf(row.userId), "Your comp-off for ${Fmt.date(row.date)} was " + (if (approved) "approved" else "rejected"),
            if (approved) Fmt.daysLabel(row.credit) + " credited. Comp-Off left: " + Fmt.daysLabel(b.compLeft) + "." else n.take(160), "hr-compoff-decided")
        commit(d)?.let { return Outcome.fail(it) }
        return Outcome.ok(if (approved) "Approved — ${Fmt.daysLabel(row.credit)} credited." else "Rejected.")
    }

    // =================================================================
    // Regularization
    // =================================================================
    fun submitRegularization(userId: String, date: String, periods: List<Period>, reason: String): Outcome {
        val r = rules(userId)
        val u = r.me ?: return Outcome.fail("Sign in first.")
        r.closedDayWhy(date).takeIf { it.isNotEmpty() }?.let { return Outcome.fail("$it There is nothing to regularize.") }
        if (date > r.today) return Outcome.fail("That date has not happened yet.")
        r.regProblem(u.id, date, periods).takeIf { it.isNotEmpty() }?.let { return Outcome.fail(it) }
        if (reason.isBlank()) return Outcome.fail("Please give a reason — HR is being asked to add time to your record.")
        if (r.regsOn(u.id, date).any { it.status == "Pending" })
            return Outcome.fail("You already have a request for ${Fmt.date(date)} waiting on HR. Wait for their answer before asking again.")
        val hr = r.regApprovers(u.id)
        if (hr.isEmpty()) return Outcome.fail("There is nobody with HR access to send this to.")
        val rec = r.recordOf(u.id, date)
        val now = iso()
        val before = r.eligibleMinutes(u.id, date, rec); val after = r.eligibleMinutes(u.id, date, rec, periods)
        val row = Regularization(uid("HRG"), u.id, date, periods.sortedBy { it.from }, reason.trim(), "Pending", now, u.id,
            RecordedDay(r.firstIn(rec), r.lastOut(rec), Fmt.jsRound(before).toInt(), r.dayPct(u.id, date, rec)), Fmt.jsRound(after - before).toInt(),
            history = listOf(HistoryEntry(at = now, by = u.id, action = "Requested", note = reason.trim())))
        var d = data.copy(hrRegularizations = data.hrRegularizations + row)
        val pText = row.periods.joinToString(", ") { it.from + "–" + it.to }
        d = audit(d, u.id, row.id, "Regularization Requested", "Person" to u.name, "Date" to date, "Periods" to pText, "Adds" to Fmt.duration(row.addsMinutes.toDouble()))
        d = notify(d, hr.map { it.id }, u.name + " asked to regularize " + Fmt.date(date), "$pText — " + reason.take(120), "hr-regularize-request")
        commit(d)?.let { return Outcome.fail(it) }
        return Outcome.ok("Sent to HR.\n\nIt shows as Pending, and your attendance for ${Fmt.date(date)} is unchanged until it is approved.", row.id)
    }

    fun decideRegularization(byId: String, id: String, approved: Boolean, note: String): Outcome {
        val r = rules(byId)
        val row = data.hrRegularizations.find { it.id == id }
        if (!r.mayDecideReg(row)) return Outcome.fail("That request is not yours to decide.")
        row!!
        val n = note.trim()
        if (!approved && n.isEmpty()) return Outcome.fail("A rejected request needs a reason — it goes to them with the decision.")
        val now = iso()
        val pctBefore = r.dayPct(row.userId, row.date)
        var next = row.copy(status = if (approved) "Approved" else "Rejected", decidedBy = byId, decidedAt = now, remarks = n,
            history = row.history + HistoryEntry(at = now, by = byId, action = if (approved) "Approved" else "Rejected", note = n))
        var d = data.copy(hrRegularizations = data.hrRegularizations.map { if (it.id == id) next else it })
        if (approved) {
            next = next.copy(pctAfter = HrRules(d, byId, clock(), zone).dayPct(row.userId, row.date))
            d = d.copy(hrRegularizations = d.hrRegularizations.map { if (it.id == id) next else it })
        }
        d = audit(d, byId, id, "Regularization " + next.status, "Person" to r.name(row.userId), "Date" to row.date,
            "Attendance %" to Fmt.num(next.pctAfter ?: pctBefore), "Decided by" to r.name(byId), "Remarks" to n.ifBlank { "—" },
            old = mapOf("Attendance %" to Fmt.num(pctBefore)))
        d = notify(d, listOf(row.userId), "Your regularization for ${Fmt.date(row.date)} was " + next.status.lowercase(),
            (if (approved) "Attendance is now ${Fmt.num(next.pctAfter ?: 0.0)}%." else "") + if (n.isNotEmpty()) " \"" + n.take(120) + "\"" else "", "hr-regularize-decided")
        commit(d)?.let { return Outcome.fail(it) }
        return Outcome.ok("Regularization ${next.status.lowercase()}.")
    }

    // =================================================================
    // Notifications, incentive & appraisal storage, backup
    // =================================================================
    fun markNotificationsRead(userId: String) {
        if (data.notifications.none { it.userId == userId && !it.read }) return
        commit(data.copy(notifications = data.notifications.map { if (it.userId == userId) it.copy(read = true) else it }))
    }

    /** Raw writes for the Incentive and Appraisal modules, which check their own permissions first. */
    fun updateModules(byId: String, action: String, recordId: String, transform: (HrData) -> HrData): Outcome {
        val d = audit(transform(data), byId, recordId, action)
        commit(d)?.let { return Outcome.fail(it) }
        return Outcome.ok()
    }

    fun exportJson(): String = JSON.encodeToString(HrData.serializer(), data)

    fun importJson(byId: String?, text: String): Outcome {
        if (isSetUp && (byId == null || !rules(byId).isHrAdmin())) return Outcome.fail("Only an HR administrator can restore a backup.")
        val incoming = try { JSON.decodeFromString(HrData.serializer(), text) } catch (e: Exception) {
            return Outcome.fail("That file is not a Finowise HR backup (${e.message?.take(120)}).")
        }
        if (incoming.team.none { it.isAdmin && it.passwordHash.isNotBlank() })
            return Outcome.fail("That backup has no administrator who can sign in, so restoring it would lock everybody out.")
        persistence.save(incoming)
        data = incoming
        listeners.toList().forEach { it(incoming) }
        return Outcome.ok("Restored: ${incoming.team.size} people, ${incoming.hrAttendance.size} attendance days, ${incoming.hrLeave.size} leave requests.")
    }

    companion object {
        val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        @Suppress("unused") private val keep = JsonPrimitive(0) to JsonObject(emptyMap())
    }
}
