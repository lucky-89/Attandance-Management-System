package com.finowise.hr.core

import com.finowise.hr.core.model.AttendanceRecord
import com.finowise.hr.core.model.CompOff
import com.finowise.hr.core.model.LeaveRequest
import com.finowise.hr.core.model.Session
import java.time.LocalDate

/**
 * The rules BOSS's server held so that no client could break them
 * (attendanceGuard, leaveGuard, compOffGuard). There is no server here, so
 * every write in [HrService] passes through these before it is stored.
 * Each returns null when the change is allowed, or the reason it is not.
 */
object Guards {
    /** An attendance row is written once and not edited: sessions only grow, a recorded punch never changes. */
    fun attendance(old: List<AttendanceRecord>, next: List<AttendanceRecord>): String? {
        if (next.isEmpty()) return null // a full reset
        val byId = old.associateBy { it.id }
        val seen = HashSet<String>()
        for (row in next) {
            seen.add(row.id)
            val was = byId[row.id]
            if (was == null) {
                if (row.sessions.size > 1) return "attendance ${row.id} tried to start a day with more than one punch-in"
                continue
            }
            if (row.userId != was.userId || row.date != was.date) return "attendance ${row.id} tried to move to another person or another date"
            if (row.sessions.size < was.sessions.size) return "attendance ${row.id} tried to drop a session"
            if (row.sessions.size > was.sessions.size && was.sessions.isNotEmpty()) return "attendance ${row.id} already has a punch-in for that day"
            for (i in was.sessions.indices) {
                val a = was.sessions[i]; val b = row.sessions[i]
                val moved = outMoved(a, b)
                if (!moved && a.outHistory != b.outHistory) return "attendance ${row.id} tried to change an earlier punch-out"
                if (a.`in` != null && a.`in` != b.`in`) return "attendance ${row.id} tried to change a recorded in"
                if (a.inBy != null && a.inBy != b.inBy) return "attendance ${row.id} tried to change a recorded inBy"
                if (a.inLoc != null && a.inLoc != b.inLoc) return "attendance ${row.id} tried to change a recorded inLoc"
                if (!moved) {
                    if (a.out != null && a.out != b.out) return "attendance ${row.id} tried to change a recorded out"
                    if (a.outBy != null && a.outBy != b.outBy) return "attendance ${row.id} tried to change a recorded outBy"
                    if (a.outLoc != null && a.outLoc != b.outLoc) return "attendance ${row.id} tried to change a recorded outLoc"
                }
            }
        }
        old.firstOrNull { it.id !in seen }?.let { return "attendance ${it.id} was dropped" }
        return null
    }

    /** A later punch-out that keeps the earlier one in outHistory. */
    private fun outMoved(a: Session, b: Session): Boolean {
        if (a.out == null || b.out == null || a.out == b.out) return false
        val ta = HrRules.parseInstant(a.out) ?: return false
        val tb = HrRules.parseInstant(b.out) ?: return false
        if (!tb.isAfter(ta)) return false
        if (b.outHistory.size != a.outHistory.size + 1) return false
        if (b.outHistory.subList(0, a.outHistory.size) != a.outHistory) return false
        val kept = b.outHistory.last()
        return kept.out == a.out && kept.outBy == a.outBy && kept.outLoc == a.outLoc
    }

    private fun day(v: String) = v.take(10)
    private fun datesOf(r: LeaveRequest): List<String> {
        val out = ArrayList<String>()
        var d = try { LocalDate.parse(day(r.from)) } catch (e: Exception) { return out }
        val end = day(r.to.ifBlank { r.from })
        var i = 0
        while (i < 400 && d.toString() <= end) { out.add(d.toString()); d = d.plusDays(1); i++ }
        return out
    }

    /** One active request per person per date; cancellations must be consistent. */
    fun leave(old: List<LeaveRequest>, next: List<LeaveRequest>): String? {
        val active = listOf("Pending", "Approved"); val cancelled = listOf("Cancelled", "Partially Cancelled")
        val byId = old.associateBy { it.id }
        val all = LinkedHashMap(byId); next.forEach { all[it.id] = it }
        for (r in next) {
            val was = byId[r.id]
            if (was != null && was.status in active && r.status in cancelled) {
                val c = r.cancellation
                val own = datesOf(was)
                if (c == null || c.dates.isEmpty()) return "A cancelled leave must say which dates were cancelled."
                if (c.dates.any { day(it) !in own }) return "Only dates inside the leave itself can be cancelled."
                val kept = own.filter { it !in c.dates.map(::day) }
                if ((r.status == "Cancelled") != kept.isEmpty())
                    return if (r.status == "Cancelled") "Some dates of this leave are still kept, so it is Partially Cancelled, not Cancelled."
                    else "Every date of this leave was cancelled, so it is Cancelled, not Partially Cancelled."
            }
            if (was == null && r.remainderOf.isNotBlank() && r.status in active) {
                val o = all[r.remainderOf]
                if (o == null || o.status !in cancelled) return "Kept dates can only follow a cancellation of the leave they belong to."
                val own = datesOf(o); val gone = (o.cancellation?.dates ?: emptyList()).map(::day)
                if (datesOf(r).any { it !in own || it in gone }) return "The kept dates must lie inside the original leave and not on a cancelled date."
            }
        }
        for (r in next) {
            if (r.status !in active || r.from.isBlank()) continue
            val was = byId[r.id]
            if (was != null && was.status == r.status && day(was.from) == day(r.from) && day(was.to) == day(r.to)) continue
            val a1 = day(r.from); val a2 = day(r.to.ifBlank { r.from })
            for (o in all.values) {
                if (o.id == r.id || o.userId != r.userId || o.status !in active || o.from.isBlank()) continue
                val b1 = day(o.from); val b2 = day(o.to.ifBlank { o.from })
                if (a1 <= b2 && b1 <= a2)
                    return "Leave is already " + (if (o.status == "Approved") "approved" else "requested") + " for " + b1 +
                        (if (b2 != b1) " to $b2" else "") + ", which overlaps " + a1 + (if (a2 != a1) " to $a2" else "") +
                        ". Choose dates that do not overlap, or cancel the earlier request first."
            }
        }
        return null
    }

    /** One comp-off per person per day; a decided record keeps its decision and credit. */
    fun compOff(old: List<CompOff>, next: List<CompOff>): String? {
        val byId = old.associateBy { it.id }
        val seen = HashSet<String>()
        for (r in next) {
            val k = r.userId + "|" + day(r.date)
            if (!seen.add(k)) return "Comp-off for ${day(r.date)} is already recorded. One day can be credited only once."
            val was = byId[r.id]
            if (was != null && was.status in listOf("Approved", "Rejected") && (was.status != r.status || was.credit != r.credit))
                return "This comp-off was already ${was.status.lowercase()} and cannot be changed."
        }
        return null
    }
}
