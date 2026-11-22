package com.finowise.hr.core.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class Tokens(val access: String, val refresh: String, val expiresAt: Long, val email: String, val userId: String)

/** Where the signed-in session is kept between launches. */
interface TokenStore {
    fun load(): Tokens?
    fun save(t: Tokens?)
}

class MemoryTokenStore(var t: Tokens? = null) : TokenStore {
    override fun load() = t
    override fun save(t: Tokens?) { this.t = t }
}

class CloudException(message: String, val status: Int = 0) : Exception(message)

/** One row of the `records` table. */
data class Row(val store: String, val id: String, val data: JsonObject)

/** The calls the sync layer needs; the real one talks to Supabase, tests use a fake. */
interface CloudApi {
    fun selectRows(stores: List<String>, limit: Int? = null, orderDesc: Boolean = false): List<Row>
    fun upsert(firmId: String, rows: List<Row>)
    fun insert(firmId: String, rows: List<Row>)
    fun rpc(name: String, body: JsonObject = JsonObject(emptyMap())): JsonElement
}

/**
 * A small Supabase client over plain HTTP: GoTrue for sign-in, PostgREST for
 * the `records` table and the two RPCs. No SDK, so it runs the same on the
 * JVM (tests) and on Android.
 */
class SupabaseApi(baseUrl: String, private val anonKey: String, private val tokens: TokenStore) : CloudApi {
    private val base = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    val session: Tokens? get() = tokens.load()

    private fun request(method: String, path: String, body: String?, headers: Map<String, String> = emptyMap(),
                        auth: Boolean = true, retried: Boolean = false): String {
        if (auth) ensureFresh()
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("apikey", anonKey)
            setRequestProperty("Authorization", "Bearer " + ((if (auth) tokens.load()?.access else null) ?: anonKey))
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) { doOutput = true; outputStream.use { it.write(body.toByteArray()) } }
        }
        val code = try { conn.responseCode } catch (e: IOException) {
            throw CloudException("Cannot reach the server. Check the internet connection. (${e.message})")
        }
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
        if (code == 401 && auth && !retried && tokens.load() != null) {
            refresh()
            return request(method, path, body, headers, auth, true)
        }
        if (code !in 200..299) throw CloudException(errorText(text, code), code)
        return text
    }

    private fun errorText(text: String, code: Int): String {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val msg = listOf("message", "msg", "error_description", "error").firstNotNullOfOrNull { k -> o?.get(k)?.jsonPrimitive?.contentOrNull }
        return when {
            msg == null -> "The server answered $code."
            msg.contains("Invalid login credentials") -> "That email and password do not match."
            msg.contains("Email not confirmed") -> "Confirm your email first: open the link Supabase sent you, then sign in."
            msg.contains("User already registered") -> "This email already has an account. Sign in instead."
            msg.contains("row-level security") -> "You are not allowed to make that change."
            else -> msg
        }
    }

    // ---------- auth ----------
    private fun tokensFrom(text: String): Tokens? {
        val o = json.parseToJsonElement(text).jsonObject
        val access = o["access_token"]?.jsonPrimitive?.contentOrNull ?: return null
        val user = o["user"]?.jsonObject
        return Tokens(access, o["refresh_token"]!!.jsonPrimitive.content,
            System.currentTimeMillis() + (o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600) * 1000,
            user?.get("email")?.jsonPrimitive?.contentOrNull ?: "", user?.get("id")?.jsonPrimitive?.contentOrNull ?: "")
    }

    /** Returns false when the project asks for the email to be confirmed first. */
    fun signUp(email: String, password: String): Boolean {
        val t = tokensFrom(request("POST", "/auth/v1/signup", buildJsonObject { put("email", email.trim()); put("password", password) }.toString(), auth = false))
        tokens.save(t)
        return t != null
    }

    fun signIn(email: String, password: String) {
        val t = tokensFrom(request("POST", "/auth/v1/token?grant_type=password",
            buildJsonObject { put("email", email.trim()); put("password", password) }.toString(), auth = false))
            ?: throw CloudException("Sign in did not return a session.")
        tokens.save(t)
    }

    fun refresh() {
        val r = tokens.load()?.refresh ?: throw CloudException("Signed out. Sign in again.", 401)
        val t = try { tokensFrom(request("POST", "/auth/v1/token?grant_type=refresh_token", buildJsonObject { put("refresh_token", r) }.toString(), auth = false)) }
        catch (e: CloudException) { if (e.status in 400..499) tokens.save(null); throw CloudException("Your session ended. Sign in again.", 401) }
        tokens.save(t)
    }

    private fun ensureFresh() {
        val t = tokens.load() ?: return
        if (System.currentTimeMillis() > t.expiresAt - 60_000) refresh()
    }

    fun recover(email: String) { request("POST", "/auth/v1/recover", buildJsonObject { put("email", email.trim()) }.toString(), auth = false) }
    fun updatePassword(password: String) { request("PUT", "/auth/v1/user", buildJsonObject { put("password", password) }.toString()) }
    fun signOut() { runCatching { request("POST", "/auth/v1/logout", "{}") }; tokens.save(null) }

    // ---------- data ----------
    override fun rpc(name: String, body: JsonObject): JsonElement {
        val text = request("POST", "/rest/v1/rpc/$name", body.toString())
        return if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
    }

    override fun selectRows(stores: List<String>, limit: Int?, orderDesc: Boolean): List<Row> {
        val out = ArrayList<Row>()
        val page = 1000
        var offset = 0
        val filter = "store=in.(" + stores.joinToString(",") + ")"
        while (true) {
            val n = if (limit != null) minOf(page, limit - out.size) else page
            if (n <= 0) break
            val q = "/rest/v1/records?select=store,id,data&" + filter +
                "&order=" + (if (orderDesc) "updated_at.desc" else "store.asc,id.asc") + "&limit=$n&offset=$offset"
            val arr = json.parseToJsonElement(request("GET", q, null)).jsonArray
            arr.forEach { e -> val o = e.jsonObject; out.add(Row(o["store"]!!.jsonPrimitive.content, o["id"]!!.jsonPrimitive.content, o["data"]!!.jsonObject)) }
            if (arr.size < n) break
            offset += arr.size
        }
        return out
    }

    private fun body(firmId: String, rows: List<Row>) = JsonArray(rows.map { r ->
        buildJsonObject { put("firm_id", firmId); put("store", r.store); put("id", r.id); put("data", r.data) }
    }).toString()

    override fun upsert(firmId: String, rows: List<Row>) {
        if (rows.isEmpty()) return
        request("POST", "/rest/v1/records?on_conflict=firm_id,store,id", body(firmId, rows),
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"))
    }

    override fun insert(firmId: String, rows: List<Row>) {
        if (rows.isEmpty()) return
        request("POST", "/rest/v1/records", body(firmId, rows), mapOf("Prefer" to "return=minimal"))
    }
}
