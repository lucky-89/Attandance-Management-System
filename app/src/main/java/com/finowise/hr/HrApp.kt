package com.finowise.hr

import android.app.Application
import android.content.Intent
import com.finowise.hr.core.HrService
import com.finowise.hr.core.cloud.CloudRemote
import com.finowise.hr.core.cloud.SupabaseApi
import com.finowise.hr.data.CloudPrefs
import com.finowise.hr.data.FilePersistence
import com.finowise.hr.data.Reminders
import com.finowise.hr.data.Session
import java.io.File

class HrApp : Application() {
    lateinit var service: HrService
        private set
    lateinit var session: Session
        private set
    lateinit var cloud: CloudPrefs
        private set
    /** Set in cloud mode only. */
    var api: SupabaseApi? = null
        private set
    var remote: CloudRemote? = null
        private set

    override fun onCreate() {
        super.onCreate()
        session = Session(this)
        cloud = CloudPrefs(this)
        // An install from before cloud mode keeps working on its own data.
        if (cloud.mode == null && File(filesDir, "hr-data.json").exists()) cloud.mode = "local"
        if (cloud.mode == "cloud" && cloud.url.isNotBlank() && cloud.anonKey.isNotBlank()) {
            val a = SupabaseApi(cloud.url, cloud.anonKey, cloud)
            val r = CloudRemote(a).also { it.firmId = cloud.membership?.firmId }
            api = a; remote = r
            service = HrService(FilePersistence(File(filesDir, "cloud").apply { mkdirs() }), remote = r)
        } else {
            service = HrService(FilePersistence(filesDir))
        }
        Reminders.createChannel(this)
        try { Reminders.schedule(this) } catch (_: Exception) { /* WorkManager unavailable (tests) */ }
    }

    /** After the mode or the project changes: start again cleanly. */
    fun restart() {
        // Settings are written synchronously (commit) before this is called. Relaunch through the
        // alarm service as well as directly, so the app comes back even where a direct start is held back.
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = android.app.PendingIntent.getActivity(this, 7, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_CANCEL_CURRENT)
        (getSystemService(ALARM_SERVICE) as android.app.AlarmManager).set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 400, pi)
        startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    /** Forget the cached copy of a firm's data (cloud sign out). */
    fun clearCloudCache() { File(filesDir, "cloud").listFiles()?.forEach { it.delete() } }
}
