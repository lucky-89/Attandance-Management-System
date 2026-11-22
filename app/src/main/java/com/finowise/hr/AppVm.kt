package com.finowise.hr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finowise.hr.core.HrRules
import com.finowise.hr.core.Outcome
import com.finowise.hr.core.model.Employee
import com.finowise.hr.core.model.HrConfig
import com.finowise.hr.core.model.HrData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** The app's state: the data, who is signed in, the clock, and the message to show. */
class AppVm(app: Application) : AndroidViewModel(app) {
    val hr = app as HrApp
    val service = hr.service
    /** True when the data is shared through Supabase. */
    val cloud = hr.api != null
    val data = MutableStateFlow(service.data)
    val userId = MutableStateFlow(initialUser())
    /** Cloud mode: signed in to Supabase but not yet linked to a firm. */
    val needsJoin = MutableStateFlow(cloud && hr.cloud.load() != null && hr.cloud.membership == null)
    val now = MutableStateFlow(Instant.now())
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val syncError = MutableStateFlow<String?>(null)
    private val listener: (HrData) -> Unit = { data.value = it }

    private fun initialUser(): String? =
        if (cloud) hr.cloud.membership?.employeeId?.takeIf { hr.cloud.load() != null }
        else hr.session.userId?.takeIf { id -> service.data.team.any { it.id == id && it.isActive } }

    init {
        service.addListener(listener)
        viewModelScope.launch { while (true) { delay(15_000); now.value = Instant.now() } }
        if (cloud) viewModelScope.launch { while (true) { if (userId.value != null) sync(); delay(30_000) } }
        else userId.value?.let { u -> viewModelScope.launch(Dispatchers.Default) { service.compOffSweep(u) } }
    }

    override fun onCleared() { service.removeListener(listener) }

    fun rules(d: HrData = data.value, at: Instant = now.value) = HrRules(d, userId.value, at, service.zone)
    fun me() = data.value.team.find { it.id == userId.value }
    fun refreshClock() { now.value = Instant.now(); if (cloud && userId.value != null) launch { sync() } }

    /** Pulls the shared data; in cloud mode it also records any extra days worked. */
    suspend fun sync() {
        val o = withContext(Dispatchers.IO) { service.refresh() }
        syncError.value = if (o.ok) null else o.message
        if (o.ok) userId.value?.let { u -> withContext(Dispatchers.IO) { service.compOffSweep(u) } }
        if (!o.ok && o.message.contains("Sign in again")) signOut()
    }

    fun signedIn(id: String) {
        hr.session.userId = id; userId.value = id
        viewModelScope.launch(Dispatchers.Default) { service.compOffSweep(id) }
    }

    fun signOut() {
        if (cloud) {
            viewModelScope.launch(Dispatchers.IO) { hr.api?.signOut() }
            hr.cloud.save(null); hr.cloud.membership = null; hr.clearCloudCache()
            needsJoin.value = false
        }
        hr.session.userId = null; userId.value = null
    }
    fun say(msg: String?) { message.value = msg }

    /** Runs a change off the main thread and shows what it said. In cloud mode the data is refreshed first. */
    fun act(showOk: Boolean = true, onOk: (Outcome) -> Unit = {}, block: () -> Outcome) {
        viewModelScope.launch {
            busy.value = true
            val o = try {
                withContext(Dispatchers.IO) {
                    if (cloud && userId.value != null) service.refresh().takeIf { !it.ok }?.let { return@withContext it }
                    block()
                }
            } catch (e: Exception) { Outcome.fail(e.message ?: "Something went wrong.") }
            busy.value = false
            now.value = Instant.now()
            if (!o.ok || (showOk && o.message.isNotBlank())) message.value = o.message
            if (o.ok) onOk(o)
        }
    }
    fun launch(block: suspend () -> Unit) { viewModelScope.launch { block() } }

    // ---------------- cloud sign-in and joining ----------------
    private fun cloudAct(block: () -> Outcome) {
        viewModelScope.launch {
            busy.value = true
            val o = try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) { Outcome.fail(e.message ?: "Something went wrong.") }
            busy.value = false
            if (o.message.isNotBlank()) message.value = o.message
        }
    }

    /** After signing in: link to the firm whose team lists this email, if there is one. */
    private fun join(): Outcome {
        val m = hr.remote!!.claim()
        if (m == null) { needsJoin.value = true; return Outcome.ok() }
        hr.cloud.membership = m
        service.refresh().takeIf { !it.ok }?.let { return it }
        needsJoin.value = false
        userId.value = m.employeeId
        service.compOffSweep(m.employeeId)
        return Outcome.ok()
    }

    fun cloudSignIn(email: String, password: String) = cloudAct { hr.api!!.signIn(email, password); join() }

    fun cloudSignUp(email: String, password: String) = cloudAct {
        if (password.length < 6) return@cloudAct Outcome.fail("A password needs at least 6 characters.")
        if (!hr.api!!.signUp(email, password))
            Outcome.ok("Account created.\n\nOpen the confirmation link Supabase emailed to $email, then come back and sign in.")
        else join()
    }

    fun cloudRecover(email: String) = cloudAct {
        if (email.isBlank()) return@cloudAct Outcome.fail("Enter your email first.")
        hr.api!!.recover(email); Outcome.ok("If $email has an account, a password reset link is on its way.")
    }

    fun checkJoinAgain() = cloudAct { join().let { if (it.ok && needsJoin.value) Outcome.fail("No firm has added ${hr.api!!.session?.email} yet. Ask HR to add you with exactly this email.") else it } }

    fun createFirm(firmName: String, yourName: String) = cloudAct {
        if (firmName.isBlank() || yourName.isBlank()) return@cloudAct Outcome.fail("Enter the firm's name and your name.")
        val email = hr.api!!.session?.email ?: ""
        val admin = Employee(id = service.uid("TM"), name = yourName.trim(), email = email, employeeId = "EMP001", role = "Admin",
            designation = "Administrator", createdAt = Instant.now().toString())
        val m = hr.remote!!.createFirm(firmName.trim(), ZoneId.systemDefault().id, admin, HrConfig(firmName = firmName.trim()))
        hr.cloud.membership = m
        service.refresh().takeIf { !it.ok }?.let { return@cloudAct it }
        needsJoin.value = false
        userId.value = m.employeeId
        Outcome.ok("${m.firmName} is set up. Add your team under More › Employees; each person then creates an account in the app with the email you entered for them.")
    }

    fun cloudChangePassword(current: String, next: String, onOk: () -> Unit) = cloudAct {
        if (next.length < 6) return@cloudAct Outcome.fail("A password needs at least 6 characters.")
        val email = hr.api!!.session?.email ?: return@cloudAct Outcome.fail("Sign in first.")
        try { hr.api!!.signIn(email, current) } catch (e: Exception) { return@cloudAct Outcome.fail("Your current password is not right.") }
        hr.api!!.updatePassword(next)
        viewModelScope.launch { onOk() }
        Outcome.ok("Password changed.")
    }
}
