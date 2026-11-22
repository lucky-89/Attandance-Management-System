package com.finowise.hr.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.finowise.hr.AppVm
import com.finowise.hr.core.Fmt
import com.finowise.hr.core.HrRules
import com.finowise.hr.data.LocationHelper
import com.finowise.hr.ui.approvals.ApprovalsTab
import com.finowise.hr.ui.attendance.AttendanceTab
import com.finowise.hr.ui.common.*
import com.finowise.hr.ui.leave.LeaveTab
import com.finowise.hr.ui.more.MoreTab
import java.time.Instant

@Composable
fun HomeScreen(vm: AppVm, nav: NavController) {
    val data by vm.data.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val r = vm.rules(data, now)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val pending = r.leavePendingForMe().size + r.compOffPendingForMe().size + r.regPendingForMe().size
    val unread = data.notifications.count { it.userId == r.currentUserId && !it.read }
    val title = listOf(data.hrConfig.firmName.ifBlank { "Finowise HR" }, "Attendance", "Leave", "Approvals", "More")[tab]
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Dashboard, null) }, label = { Text("Home") })
            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.AccessTime, null) }, label = { Text("Attendance") })
            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.EventBusy, null) }, label = { Text("Leave") })
            NavigationBarItem(tab == 3, { tab = 3 }, {
                BadgedBox(badge = { if (pending > 0) Badge { Text("$pending") } }) { Icon(Icons.Default.TaskAlt, null) }
            }, label = { Text("Approvals") })
            NavigationBarItem(tab == 4, { tab = 4 }, {
                BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) { Icon(Icons.Default.Menu, null) }
            }, label = { Text("More") })
        }
    }) { pad ->
        Box(Modifier.padding(pad)) {
            Screen(title, actions = {
                IconButton(onClick = { nav.navigate("notifications") }) {
                    BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) { Icon(Icons.Default.Notifications, "Notifications") }
                }
            }) {
                when (tab) {
                    0 -> Dashboard(vm, nav, r)
                    1 -> AttendanceTab(vm, nav, r)
                    2 -> LeaveTab(vm, nav, r)
                    3 -> ApprovalsTab(vm, r)
                    else -> MoreTab(vm, nav, r)
                }
            }
        }
    }
}

@Composable
private fun Dashboard(vm: AppVm, nav: NavController, r: HrRules) {
    val u = r.me ?: return
    val today = r.today
    Text("Hello, ${u.name.substringBefore(' ')}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Note(Fmt.date(today) + " · " + (u.designation.ifBlank { u.role }) + (if (u.branch.isNotBlank()) " · " + u.branch else ""))
    if (r.moduleOpen("hrattendance")) PunchCard(vm, r)

    val rec = r.recordOf(u.id, today)
    val month = r.monthDays(u.id)
    Section("⏱ Attendance overview") {
        val items = mutableListOf(
            Triple(r.dayStatus(u.id, today, rec).label, "You, today", "info"),
            Triple(Fmt.duration(liveWorked(r, rec, r.now)), "Your hours today", ""),
            Triple(Fmt.num(r.dayPct(u.id, today, rec)) + "%", "Attendance today", ""),
            Triple(Fmt.num(month.days), "Days worked this month", "ok"),
        )
        KpiGrid(items)
        if (r.isStaff()) {
            val people = r.people()
            val st = people.map { r.dayStatus(it.id, today).code }
            Text("Team today", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple("present", st.count { it == "in" || it == "present" }, "Present"),
                    Triple("in", st.count { it == "in" }, "Punched in now"),
                    Triple("leave", st.count { it == "leave" }, "On leave"),
                    Triple("none", st.count { it == "none" || it == "absent" }, "Not punched in")).forEach { (k, n, l) ->
                    Surface(onClick = { nav.navigate("today/$k") }, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(8.dp)) { Text("$n", fontSize = 18.sp, fontWeight = FontWeight.Bold); Text(l, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { nav.navigate("leaveRequest?date=") }) { Text("+ Leave Request") }
            if (r.isStaff()) OutlinedButton(onClick = { nav.navigate("monthBoard") }) { Text("Month board") }
        }
    }

    val b = r.balances(u.id)
    Section("Leave balance ${b.year}") {
        KpiGrid(listOf(
            Triple(Fmt.num(b.paidLeft), "Paid Leave left of ${Fmt.num(b.entitled)}", "ok"),
            Triple(Fmt.num(b.compLeft), "Comp-Off left", "info"),
            Triple(Fmt.num(b.unpaid), "Unpaid taken", "warn"),
        ), perRow = 3)
        if (b.paidPending > 0 || b.compPending > 0) Note("Waiting for approval: " + Fmt.num(b.paidPending + b.compPending) + " day(s).")
        if (r.isStaff()) TextButton(onClick = { nav.navigate("balances") }) { Text("Everybody's balances →") }
    }

    val pending = r.leavePendingForMe().size + r.compOffPendingForMe().size + r.regPendingForMe().size
    if (pending > 0) Section("Waiting for you") { Note("$pending request(s) to decide — open the Approvals tab.", "warn") }

    if (r.moduleOpen("hrincentive") || r.moduleOpen("hrappraisal")) Section("Incentive & appraisal") {
        if (r.moduleOpen("hrincentive")) ListRow("💰 Incentive management", "Monthly incentive sheets, worked out the firm's way", { nav.navigate("incentive") })
        if (r.moduleOpen("hrappraisal")) ListRow("📈 Appraisal management", "Ratings, KPIs, increment and new CTC", { nav.navigate("appraisal") })
    }
}

/** Worked minutes today including the session still open, up to now. */
fun liveWorked(r: HrRules, rec: com.finowise.hr.core.model.AttendanceRecord?, now: Instant): Double {
    val open = r.openSession(rec)
    return r.workedMinutes(rec) + (open?.let { r.minutesBetween(it.`in`, now.toString()) } ?: 0.0)
}

@Composable
fun PunchCard(vm: AppVm, r: HrRules) {
    val ctx = LocalContext.current
    val u = r.me ?: return
    val rec = r.recordOf(u.id, r.today)
    val rules = r.punchRules(rec)
    val open = r.openSession(rec)
    val busy by vm.busy.collectAsStateWithLifecycle()
    var pendingAction by remember { mutableStateOf<String?>(null) }

    fun doPunch(kind: String) {
        vm.launch {
            vm.busy.value = true
            val fix = LocationHelper.getFix(ctx)
            vm.busy.value = false
            vm.act { if (kind == "in") vm.service.punchIn(u.id, fix) else vm.service.punchOut(u.id, fix) }
        }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingAction?.let { doPunch(it) }; pendingAction = null
    }
    fun press(kind: String) {
        if (LocationHelper.hasPermission(ctx)) doPunch(kind)
        else { pendingAction = kind; perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    }

    val st = r.dayStatus(u.id, r.today, rec)
    Section("Punch clock", trailing = { Chip(st.label, st.tone) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Fmt.duration(liveWorked(r, rec, r.now)), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Note("In " + r.clock(r.firstIn(rec)) + "   ·   Out " + r.clock(r.lastOut(rec)) +
                    (r.lateMinutes(rec)?.let { if (it > 0) "   ·   Late $it min" else "   ·   On time" } ?: ""))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { press("in") }, enabled = rules.inOk && !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.Login, null); Spacer(Modifier.width(6.dp)); Text("Punch In")
            }
            Button(onClick = { press("out") }, enabled = rules.outOk && !busy, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                Icon(Icons.AutoMirrored.Filled.Logout, null); Spacer(Modifier.width(6.dp)); Text(if (open == null && r.lastOut(rec).isNotEmpty()) "Punch Out again" else "Punch Out")
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!rules.inOk && rules.inWhy.isNotBlank() && r.firstIn(rec).isEmpty()) Note(rules.inWhy, "warn")
        else if (!rules.outOk && rules.outWhy.isNotBlank() && r.firstIn(rec).isNotEmpty()) Note(rules.outWhy, "warn")
        val w = r.punchWindow()
        Note("Punch In from ${w.first} · Punch Out until ${w.second}" + if (r.cfg.location.required) " · location required" else "")
        if (r.offDayKind(r.today).isNotEmpty() && r.alCfg().compOffEnabled)
            Note("Today is a ${r.offDayKind(r.today)}. Working it records an extra day that can earn comp-off.", "info")
    }
}
