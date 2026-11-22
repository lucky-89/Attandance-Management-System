package com.finowise.hr.ui.leave

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.finowise.hr.AppVm
import com.finowise.hr.core.*
import com.finowise.hr.core.model.LeaveRequest
import com.finowise.hr.ui.common.*

@Composable
fun LeaveTab(vm: AppVm, nav: NavController, r: HrRules) {
    val u = r.me ?: return
    val b = r.balances(u.id)
    Section("Balances ${b.year}") {
        KpiGrid(listOf(
            Triple(Fmt.num(b.paidLeft), "Paid Leave left of ${Fmt.num(b.entitled)}", "ok"),
            Triple(Fmt.num(b.paidUsed), "Paid Leave used", ""),
            Triple(Fmt.num(b.compLeft), "Comp-Off left (earned ${Fmt.num(b.compEarned)})", "info"),
            Triple(Fmt.num(b.unpaid), "Unpaid Leave taken", "warn"),
        ))
        Note(r.categoryHelp(u.id, b.year))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { nav.navigate("leaveRequest?date=") }) { Text("+ Leave Request") }
            OutlinedButton(onClick = { nav.navigate("ledger/${u.id}/${b.year}") }) { Text("Ledger") }
        }
    }
    Section("My leave requests") {
        val rows = r.leaveOf(u.id)
        if (rows.isEmpty()) Empty("No leave asked for yet.")
        rows.forEach { l -> LeaveRow(r, l) { nav.navigate("leave/${l.id}") } }
    }
    val co = r.compOffOf(u.id)
    Section("My comp-off (extra days worked)") {
        if (co.isEmpty()) Empty("Working a Sunday, a weekly off or a holiday records an extra day here: ${Fmt.num(r.alCfg().compOffFullHours)} h earns a full day, ${Fmt.num(r.alCfg().compOffHalfHours)} h half of one.")
        co.forEach { c ->
            ListRow(Fmt.date(c.date) + " · " + c.kind, Fmt.duration(c.minutes.toDouble()) + " worked → " + Fmt.daysLabel(c.credit) +
                (if (c.approverId.isNotBlank()) " · with " + r.name(c.approverId) else "") + (if (c.remarks.isNotBlank()) "\n“${c.remarks}”" else "")) {
                Chip(c.status, statusTone(c.status))
            }
        }
    }
}

@Composable
fun LeaveRow(r: HrRules, l: LeaveRequest, showName: Boolean = false, onClick: () -> Unit) {
    val cat = LEAVE_CATEGORY_LABEL[r.categoryOf(l)] ?: ""
    ListRow((if (showName) r.name(l.userId) + " · " else "") + Fmt.date(l.from) + (if (l.to.isNotBlank() && l.to != l.from) " – " + Fmt.date(l.to) else ""),
        "$cat · ${l.type} · ${Fmt.daysLabel(r.leaveDays(l))}" + (if (l.session.isNotBlank()) " (${l.session})" else "") +
            (if (l.status == "Pending") "\nWith " + r.name(l.approverId) else "") + (if (l.remarks.isNotBlank()) "\n“${l.remarks}”" else ""),
        onClick) { Chip(l.status, statusTone(l.status)) }
}

@Composable
fun LeaveRequestScreen(vm: AppVm, lockDate: String?, onBack: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val r = vm.rules(data)
    val u = r.me ?: return
    var type by remember { mutableStateOf("") }
    var from by remember { mutableStateOf(lockDate ?: r.today) }
    var to by remember { mutableStateOf(lockDate ?: r.today) }
    var duration by remember { mutableStateOf("Full Day") }
    var category by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var delegated by remember { mutableStateOf("") }
    var handover by remember { mutableStateOf("") }
    var delEmail by remember { mutableStateOf("") }
    val tasks = remember { mutableStateListOf("", "", "") }
    val cats = r.categoriesFor(u.id, from.take(4))
    LaunchedEffect(cats) { if (category !in cats) category = if (CAT_PAID in cats) CAT_PAID else "" }

    Screen("Leave request", onBack = onBack) {
        Section("Employee") {
            LabelValue("Name", u.name); LabelValue("Employee ID", u.employeeId.ifBlank { u.id })
            LabelValue("Department", u.department.ifBlank { "—" }); LabelValue("Designation", u.designation.ifBlank { u.role })
            LabelValue("Branch", u.branch.ifBlank { "—" }); LabelValue("Reporting manager", r.manager(u)?.name ?: "—")
        }
        Section("Leave") {
            Dropdown("Leave Type", type, r.leaveFormTypes().map { it to it }, { type = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField("Start Date", from, { from = it; if (to < it) to = it }, Modifier.weight(1f), enabled = lockDate == null)
                DateField("End Date", to, { to = it }, Modifier.weight(1f), enabled = lockDate == null)
            }
            Dropdown("Duration", duration, LEAVE_DURATIONS.map { it to it }, { duration = it; if (it.startsWith("Half")) to = from })
            Dropdown("Leave Category", category, cats.map { it to LEAVE_CATEGORY_LABEL[it]!! }, { category = it })
            Note(r.categoryHelp(u.id, from.take(4)))
            if (from.isNotBlank()) {
                val probe = LeaveRequest("p", u.id, from, to, type, category, if (duration.startsWith("Half")) "half" else "full")
                val days = r.leaveDays(probe)
                val charge = if (category.isNotBlank() && category != CAT_UNPAID) r.chargeDays(probe) else null
                Note("Asking for " + Fmt.daysLabel(days) + "." + (if (charge != null && charge != days)
                    " ${Fmt.daysLabel(charge)} of it is charged to ${LEAVE_CATEGORY_LABEL[category]} (Sundays, weekly offs and holidays are not)." else ""))
                if (r.cfg.leaveBalance.enabled && type.isNotBlank()) Note(r.typeBalanceLine(u.id, type))
                val problem = if (category.isNotBlank()) r.balanceProblem(probe, false) else ""
                if (problem.isNotEmpty()) Note(problem + " Choose fewer days, or apply as Unpaid Leave.", "danger")
                r.leaveClash(u.id, from, to)?.let { Note("You already have ${it.status.lowercase()} leave from ${Fmt.date(it.from)} to ${Fmt.date(it.to.ifBlank { it.from })} that overlaps.", "danger") }
            }
            Field("Reason", reason, { reason = it }, minLines = 2)
        }
        Section("Delegate your pending tasks") {
            Dropdown("Have you delegated your pending tasks?", delegated, listOf("Yes" to "Yes", "No" to "No"), { delegated = it })
            if (delegated == "Yes") {
                Dropdown("To whom?", handover, data.team.filter { it.isActive && it.id != u.id }.map { it.id to it.name }, {
                    handover = it; delEmail = data.team.find { t -> t.id == it }?.email ?: delEmail
                })
                Field("Delegated person email", delEmail, { delEmail = it })
                (0..2).forEach { i -> Field("Delegated task #${i + 1}", tasks[i], { tasks[i] = it }) }
            }
        }
        val approver = r.approverFor(u.id)
        Note(if (approver != null) "This goes to ${r.name(approver.id)} (${approver.why}) and shows as Pending until they answer."
            else "There is nobody on file to approve this. Ask HR to set your reporting manager or a branch head.", if (approver == null) "danger" else "")
        Button(onClick = {
            vm.act(onOk = { onBack() }) {
                vm.service.submitLeave(u.id, LeaveForm(type, from, to, duration, category, reason, delegated, handover, delEmail, tasks.toList(), lockDate))
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Submit request") }
    }
}

@Composable
fun LeaveDetailScreen(vm: AppVm, id: String, onBack: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val r = vm.rules(data)
    val l = data.hrLeave.find { it.id == id }
    var decide by remember { mutableStateOf<Boolean?>(null) }
    Screen("Leave request", onBack = onBack) {
        if (l == null || !r.maySeeUser(l.userId) && l.approverId != r.currentUserId) { Empty("Not found."); return@Screen }
        Section(r.name(l.userId), trailing = { Chip(l.status, statusTone(l.status)) }) {
            LabelValue("Dates", Fmt.date(l.from) + (if (l.to.isNotBlank() && l.to != l.from) " – " + Fmt.date(l.to) else ""))
            LabelValue("Duration", l.duration.ifBlank { if (l.unit == "half") "Half Day" else "Full Day" } + " · " + Fmt.daysLabel(r.leaveDays(l)))
            LabelValue("Category", LEAVE_CATEGORY_LABEL[r.categoryOf(l)] ?: "")
            LabelValue("Charged", if (r.categoryOf(l) == CAT_UNPAID) "nothing (unpaid)" else Fmt.daysLabel(l.charged ?: r.chargeDays(l)))
            LabelValue("Type", l.type)
            LabelValue("Reason", l.reason.ifBlank { "—" })
            LabelValue("Approver", r.name(l.approverId) + " — " + l.approverWhy)
            LabelValue("Requested", Fmt.date(l.requestedAt) + " " + r.clock(l.requestedAt))
            if (l.decidedBy.isNotBlank()) LabelValue("Decided", r.name(l.decidedBy) + ", " + Fmt.date(l.decidedAt) + if (l.remarks.isNotBlank()) " — “${l.remarks}”" else "")
            if (l.delegated == "Yes") LabelValue("Delegated to", r.name(l.handoverTo) + " (" + l.delegateEmail + "): " + l.delegatedTasks.filter { it.isNotBlank() }.joinToString("; "))
            l.cancellation?.let { c -> LabelValue("Cancelled", c.dates.joinToString(", ") { Fmt.date(it) } + " by " + r.name(c.by) + " — “${c.reason}”") }
            if (l.remainderOf.isNotBlank()) Note("Left after a part cancellation.")
        }
        if (r.mayDecideLeave(l)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { decide = true }, Modifier.weight(1f)) { Text("Approve") }
            OutlinedButton(onClick = { decide = false }, Modifier.weight(1f)) { Text("Decline") }
        }
        if (r.mayCancelLeave(l)) CancelSection(vm, r, l, onBack)
    }
    decide?.let { yes ->
        DecisionDialog((if (yes) "Approve" else "Decline") + " leave — " + r.name(l!!.userId), yes,
            details = { Note(r.balanceProblem(l, true).ifBlank { r.categoryHelp(l.userId, l.from.take(4)) }) },
            onDismiss = { decide = null }) { note -> decide = null; vm.act { vm.service.decideLeave(r.currentUserId!!, l.id, yes, note) } }
    }
}

@Composable
private fun CancelSection(vm: AppVm, r: HrRules, l: LeaveRequest, onBack: () -> Unit) {
    val dates = r.leaveDatesOf(l)
    var some by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<String>() }
    var reason by remember { mutableStateOf("") }
    Section("Cancel this leave") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(!some, { some = false }); Text("All of it")
            Spacer(Modifier.width(12.dp))
            RadioButton(some, { some = true }, enabled = dates.size > 1); Text("Some days")
        }
        if (some) dates.forEach { d ->
            val allowed = r.isHrAdmin() || d >= r.today
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(d in picked, { if (it) picked.add(d) else picked.remove(d) }, enabled = allowed)
                Text(Fmt.date(d) + (if (r.offDayKind(d).isNotEmpty()) " · " + r.offDayKind(d) else "") + if (!allowed) " (passed)" else "")
            }
        }
        Field("Why are you cancelling?", reason, { reason = it }, minLines = 2)
        OutlinedButton(onClick = {
            vm.act(onOk = { onBack() }) { vm.service.cancelLeave(r.currentUserId!!, l.id, if (some) picked.toList() else dates, reason) }
        }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Cancel leave", fontWeight = FontWeight.SemiBold) }
    }
}
