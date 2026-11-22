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
import com.finowise.hr.ui.common.Section

@Composable
private fun Frame(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp))
            Text("Finowise HR", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Note(subtitle)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

/** First launch: share the firm's data through Supabase, or keep it on this device. */
@Composable
fun ModeScreen(vm: AppVm) {
    var url by remember { mutableStateOf(vm.hr.cloud.url) }
    var key by remember { mutableStateOf(vm.hr.cloud.anonKey) }
    Frame("How will your firm use the app?", "Choose once; you can change it later from More.") {
        Section("Share with my team (recommended)") {
            Note("Everyone signs in on their own phone; attendance, leave and approvals are shared through your free Supabase project. " +
                "Paste its Project URL and its publishable (or legacy anon) key from Supabase › Project Settings › API Keys.")
            Field("Project URL (https://….supabase.co)", url, { url = it })
            Field("Publishable key (sb_publishable_…) or anon key (eyJ…)", key, { key = it }, minLines = 2)
            Button(onClick = {
                when {
                    !Regex("^https://[^\\s/]+").containsMatchIn(url.trim()) -> vm.say("The Project URL starts with https:// and ends with .supabase.co.")
                    key.trim().length < 30 -> vm.say("Paste the whole key — it starts with sb_publishable_ or eyJ.")
                    else -> { vm.hr.cloud.url = url; vm.hr.cloud.anonKey = key; vm.hr.cloud.mode = "cloud"; vm.hr.restart() }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
        }
        Section("This device only") {
            Note("One phone or tablet holds everything — for example an office tablet everyone signs into. No internet needed.")
            OutlinedButton(onClick = { vm.hr.cloud.mode = "local"; vm.hr.restart() }, modifier = Modifier.fillMaxWidth()) { Text("Use on this device only") }
        }
    }
}

@Composable
fun CloudAuthScreen(vm: AppVm) {
    var creating by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    Frame(if (creating) "Create your account" else "Sign in",
        if (creating) "Use the email HR added you with. Setting up a new firm? Create your account first, then set the firm up."
        else "Attendance, leave, incentive and appraisal — shared with your team.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!creating, { creating = false }, label = { Text("Sign in") })
            FilterChip(creating, { creating = true }, label = { Text("Create account") })
        }
        Field("Email", email, { email = it })
        Field("Password", pw, { pw = it }, password = true)
        if (creating) Field("Repeat password", pw2, { pw2 = it }, password = true)
        Button(onClick = {
            if (creating) { if (pw != pw2) vm.say("The two passwords are different.") else vm.cloudSignUp(email, pw) }
            else vm.cloudSignIn(email, pw)
        }, enabled = !busy && email.isNotBlank() && pw.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (creating) "Create account" else "Sign in")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!creating) TextButton(onClick = { vm.cloudRecover(email) }) { Text("Forgot password? Email me a reset link") }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { vm.hr.cloud.mode = null; vm.hr.restart() }) { Text("Change server or mode") }
    }
}

/** Signed in to Supabase, but this email is not on any firm's team yet. */
@Composable
fun JoinScreen(vm: AppVm) {
    var firm by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val email = vm.hr.api?.session?.email ?: ""
    Frame("Join your firm", "Signed in as $email") {
        Section("Joining an existing firm?") {
            Note("Ask HR to add you under More › Employees with exactly this email ($email), then tap Check again.")
            Button(onClick = { vm.checkJoinAgain() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Check again") }
        }
        Section("Setting up a new firm?") {
            Note("You become its administrator. Do this once, for the whole firm.")
            Field("Firm name", firm, { firm = it })
            Field("Your name", name, { name = it })
            OutlinedButton(onClick = { vm.createFirm(firm, name) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Create the firm") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = { vm.signOut() }) { Text("Sign out") }
    }
}

@Composable
fun LoadingScreen(vm: AppVm) {
    val err by vm.syncError.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.sync() }
    Frame("Loading your firm…", err ?: "Fetching the latest from the server.") {
        if (err == null) CircularProgressIndicator()
        else { Button(onClick = { vm.launch { vm.sync() } }) { Text("Try again") }; TextButton(onClick = { vm.signOut() }) { Text("Sign out") } }
    }
}
