package com.finowise.hr.ui.team

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.finowise.hr.AppVm
import com.finowise.hr.core.*
import com.finowise.hr.ui.common.*
import com.finowise.hr.ui.leave.LeaveRow
import com.finowise.hr.ui.theme.toneColor
import java.time.YearMonth

private val TONE = mapOf("P" to "ok", "IN" to "ok", "I" to "warn", "A" to "danger", "L" to "info", "U" to "warn", "½" to "info")

/** Saves text to a file the person picks. */
@Composable
fun rememberCsvSaver(vm: AppVm): (String, () -> String) -> Unit {
    val ctx = LocalContext.current
    var content by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(content.toByteArray()) } }
            .onSuccess { vm.say("Exported.") }.onFailure { vm.say("Could not save: ${it.message}") }
    }
    return { name, make -> content = make(); launcher.launch(name) }
}

fun csv(rows: List<List<Any?>>) = rows.joinToString("\n") { row ->
    row.joinToString(",") { v -> val s = v?.toString() ?: ""; if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s }
}

@Composable
fun MonthBoardScreen(vm: AppVm, nav: NavController) {
    val data by vm.data.collectAsStateWithLifecycle()
    val r = vm.rules(data)
    var month by remember { mutableStateOf(r.today.take(7)) }
    var branch by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    val save = rememberCsvSaver(vm)
    val people = r.people().filter { branch.isBlank() || it.branch == branch }
        .filter { p -> q.isBlank() || listOf(p.name, p.id, p.email, p.role, p.branch, p.employeeId).any { it.contains(q, true) } }
        .sortedBy { it.name.lowercase() }
    val rows = remember(data, month, branch, q) { people.map { r.monthRow(it, month) } }
    val dates = r.monthDates(month)
    Screen("Month board", onBack = { nav.popBackStack() }, actions = {
        IconButton(onClick = {
            save("attendance-$month.csv") {
                csv(listOf(listOf("Employee", "Employee ID", "Branch", "Present", "Absent", "Leave", "Unpaid", "Late", "Hours", "Days worked", "Attendance %") +
                    dates.map { it.takeLast(2) }) + rows.map { m ->
                    listOf(m.person.name, m.person.employeeId, m.person.branch, m.present, m.absent, Fmt.num(m.leave), Fmt.num(m.unpaid), m.late,
                        Fmt.num(m.mins / 60), Fmt.num(m.credit), Fmt.num(m.pct)) + m.codes.map { it.c }
                })
            }
        }) { Icon(Icons.Default.Download, "Export CSV") }
    }) {
        if (!r.isStaff()) { Empty("The month board is for HR."); return@Screen }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = YearMonth.parse(month).minusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
            Text(YearMonth.parse(month).let { it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() } + " " + it.year },
                Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { month = YearMonth.parse(month).plusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Search", q, { q = it }, Modifier.weight(1f))
            Dropdown("Branch", branch, listOf("" to "All") + data.team.map { it.branch }.filter { it.isNotBlank() }.distinct().sorted().map { it to it },
                { branch = it }, Modifier.weight(1f))
        }
        Note("P present · IN punched in · I incomplete · A absent · L leave · ½ half-day leave · U unpaid · S Sunday · H holiday · W weekly off")
        if (rows.isEmpty()) Empty("Nobody to show.")
        rows.forEach { m ->
            Section {
                Row(Modifier.fillMaxWidth().clickable { nav.navigate("person/${m.person.id}") }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.person.name, fontWeight = FontWeight.SemiBold)
                        Note(m.person.role + (if (m.person.branch.isNotBlank()) " · " + m.person.branch else ""))
                    }
                    Chip(Fmt.num(m.pct) + "%", if (m.pct >= 90) "ok" else if (m.pct >= 60) "warn" else "danger")
                }
                Note("Worked ${Fmt.num(m.credit)} days · present ${m.present} · absent ${m.absent} · leave ${Fmt.num(m.leave)}" +
                    (if (m.unpaid > 0) " (unpaid ${Fmt.num(m.unpaid)})" else "") + " · late ${m.late} · ${Fmt.duration(m.mins)}" +
                    (if (m.extraMins > 0) " · extra ${Fmt.duration(m.extraMins)}" else "") +
                    (if (m.pendingLeave + m.pendingReg > 0) " · pending ${m.pendingLeave + m.pendingReg}" else ""))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    m.codes.forEachIndexed { i, cell ->
                        val tone = TONE[cell.c] ?: "muted"
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(26.dp)
                            .clickable { if (cell.title.isNotBlank()) vm.say(cell.title) }) {
                            Text(dates[i].takeLast(2), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(cell.c.ifBlank { " " }, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = toneColor(tone),
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                                    .background(toneColor(tone).copy(alpha = 0.12f), RoundedCornerShape(4.dp)).padding(vertical = 3.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TeamTodayScreen(vm: AppVm, nav: NavController, kind: String) {
    val data by vm.data.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val r = vm.rules(data, now)
    val t = r.today
    val title = mapOf("present" to "Present today", "in" to "Punched in now", "leave" to "On leave today", "none" to "Not punched in today")[kind] ?: "Today"
    val people = r.people().filter {
        val c = r.dayStatus(it.id, t).code
        when (kind) { "present" -> c == "in" || c == "present"; "in" -> c == "in"; "leave" -> c == "leave"; else -> c == "none" || c == "absent" }
    }
    Screen("$title (${people.size})", onBack = { nav.popBackStack() }) {
        if (!r.isStaff()) { Empty("For HR."); return@Screen }
        if (people.isEmpty()) Empty("Nobody right now.")
        people.forEach { p ->
            val rec = r.recordOf(p.id, t)
            val sub = when (kind) {
                "leave" -> r.leaveOn(p.id, t)?.let { it.type + " · " + Fmt.date(it.from) + (if (it.to != it.from) " – " + Fmt.date(it.to) else "") + " · " + it.duration } ?: ""
                "none" -> listOf(p.mobile.ifBlank { "—" }, "Manager: " + (r.manager(p)?.name ?: "—"),
                    if (r.leaveOverlapping(p.id, t, listOf("Pending")).isNotEmpty()) "Leave request pending" else "").filter { it.isNotBlank() }.joinToString(" · ")
                else -> "In ${r.clock(r.firstIn(rec))} · Out ${r.clock(r.lastOut(rec))} · ${Fmt.duration(r.workedMinutes(rec))}" +
                    (r.lateMinutes(rec)?.let { if (it > 0) " · late $it min" else " · on time" } ?: "")
            }
            ListRow(p.name + " · " + p.role, (if (p.branch.isNotBlank()) p.branch + " · " else "") + sub, { nav.navigate("person/${p.id}") })
        }
    }
}

@Composable
fun PersonHistoryScreen(vm: AppVm, nav: NavController, userId: String) {
    val data by vm.data.collectAsStateWithLifecycle()
    val r = vm.rules(data)
    val p = data.team.find { it.id == userId }
    Screen(p?.name ?: "Person", onBack = { nav.popBackStack() }) {
        if (p == null || !r.maySeeUser(userId)) { Empty("Restricted."); return@Screen }
        val m = r.monthDays(userId)
        val b = r.balances(userId)
        Section("${p.role} · ${p.branch.ifBlank { "—" }}") {
            KpiGrid(listOf(Triple(Fmt.num(m.days), "Days worked this month", "ok"), Triple(Fmt.num(b.paidLeft), "Paid Leave left", ""),
                Triple(Fmt.num(b.compLeft), "Comp-Off left", "info"), Triple(Fmt.num(b.unpaid), "Unpaid taken", "warn")))
            TextButton(onClick = { nav.navigate("ledger/$userId/${b.year}") }) { Text("Leave ledger →") }
        }
        Section("Last 45 days") {
            r.datesBack(45).forEach { date ->
                val d = r.ahDay(userId, date)
                val closed = r.closedCode(date).isNotEmpty() && d.bucket.isEmpty()
                ListRow(Fmt.date(date), d.rec?.let { "In ${r.clock(r.firstIn(it))} · Out ${r.clock(r.lastOut(it))} · ${Fmt.num(r.dayPct(userId, date, it))}%" }
                    ?.plus(if (d.remarks.isNotBlank()) "\n" + d.remarks else "") ?: d.remarks.ifBlank { d.reason },
                    { nav.navigate("day/$userId/$date") }) { Chip(if (closed) r.offDayKind(date) else d.chip, if (closed) "muted" else d.tone) }
            }
        }
        Section("Leave") {
            val rows = r.leaveOf(userId)
            if (rows.isEmpty()) Empty("No leave.")
            rows.take(30).forEach { l -> LeaveRow(r, l) { nav.navigate("leave/${l.id}") } }
        }
    }
}

@Composable
fun LeaveBalancesScreen(vm: AppVm, nav: NavController) {
    val data by vm.data.collectAsStateWithLifecycle()
    val r = vm.rules(data)
    var q by remember { mutableStateOf("") }
    var dept by remember { mutableStateOf("") }
    var year by remember { mutableStateOf(r.today.take(4)) }
    var cat by remember { mutableStateOf("") }
    val save = rememberCsvSaver(vm)
    val rows = data.team.filter { it.isActive }
        .filter { t -> q.isBlank() || listOf(t.name, t.employeeId, t.id).any { it.contains(q, true) } }
        .filter { dept.isBlank() || it.department == dept }
        .map { it to r.balances(it.id, year) }
        .filter { (_, b) -> when (cat) { CAT_PAID -> b.paidUsed > 0 || b.paidPending > 0; CAT_COMP -> b.compEarned > 0 || b.compUsed > 0 || b.compCreditPending > 0
            CAT_UNPAID -> b.unpaid > 0 || b.unpaidPending > 0; else -> true } }
        .sortedBy { it.first.name.lowercase() }
    Screen("Remaining leave balance", onBack = { nav.popBackStack() }, actions = {
        IconButton(onClick = {
            save("leave-balances-$year.csv") {
                csv(listOf(listOf("Employee", "Employee ID", "Department", "Annual Paid Leave", "Paid Leave Used", "Remaining Paid Leave",
                    "Total Comp-Off Earned", "Comp-Off Used", "Remaining Comp-Off", "Total Unpaid Leave Taken")) +
                    rows.map { (t, b) -> listOf(t.name, t.employeeId, t.department, Fmt.num(b.entitled), Fmt.num(b.paidUsed), Fmt.num(b.paidLeft),
                        Fmt.num(b.compEarned), Fmt.num(b.compUsed), Fmt.num(b.compLeft), Fmt.num(b.unpaid)) })
            }
        }) { Icon(Icons.Default.Download, "Export CSV") }
    }) {
        if (!r.isStaff()) { Empty("For HR."); return@Screen }
        Field("Name or employee ID", q, { q = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown("Department", dept, listOf("" to "All") + data.team.map { it.department }.filter { it.isNotBlank() }.distinct().sorted().map { it to it }, { dept = it }, Modifier.weight(1f))
            Dropdown("Year", year, (0..3).map { (r.today.take(4).toInt() - it).toString() }.map { it to it }, { year = it }, Modifier.weight(1f))
        }
        Dropdown("Leave category", cat, listOf("" to "All") + LEAVE_CATEGORIES.map { it to LEAVE_CATEGORY_LABEL[it]!! }, { cat = it })
        if (rows.isEmpty()) Empty("Nobody matches.")
        rows.forEach { (t, b) ->
            ListRow(t.name + (if (t.employeeId.isNotBlank()) " · " + t.employeeId else ""),
                "Paid ${Fmt.num(b.paidUsed)} used / ${Fmt.num(b.paidLeft)} left of ${Fmt.num(b.entitled)} · Comp-Off ${Fmt.num(b.compEarned)} earned / " +
                    "${Fmt.num(b.compUsed)} used / ${Fmt.num(b.compLeft)} left · Unpaid ${Fmt.num(b.unpaid)}",
                { nav.navigate("ledger/${t.id}/$year") })
        }
    }
}

@Composable
fun LedgerScreen(vm: AppVm, userId: String, year: String, onBack: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val r = vm.rules(data)
    Screen("Ledger — " + r.name(userId) + " " + year, onBack = onBack) {
        if (!r.maySeeUser(userId)) { Empty("Restricted."); return@Screen }
        Note("Allocation, credits and deductions with running balances. Only approved rows move a balance.")
        r.ledger(userId, year).forEach { e ->
            ListRow(Fmt.date(e.date) + " · " + e.what, e.note + "\nPaid balance ${Fmt.num(e.paidBal)} · Comp-Off balance ${Fmt.num(e.compBal)}") {
                Chip(e.status, statusTone(e.status))
            }
        }
    }
}
