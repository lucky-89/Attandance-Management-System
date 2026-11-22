package com.finowise.hr.ui.attendance

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.finowise.hr.AppVm
import com.finowise.hr.core.Fmt
import com.finowise.hr.core.HrRules
import com.finowise.hr.core.model.LocStamp
import com.finowise.hr.core.model.Period
import com.finowise.hr.ui.common.*
import com.finowise.hr.ui.home.PunchCard

@Composable
fun AttendanceTab(vm: AppVm, nav: NavController, r: HrRules) {
    val u = r.me ?: return
    if (!r.moduleOpen("hrattendance")) { Empty("Attendance has not been granted to this account."); return }
    PunchCard(vm, r)
    val month = r.monthDays(u.id)
    Section("This month") {
        KpiGrid(listOf(
            Triple(Fmt.num(month.days), "Days worked", "ok"),
            Triple("${month.full}", "Full days", ""),
            Triple(Fmt.num(month.leave), "Paid / comp-off leave days", "info"),
            Triple(Fmt.num(month.unpaid), "Unpaid leave days", "warn"),
        ))
        Note("A day counts in proportion to its attendance %: ${Fmt.num(r.fullDayMinutes() / 60)} hours is 100%. Approved leave counts as a day (half for a half day); unpaid leave does not.")
    }
    Section("History") {
        Note("Tap a date to see its punches and locations, to regularize it, or to apply leave for it.")
        r.datesBack(45).forEach { date ->
            val d = r.ahDay(u.id, date)
            val closed = r.closedCode(date)
            val chip = if (closed.isNotEmpty() && d.bucket.isEmpty()) (r.offDayKind(date)) else d.chip
            val tone = if (closed.isNotEmpty() && d.bucket.isEmpty()) "muted" else d.tone
            ListRow(Fmt.date(date) + " · " + HrRules.DAY_NAMES[r.dow(date)!!.value % 7],
                listOfNotNull(
                    d.rec?.let { "In ${r.clock(r.firstIn(it))} · Out ${r.clock(r.lastOut(it))} · ${Fmt.duration(r.eligibleMinutes(u.id, date, it))} · ${Fmt.num(r.dayPct(u.id, date, it))}%" },
                    d.remarks.ifBlank { null }, d.reason.ifBlank { null }).joinToString("\n"),
                { nav.navigate("day/${u.id}/$date") }) { Chip(chip, tone) }
        }
    }
    val regs = r.regsOf(u.id)
    if (regs.isNotEmpty()) Section("My regularization requests") {
        regs.take(20).forEach { g ->
            ListRow(Fmt.date(g.date) + " · " + g.periods.joinToString(", ") { it.from + "–" + it.to },
                g.reason + (if (g.remarks.isNotBlank()) "\n“${g.remarks}” — ${r.name(g.decidedBy)}" else "")) { Chip(g.status, statusTone(g.status)) }
        }
    }
}

@Composable
fun DayDetailScreen(vm: AppVm, nav: NavController, userId: String, date: String) {
    val data by vm.data.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val r = vm.rules(data, now)
    val ctx = LocalContext.current
    Screen(r.name(userId) + " — " + Fmt.date(date), onBack = { nav.popBackStack() }) {
        if (!r.maySeeUser(userId)) { Empty("This record is restricted."); return@Screen }
        val rec = r.recordOf(userId, date)
        val st = r.dayStatus(userId, date, rec)
        val credit = r.dayCredit(userId, date, rec)
        Section("The day", trailing = { Chip(st.label, st.tone) }) {
            LabelValue("Eligible time", Fmt.duration(r.eligibleMinutes(userId, date, rec)))
            LabelValue("Attendance", Fmt.num(r.dayPct(userId, date, rec)) + "%")
            LabelValue("Counts as", Fmt.daysText(credit.credit) + (if (credit.leave > 0) " (leave ${Fmt.num(credit.leave)})" else "") +
                (if (credit.unpaid > 0) " · unpaid ${Fmt.num(credit.unpaid)}" else ""))
            r.lateMinutes(rec)?.let { LabelValue("Late", if (it > 0) "$it min" else "On time") }
            r.offDayKind(date).takeIf { it.isNotEmpty() }?.let { LabelValue("Day", it) }
            r.leaveOn(userId, date)?.let { LabelValue("Leave", it.type + " · " + (com.finowise.hr.core.LEAVE_CATEGORY_LABEL[r.categoryOf(it)] ?: "")) }
        }
        Section("Punches") {
            if (rec == null || rec.sessions.isEmpty()) Empty("No punch on this date.")
            rec?.sessions?.forEachIndexed { i, s ->
                Text("Session ${i + 1}", fontWeight = FontWeight.SemiBold)
                LabelValue("Punch In", r.clock(s.`in`))
                LocRow("In location", s.inLoc, ctx)
                LabelValue("Punch Out", if (s.out == null) "still open" else r.clock(s.out))
                LocRow("Out location", s.outLoc, ctx)
                if (s.outHistory.isNotEmpty()) LabelValue("Earlier punch outs", s.outHistory.joinToString(", ") { r.clock(it.out) } + " (replaced, kept)")
                LabelValue("Worked", Fmt.duration(if (s.out != null) r.minutesBetween(s.`in`, s.out) else null))
            }
        }
        val regs = r.regsOn(userId, date)
        if (regs.isNotEmpty()) Section("Regularizations") {
            regs.forEach { g -> ListRow(g.periods.joinToString(", ") { it.from + "–" + it.to } + " (+${Fmt.duration(g.addsMinutes.toDouble())})", g.reason) { Chip(g.status, statusTone(g.status)) } }
        }
        if (userId == r.currentUserId && date <= r.today) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.closedDayWhy(date).isEmpty()) OutlinedButton(onClick = { nav.navigate("regularize/$date") }) { Text("Regularize Attendance") }
            OutlinedButton(onClick = { nav.navigate("leaveRequest?date=$date") }) { Text("Apply Leave") }
        }
    }
}

@Composable
private fun LocRow(label: String, loc: LocStamp?, ctx: android.content.Context) {
    when {
        loc == null -> LabelValue(label, "not recorded")
        loc.status != "captured" || loc.lat == null || loc.lng == null -> LabelValue(label, loc.status + if (loc.why.isNotBlank()) " — " + loc.why else "")
        else -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(0.42f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/?mlat=${loc.lat}&mlon=${loc.lng}#map=17/${loc.lat}/${loc.lng}"))) }
            }, modifier = Modifier.weight(0.58f)) {
                Text("%.4f, %.4f".format(loc.lat, loc.lng) + (loc.accuracy?.let { " ±${it}m" } ?: ""))
            }
        }
    }
}

@Composable
fun RegularizeScreen(vm: AppVm, date: String, onBack: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val r = vm.rules(data)
    val u = r.me ?: return
    val periods = remember { mutableStateListOf(Period()) }
    var reason by remember { mutableStateOf("") }
    Screen("Regularize — " + Fmt.date(date), onBack = onBack) {
        val rec = r.recordOf(u.id, date)
        Section("What is recorded") {
            LabelValue("Punch In", r.clock(r.firstIn(rec)))
            LabelValue("Punch Out", r.clock(r.lastOut(rec)))
            LabelValue("Counted", Fmt.duration(r.eligibleMinutes(u.id, date, rec)) + " · " + Fmt.num(r.dayPct(u.id, date, rec)) + "%")
            r.regsOn(u.id, date).forEach { Note(it.periods.joinToString(", ") { p -> p.from + "–" + p.to } + " — " + it.status) }
        }
        Section("Periods to add") {
            val w = r.punchWindow()
            Note("Times between ${w.first} and ${w.second}. Time already recorded is not counted twice.")
            periods.forEachIndexed { i, p ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TimeField("From", p.from, { periods[i] = p.copy(from = it) }, Modifier.weight(1f))
                    TimeField("To", p.to, { periods[i] = p.copy(to = it) }, Modifier.weight(1f))
                    IconButton(onClick = { if (periods.size > 1) periods.removeAt(i) else periods[0] = Period() }) { Icon(Icons.Default.Delete, "Remove") }
                }
            }
            TextButton(onClick = { periods.add(Period()) }) { Text("+ Add period") }
            val filled = periods.filter { it.from.isNotBlank() || it.to.isNotBlank() }
            if (filled.isNotEmpty()) {
                val problem = r.regProblem(u.id, date, filled)
                if (problem.isNotEmpty()) Note(problem, "danger") else {
                    val before = r.eligibleMinutes(u.id, date); val after = r.eligibleMinutes(u.id, date, extra = filled)
                    Note("If approved, this adds ${Fmt.duration(after - before)}: ${Fmt.duration(before)} becomes ${Fmt.duration(after)}, and attendance " +
                        "${Fmt.num(r.pctOf(before))}% becomes ${Fmt.num(r.pctOf(after))}%.", "ok")
                }
            }
        }
        Field("Reason", reason, { reason = it }, minLines = 2)
        val hr = r.regApprovers(u.id)
        Note(if (hr.isNotEmpty()) "Goes to HR (" + hr.joinToString(", ") { it.name } + ") and waits as Pending. Nothing changes until it is approved."
            else "Nobody holds HR access yet, so there is nobody to send this to.", if (hr.isEmpty()) "danger" else "")
        Button(onClick = {
            vm.act(onOk = { onBack() }) { vm.service.submitRegularization(u.id, date, periods.filter { it.from.isNotBlank() || it.to.isNotBlank() }, reason) }
        }, modifier = Modifier.fillMaxWidth()) { Text("Send to HR") }
    }
}
