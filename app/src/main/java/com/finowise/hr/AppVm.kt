package com.finowise.hr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finowise.hr.core.HrRules
import com.finowise.hr.core.Outcome
import com.finowise.hr.core.model.HrData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** The app's state: the data, who is signed in, the clock, and the message to show. */
class AppVm(app: Application) : AndroidViewModel(app) {
    private val hr = app as HrApp
    val service = hr.service
    val data = MutableStateFlow(service.data)
    val userId = MutableStateFlow(hr.session.userId?.takeIf { id -> service.data.team.any { it.id == id && it.isActive } })
    val now = MutableStateFlow(Instant.now())
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    private val listener: (HrData) -> Unit = { data.value = it }

    init {
        service.addListener(listener)
        viewModelScope.launch { while (true) { delay(15_000); now.value = Instant.now() } }
        userId.value?.let { u -> viewModelScope.launch(Dispatchers.Default) { service.compOffSweep(u) } }
    }

    override fun onCleared() { service.removeListener(listener) }

    fun rules(d: HrData = data.value, at: Instant = now.value) = HrRules(d, userId.value, at, service.zone)
    fun me() = data.value.team.find { it.id == userId.value }
    fun refreshClock() { now.value = Instant.now() }

    fun signedIn(id: String) {
        hr.session.userId = id; userId.value = id
        viewModelScope.launch(Dispatchers.Default) { service.compOffSweep(id) }
    }
    fun signOut() { hr.session.userId = null; userId.value = null }
    fun say(msg: String?) { message.value = msg }

    /** Runs a change off the main thread and shows what it said. */
    fun act(showOk: Boolean = true, onOk: (Outcome) -> Unit = {}, block: () -> Outcome) {
        viewModelScope.launch {
            busy.value = true
            val o = try { withContext(Dispatchers.Default) { block() } } catch (e: Exception) { Outcome.fail("Something went wrong: ${e.message}") }
            busy.value = false
            now.value = Instant.now()
            if (!o.ok || (showOk && o.message.isNotBlank())) message.value = o.message
            if (o.ok) onOk(o)
        }
    }
    fun launch(block: suspend () -> Unit) { viewModelScope.launch { block() } }
}
