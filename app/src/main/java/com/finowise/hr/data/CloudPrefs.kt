package com.finowise.hr.data

import android.content.Context
import com.finowise.hr.BuildConfig
import com.finowise.hr.core.JSON
import com.finowise.hr.core.cloud.Membership
import com.finowise.hr.core.cloud.TokenStore
import com.finowise.hr.core.cloud.Tokens

/**
 * Which mode the app runs in, the Supabase project it talks to, the signed-in
 * session and the firm this account belongs to.
 */
class CloudPrefs(context: Context) : TokenStore {
    private val p = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    /** "cloud", "local", or null until somebody chooses. A build with a project baked in starts in cloud mode. */
    var mode: String?
        get() = p.getString("mode", null) ?: if (BuildConfig.SUPABASE_URL.isNotBlank()) "cloud" else null
        set(v) { p.edit().putString("mode", v).apply() }
    var url: String
        get() = p.getString("url", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.SUPABASE_URL
        set(v) { p.edit().putString("url", v.trim()).apply() }
    var anonKey: String
        get() = p.getString("key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.SUPABASE_ANON_KEY
        set(v) { p.edit().putString("key", v.trim()).apply() }

    override fun load(): Tokens? = p.getString("tokens", null)?.let { runCatching { JSON.decodeFromString(Tokens.serializer(), it) }.getOrNull() }
    override fun save(t: Tokens?) { p.edit().apply { if (t == null) remove("tokens") else putString("tokens", JSON.encodeToString(Tokens.serializer(), t)) }.commit() }

    var membership: Membership?
        get() {
            val f = p.getString("firm", null) ?: return null
            return Membership(f, p.getString("emp", "")!!, p.getString("firmName", "")!!)
        }
        set(m) { p.edit().apply {
            if (m == null) { remove("firm"); remove("emp"); remove("firmName") }
            else { putString("firm", m.firmId); putString("emp", m.employeeId); putString("firmName", m.firmName) }
        }.commit() }
}
