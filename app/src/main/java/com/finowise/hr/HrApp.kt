package com.finowise.hr

import android.app.Application
import com.finowise.hr.core.HrService
import com.finowise.hr.data.FilePersistence
import com.finowise.hr.data.Reminders
import com.finowise.hr.data.Session

class HrApp : Application() {
    lateinit var service: HrService
        private set
    lateinit var session: Session
        private set

    override fun onCreate() {
        super.onCreate()
        service = HrService(FilePersistence(filesDir))
        session = Session(this)
        Reminders.createChannel(this)
        try { Reminders.schedule(this) } catch (_: Exception) { /* WorkManager unavailable (tests) */ }
    }
}
