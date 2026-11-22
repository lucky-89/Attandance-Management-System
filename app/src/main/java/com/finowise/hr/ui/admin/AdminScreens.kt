package com.finowise.hr.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.finowise.hr.AppVm
import com.finowise.hr.core.Grants
import com.finowise.hr.core.HrRules
import com.finowise.hr.core.model.*
import com.finowise.hr.ui.common.*

@Composable
fun EmployeesScreen(vm: AppVm, nav: NavController) {
    val data by vm.data.collectAsStateWithLifecycle()
    val r = vm.rules(data)
    var q by remember { mutableStateOf("") }
    var showInactive by remember { mutableStateOf(false) }
    Screen("Employees", onBack = { nav.popBackStack() }, fab = {
        if (r.isHrAdmin()) FloatingActionButton(onClick = { nav.navigate("employee/new") }) { Icon(Icons.Default.PersonAdd, "Add") }
    }) {
        if (!r.isHrAdmin()) { Empty("Managing employees is an HR administrator's."); return@Screen }
        Field("Search", q, { q = it })
        SwitchRow("Show inactive", showInactive, { showInactive = it })
        data.team.filter { showInactive || it.isActive }
            .filter { e -> q.isBlank() || listOf(e.name, e.email, e.employeeId, e.role, e.branch, e.department).any { it.contains(q, true) } }
            .sortedBy { it.name.lowercase() }
            .forEach { e ->
                val g = Grants.ALL.filter { r.has(it.first, e) }.joinToString(", ") { it.first }
                ListRow(e.name + (if (e.employeeId.isNotBlank()) " · " + e.employeeId else ""),
                    listOf(e.role, e.branch, e.department, e.email).filter { it.isNotBlank() }.joinToString(" · ") +
                        "\nHR: " + g.ifBlank { "none" } + (if (e.parentUserId.isNotBlank()) " · reports to " + r.name(e.parentUserId) else ""),
                    { nav.navigate("employee/${e.id}") }) { if (!e.isActive) Chip("Inactive", "muted") else if (e.isAdmin) Chip("Admin", "info") }
            }
    }
}

@Composable
fun EmployeeEditScreen(vm: AppVm, id: String, onBack: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val r = vm.rules(data)
    val existing = data.team.find { it.id == id }
    var e by remember { mutableStateOf(existing ?: Employee(id = "", role = data.hrConfig.roles.lastOrNull() ?: "Executive")) }
    var pw by remember { mutableStateOf("") }
    val cfg = data.hrConfig
    Screen(if (existing == null) "Add employee" else e.name, onBack = onBack) {
        if (!r.isHrAdmin()) { Empty("Managing employees is an HR administrator's."); return@Screen }
        Section("Person") {
            Field("Full name", e.name, { e = e.copy(name = it) })
            Field("Email (sign-in)", e.email, { e = e.copy(email = it) })
            Field("Employee ID", e.employeeId, { e = e.copy(employeeId = it) })
            Field("Mobile", e.mobile, { e = e.copy(mobile = it) })
            DateField("Joining date", e.joiningDate, { e = e.copy(joiningDate = it) }, Modifier.fillMaxWidth())
        }
        Section("Position") {
            Dropdown("Role", e.role, (cfg.roles + e.role).filter { it.isNotBlank() }.distinct().map { it to it }, { e = e.copy(role = it) })
            Note("“Admin” is a full administrator. A role with “branch” in its name approves leave for its branch unless HR Settings names another.")
            Field("Designation", e.designation, { e = e.copy(designation = it) })
            Dropdown("Department", e.department, (listOf("") + cfg.departments + e.department).distinct().map { it to it.ifBlank { "—" } }, { e = e.copy(department = it) })
            Dropdown("Branch", e.branch, (listOf("") + cfg.branches + e.branch).distinct().map { it to it.ifBlank { "—" } }, { e = e.copy(branch = it) })
            Dropdown("Reporting manager", e.parentUserId, listOf("" to "—") + data.team.filter { it.id != e.id && it.isActive }.map { it.id to it.name },
                { e = e.copy(parentUserId = it) })
            Field("Annual CTC (₹)", e.ctc?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "",
                { e = e.copy(ctc = it.toDoubleOrNull()) }, number = true, supporting = "Read by Incentive and Appraisal.")
            Dropdown("Incentive category", e.incentiveCategory, listOf("" to "Follow the rules (Incentive grid)", "sales" to "Sales", "nonSales" to "Non-sales"),
                { e = e.copy(incentiveCategory = it) })
            Dropdown("Appraisal KPI set", e.appraisalRoleKey, listOf("" to "Follow the mapping (Appraisal settings)") +
                com.finowise.hr.core.appraisal.Appraisals.effectiveConfig(null).roles.map { it.key to it.value.label }, { e = e.copy(appraisalRoleKey = it) })
            Dropdown("Status", e.status, listOf("Active" to "Active", "Inactive" to "Inactive"), { e = e.copy(status = it) })
        }
        Section("HR access") {
            if (e.isAdmin) Note("An Admin holds all five.")
            Grants.ALL.forEach { (k, label, note) ->
                val on = HrRules(data, null).has(k, e)
                SwitchRow(label, on, { v -> e = e.copy(hrAccess = e.hrAccess + (k to if (v) 1 else 0)) }, note)
            }
        }
        if (vm.cloud) Section("Signing in") {
            Note(if (existing == null) "After you save, they install the app, tap Create account and sign up with the email above. " +
                "Their account links to this record by itself. Nobody else can claim it."
                else "They sign in with ${e.email}. If they forget their password, they tap “Forgot password” on the sign-in screen.")
        } else Section(if (existing == null) "First password" else "Set a new password") {
            Field("Password", pw, { pw = it }, password = true,
                supporting = if (existing == null) "They sign in with their email and this password." else "Leave blank to keep their current password.")
        }
        Button(onClick = { vm.act(onOk = { onBack() }) { vm.service.saveEmployee(r.currentUserId!!, e, pw.ifBlank { null }) } },
            modifier = Modifier.fillMaxWidth()) { Text("Save") }
    }
}

@Composable
fun HrSettingsScreen(vm: AppVm, onBack: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val r = vm.rules(data)
    var c by remember { mutableStateOf(data.hrConfig) }
    var holidays by remember { mutableStateOf(data.hrConfig.holidays.joinToString("\n") { it.date + " " + it.name }) }
    var types by remember { mutableStateOf(data.hrConfig.leaveTypes.joinToString("\n") { it.name + if (it.unit == "half") " | half" else "" }) }
    var roles by remember { mutableStateOf(data.hrConfig.roles.joinToString("\n")) }
    var depts by remember { mutableStateOf(data.hrConfig.departments.joinToString("\n")) }
    var branches by remember { mutableStateOf(data.hrConfig.branches.joinToString("\n")) }
    fun lines(s: String) = s.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    Screen("HR Settings", onBack = onBack) {
        if (!r.isHrAdmin()) { Empty("HR configuration is an administrator’s to change."); return@Screen }
        Note("A rule left blank is not applied: nothing assumes an office start time or a grace period until it is set here.")
        Section("Firm") {
            Field("Firm name", c.firmName, { c = c.copy(firmName = it) })
            Field("Roles (one per line)", roles, { roles = it }, minLines = 3)
            Field("Departments (one per line)", depts, { depts = it }, minLines = 2)
            Field("Branches (one per line)", branches, { branches = it }, minLines = 2)
        }
        Section("Working day") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField("Office starts", c.day.start, { c = c.copy(day = c.day.copy(start = it)) }, Modifier.weight(1f))
                TimeField("Office ends", c.day.end, { c = c.copy(day = c.day.copy(end = it)) }, Modifier.weight(1f))
            }
            if (c.day.start.isNotBlank()) TextButton(onClick = { c = c.copy(day = c.day.copy(start = "", end = "")) }) { Text("Clear office times (no lateness shown)") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Grace (minutes)", c.day.graceMinutes, { c = c.copy(day = c.day.copy(graceMinutes = it)) }, Modifier.weight(1f), number = true)
                Field("Full day (hours)", c.day.fullDayHours, { c = c.copy(day = c.day.copy(fullDayHours = it)) }, Modifier.weight(1f), number = true, supporting = "9 when blank")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField("Punch In opens", c.day.punchInFrom, { c = c.copy(day = c.day.copy(punchInFrom = it)) }, Modifier.weight(1f))
                TimeField("Punch clock closes", c.day.punchOutUntil, { c = c.copy(day = c.day.copy(punchOutUntil = it)) }, Modifier.weight(1f))
            }
            Text("Weekly offs (Sundays are always closed)", style = MaterialTheme.typography.labelLarge)
            FlowRowDays(c.weeklyOff) { c = c.copy(weeklyOff = it) }
            Field("How lateness is read (note)", c.lateRule, { c = c.copy(lateRule = it) }, minLines = 2)
        }
        Section("Holidays") {
            Field("One per line: YYYY-MM-DD Name", holidays, { holidays = it }, minLines = 4)
        }
        Section("Punch location") {
            SwitchRow("Location required to punch", c.location.required, { c = c.copy(location = c.location.copy(required = it)) },
                "On: a punch without a location is refused. Off: it is recorded and says the location is missing. No coordinate is ever invented.")
            Field("Note shown when it is refused", c.location.note, { c = c.copy(location = c.location.copy(note = it)) })
        }
        Section("Reminders") {
            SwitchRow("Punch reminders", c.reminders.enabled, { c = c.copy(reminders = c.reminders.copy(enabled = it)) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField("Not punched in after", c.reminders.inAfter, { c = c.copy(reminders = c.reminders.copy(inAfter = it)) }, Modifier.weight(1f))
                TimeField("Still in after", c.reminders.outAfter, { c = c.copy(reminders = c.reminders.copy(outAfter = it)) }, Modifier.weight(1f))
            }
            Field("Repeat every (minutes)", c.reminders.repeatMinutes.toString(), { v -> v.toIntOrNull()?.let { c = c.copy(reminders = c.reminders.copy(repeatMinutes = it)) } }, number = true)
        }
        Section("Annual leave") {
            Field("Paid Leave days per calendar year", fmt(c.annualLeave.paidPerYear), { v -> v.toDoubleOrNull()?.let { c = c.copy(annualLeave = c.annualLeave.copy(paidPerYear = it)) } }, number = true)
            SwitchRow("Comp-off for working a Sunday, weekly off or holiday", c.annualLeave.compOffEnabled, { c = c.copy(annualLeave = c.annualLeave.copy(compOffEnabled = it)) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Full day at (hours)", fmt(c.annualLeave.compOffFullHours), { v -> v.toDoubleOrNull()?.let { c = c.copy(annualLeave = c.annualLeave.copy(compOffFullHours = it)) } }, Modifier.weight(1f), number = true)
                Field("Half day at (hours)", fmt(c.annualLeave.compOffHalfHours), { v -> v.toDoubleOrNull()?.let { c = c.copy(annualLeave = c.annualLeave.copy(compOffHalfHours = it)) } }, Modifier.weight(1f), number = true)
            }
            Dropdown("Comp-off approver", c.annualLeave.compOffApprover,
                listOf("line" to "Same as leave (branch head / manager)", "hr" to "An HR administrator") + data.team.filter { it.isActive }.map { it.id to it.name },
                { c = c.copy(annualLeave = c.annualLeave.copy(compOffApprover = it)) })
        }
        Section("Leave approval and types") {
            Dropdown("Role that approves leave", c.access.branchHeadRole, listOf("" to "Work it out (a role with “branch” in its name)") + c.roles.map { it to it },
                { c = c.copy(access = c.access.copy(branchHeadRole = it)) })
            Field("Leave types (one per line; add “| half” for half a day)", types, { types = it }, minLines = 3)
            SwitchRow("Track a per-type yearly allowance too", c.leaveBalance.enabled, { c = c.copy(leaveBalance = c.leaveBalance.copy(enabled = it)) })
            if (c.leaveBalance.enabled) r.leaveFormTypes().forEach { t ->
                Field("$t — days per year", c.leaveBalance.perYear[t]?.let(::fmt) ?: "", { v ->
                    c = c.copy(leaveBalance = c.leaveBalance.copy(perYear = if (v.isBlank()) c.leaveBalance.perYear - t else v.toDoubleOrNull()?.let { c.leaveBalance.perYear + (t to it) } ?: c.leaveBalance.perYear))
                }, number = true)
            }
        }
        Button(onClick = {
            val next = c.copy(
                holidays = lines(holidays).map { val b = it.split(Regex("\\s+"), 2); Holiday(b[0], b.getOrElse(1) { "" }) },
                leaveTypes = lines(types).map { val b = it.split("|"); LeaveType(b[0].trim(), if (b.drop(1).joinToString(" ").contains("half", true)) "half" else "full") },
                roles = lines(roles).ifEmpty { listOf("Admin") }.let { if ("Admin" in it) it else listOf("Admin") + it },
                departments = lines(depts), branches = lines(branches))
            vm.act { vm.service.saveConfig(r.currentUserId!!, next) }
        }, modifier = Modifier.fillMaxWidth()) { Text("Save HR settings") }
    }
}

private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowDays(selected: List<String>, onChange: (List<String>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { d ->
            FilterChip(d in selected, { onChange(if (d in selected) selected - d else selected + d) }, label = { Text(d) })
        }
    }
}

@Suppress("unused") private val keepAlign = Alignment.Center
