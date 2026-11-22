package com.finowise.hr.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finowise.hr.AppVm
import com.finowise.hr.ui.common.Field
import com.finowise.hr.ui.common.Note
import com.finowise.hr.ui.more.RestoreButton

@Composable
private fun AuthFrame(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            Text("Finowise HR", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Note(subtitle)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun SetupScreen(vm: AppVm) {
    var firm by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    AuthFrame("Set up your firm", "This device keeps all HR data itself — no server. You become the administrator; add your team next.") {
        Field("Firm name", firm, { firm = it })
        Field("Your name", name, { name = it })
        Field("Your email (you sign in with it)", email, { email = it })
        Field("Password", pw, { pw = it }, password = true)
        Field("Repeat password", pw2, { pw2 = it }, password = true)
        Button(onClick = {
            if (pw != pw2) vm.say("The two passwords are different.")
            else vm.act(showOk = false, onOk = { vm.signedIn(it.id!!) }) { vm.service.setupFirm(firm, name, email, pw) }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Create firm") }
        Note("Already have a backup from another device? Sign in after restoring it from More › Backup — or restore now:")
        RestoreButton(vm)
    }
}

@Composable
fun LoginScreen(vm: AppVm) {
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val firm = vm.data.collectAsStateWithLifecycle().value.hrConfig.firmName
    AuthFrame("Sign in", firm.ifBlank { "Attendance, leave, incentive and appraisal" }) {
        Field("Email", email, { email = it })
        Field("Password", pw, { pw = it }, password = true)
        Button(onClick = { vm.act(showOk = false, onOk = { vm.signedIn(it.id!!) }) { vm.service.signIn(email, pw) } },
            enabled = !busy && email.isNotBlank() && pw.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
        Note("Forgot your password? An HR administrator sets a new one for you in Employees.")
    }
}
