package com.finowise.hr.ui.more

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.finowise.hr.AppVm
import com.finowise.hr.core.Fmt
import com.finowise.hr.core.HrRules
import com.finowise.hr.ui.common.*

@Composable
fun MoreTab(vm: AppVm, nav: NavController, r: HrRules) {
    val u = r.me ?: return
    Section(u.name) {
        Note(u.email + " · " + u.role + (if (u.branch.isNotBlank()) " · " + u.branch else ""))
        Note("HR access: " + com.finowise.hr.core.Grants.ALL.filter { r.has(it.first) }.joinToString(", ") { it.second })
    }
    Section("HR") {
        ListRow("Notifications", "Requests, decisions and delegations", { nav.navigate("notifications") })
        if (r.isStaff()) {
            ListRow("Month board", "Everybody's attendance, month by month", { nav.navigate("monthBoard") })
            ListRow("Remaining leave balance", "Paid, comp-off and unpaid for every employee", { nav.navigate("balances") })
        }
        if (r.moduleOpen("hrincentive")) ListRow("Incentive management", null, { nav.navigate("incentive") })
        if (r.moduleOpen("hrappraisal")) ListRow("Appraisal management", null, { nav.navigate("appraisal") })
    }
    if (r.isHrAdmin()) Section("Administration") {
        ListRow("Employees", "Add people, set roles, managers, CTC and HR access", { nav.navigate("employees") })
        ListRow("HR Settings", "Working day, punch window, holidays, leave and comp-off rules", { nav.navigate("settings") })
        if (r.moduleOpen("hrincentive")) ListRow("Incentive grid", "Factors, slabs, rates and the task bank", { nav.navigate("incentiveSettings") })
        if (r.moduleOpen("hrappraisal")) ListRow("Appraisal settings", "Role mapping, weights and company growth", { nav.navigate("appraisalSettings") })
        ListRow("Audit log", "Who changed what, and when", { nav.navigate("audit") })
        ListRow("Backup & restore", "Export all data as JSON, or restore a backup", { nav.navigate("backup") })
    }
    Section("Account") {
        ListRow("Change password", null, { nav.navigate("password") })
        ListRow("Sign out", null, { vm.signOut() })
    }
    Note("Finowise HR 1.0 · all data is stored on this device.")
}

@Composable
fun NotificationsScreen(vm: AppVm, onBack: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val r = vm.rules(data)
    val mine = data.notifications.filter { it.userId == r.currentUserId }.sortedByDescending { it.at }
    DisposableEffect(Unit) { onDispose { vm.launch { vm.service.markNotificationsRead(r.currentUserId!!) } } }
    Screen("Notifications", onBack = onBack) {
        if (mine.isEmpty()) Empty("Nothing yet.")
        mine.take(200).forEach { n ->
            ListRow((if (!n.read) "● " else "") + n.title, n.body + "\n" + Fmt.date(n.at) + " " + r.clock(n.at))
        }
    }
}

@Composable
fun AuditScreen(vm: AppVm, onBack: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val r = vm.rules(data)
    var q by remember { mutableStateOf("") }
    Screen("Audit log", onBack = onBack) {
        if (!r.isHrAdmin()) { Empty("For HR administrators."); return@Screen }
        Field("Search", q, { q = it })
        data.auditLog.asReversed().asSequence()
            .filter { a -> q.isBlank() || a.action.contains(q, true) || r.name(a.by).contains(q, true) || a.fields.any { it.newValue.contains(q, true) } }
            .take(300).forEach { a ->
                ListRow(a.action + " · " + r.name(a.by), Fmt.date(a.at) + " " + r.clock(a.at) + "\n" +
                    a.fields.joinToString("\n") { f -> f.field + ": " + (if (f.oldValue.isNotBlank()) f.oldValue + " → " else "") + f.newValue })
            }
    }
}

@Composable
fun RestoreButton(vm: AppVm) {
    val ctx = LocalContext.current
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrElse { vm.say("Could not read that file."); return@rememberLauncherForActivityResult }
        vm.act { vm.service.importJson(vm.userId.value, text) }
    }
    OutlinedButton(onClick = { open.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Restore from backup file") }
}

@Composable
fun BackupScreen(vm: AppVm, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val r = vm.rules()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(vm.service.exportJson().toByteArray()) } }
            .onSuccess { vm.say("Backup saved.") }.onFailure { vm.say("Could not save: ${it.message}") }
    }
    Screen("Backup & restore", onBack = onBack) {
        if (!r.isHrAdmin()) { Empty("For HR administrators."); return@Screen }
        Section("Back up") {
            Note("Everything — employees, settings, attendance, leave, comp-off, regularizations, incentive sheets and appraisals — in one JSON file. " +
                "The record shapes are BOSS's, so the HR stores line up with the web app's.")
            Button(onClick = { save.launch("finowise-hr-backup-${r.today}.json") }, modifier = Modifier.fillMaxWidth()) { Text("Save backup file") }
        }
        Section("Restore") {
            Note("Replaces everything on this device with the backup. Take a backup first.", "warn")
            RestoreButton(vm)
        }
    }
}

@Composable
fun PasswordScreen(vm: AppVm, onBack: () -> Unit) {
    var cur by remember { mutableStateOf("") }
    var n1 by remember { mutableStateOf("") }
    var n2 by remember { mutableStateOf("") }
    Screen("Change password", onBack = onBack) {
        Field("Current password", cur, { cur = it }, password = true)
        Field("New password", n1, { n1 = it }, password = true)
        Field("Repeat new password", n2, { n2 = it }, password = true)
        Button(onClick = {
            if (n1 != n2) vm.say("The two new passwords are different.")
            else vm.act(onOk = { onBack() }) { vm.service.changePassword(vm.userId.value!!, cur, n1) }
        }, modifier = Modifier.fillMaxWidth()) { Text("Change password") }
        Spacer(Modifier.height(8.dp))
    }
}
