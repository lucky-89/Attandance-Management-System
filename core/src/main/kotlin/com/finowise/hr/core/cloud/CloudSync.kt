package com.finowise.hr.core.cloud

import com.finowise.hr.core.JSON
import com.finowise.hr.core.model.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*

/** How HrService reaches the shared database. */
interface Remote {
    /** Everything this person may read, as the server holds it now. */
    fun fetch(): HrData
    /** Sends what changed between [old] and [next]; returns why the server refused, or null. */
    fun push(old: HrData, next: HrData): String?
    /** The latest audit lines (HR only). */
    fun fetchAudit(limit: Int = 300): List<AuditEntry>
}

data class Membership(val firmId: String, val employeeId: String, val firmName: String)

/**
 * Maps the app's HrData onto `records` rows (one row per record, the same JSON
 * BOSS keeps) and sends only the rows that changed.
 */
class CloudRemote(private val api: CloudApi) : Remote {
    @Volatile var firmId: String? = null

    companion object {
        val FETCHED = listOf("team", "config", "hrAttendance", "hrLeave", "hrCompOff", "hrRegularizations",
            "hrIncentive", "hrAppraisals", "notifications")
        /** Written with a plain insert: the writer may not be allowed to read them back. */
        val INSERT_ONLY = setOf("auditLog")

        private fun <T> enc(s: KSerializer<T>, v: T) = JSON.encodeToJsonElement(s, v).jsonObject
        private fun <T> dec(s: KSerializer<T>, o: JsonObject): T? = runCatching { JSON.decodeFromJsonElement(s, o) }.getOrNull()

        /** Every row the data stands for, keyed by store and id. */
        fun rowsOf(d: HrData): Map<Pair<String, String>, JsonObject> {
            val m = LinkedHashMap<Pair<String, String>, JsonObject>()
            d.team.forEach { m["team" to it.id] = enc(Employee.serializer(), it.copy(passwordHash = "", passwordSalt = "")).let { o -> JsonObject(o - "passwordHash" - "passwordSalt") } }
            m["config" to "hrConfig"] = enc(HrConfig.serializer(), d.hrConfig)
            d.incentiveConfig?.let { m["config" to "incentiveConfig"] = it }
            d.appraisalConfig?.let { m["config" to "appraisalConfig"] = it }
            d.hrAttendance.forEach { m["hrAttendance" to it.id] = enc(AttendanceRecord.serializer(), it) }
            d.hrLeave.forEach { m["hrLeave" to it.id] = enc(LeaveRequest.serializer(), it) }
            d.hrCompOff.forEach { m["hrCompOff" to it.id] = enc(CompOff.serializer(), it) }
            d.hrRegularizations.forEach { m["hrRegularizations" to it.id] = enc(Regularization.serializer(), it) }
            d.hrIncentive.forEach { o -> o["id"]?.jsonPrimitive?.contentOrNull?.let { m["hrIncentive" to it] = o } }
            d.hrAppraisals.forEach { o -> o["id"]?.jsonPrimitive?.contentOrNull?.let { m["hrAppraisals" to it] = o } }
            d.notifications.forEach { m["notifications" to it.id] = enc(AppNotification.serializer(), it) }
            d.auditLog.forEach { m["auditLog" to it.id] = enc(AuditEntry.serializer(), it) }
            return m
        }

        fun dataOf(rows: List<Row>, keepAudit: List<AuditEntry> = emptyList()): HrData {
            val by = rows.groupBy { it.store }
            fun <T> list(store: String, s: KSerializer<T>) = by[store].orEmpty().mapNotNull { dec(s, it.data) }
            val cfg = by["config"].orEmpty().associateBy { it.id }
            return HrData(
                team = list("team", Employee.serializer()),
                hrConfig = cfg["hrConfig"]?.let { dec(HrConfig.serializer(), it.data) } ?: HrConfig(),
                hrAttendance = list("hrAttendance", AttendanceRecord.serializer()),
                hrLeave = list("hrLeave", LeaveRequest.serializer()),
                hrCompOff = list("hrCompOff", CompOff.serializer()),
                hrRegularizations = list("hrRegularizations", Regularization.serializer()),
                incentiveConfig = cfg["incentiveConfig"]?.data,
                hrIncentive = by["hrIncentive"].orEmpty().map { it.data },
                appraisalConfig = cfg["appraisalConfig"]?.data,
                hrAppraisals = by["hrAppraisals"].orEmpty().map { it.data },
                notifications = list("notifications", AppNotification.serializer()),
                auditLog = keepAudit,
            )
        }

        /** Rows to write: changed ones first (a cancelled leave before the remainders that point at it), then new ones. */
        fun changes(old: HrData, next: HrData): Pair<List<Row>, List<Row>> {
            val a = rowsOf(old); val b = rowsOf(next)
            val changed = b.filter { (k, v) -> a.containsKey(k) && a[k] != v }.map { (k, v) -> Row(k.first, k.second, v) }
            val added = b.filter { (k, _) -> !a.containsKey(k) }.map { (k, v) -> Row(k.first, k.second, v) }
            val order = listOf("team", "config") // people and settings before anything that refers to them
            val sorted = (changed + added).sortedBy { r -> order.indexOf(r.store).let { if (it < 0) 99 else it } }
            return sorted.filter { it.store !in INSERT_ONLY && !(it.store == "notifications" && !a.containsKey(it.store to it.id)) } to
                sorted.filter { it.store in INSERT_ONLY || (it.store == "notifications" && !a.containsKey(it.store to it.id)) }
        }
    }

    private fun firm() = firmId ?: throw CloudException("Not joined to a firm yet.")

    override fun fetch(): HrData = dataOf(api.selectRows(FETCHED))

    override fun push(old: HrData, next: HrData): String? {
        val (upserts, inserts) = changes(old, next)
        return try {
            // Each store in its own request, in a fixed order, so a refusal names one kind of record.
            upserts.groupBy { it.store }.forEach { (_, rows) -> api.upsert(firm(), rows) }
            api.insert(firm(), inserts)
            null
        } catch (e: CloudException) { e.message ?: "The server refused the change." }
    }

    override fun fetchAudit(limit: Int): List<AuditEntry> =
        api.selectRows(listOf("auditLog"), limit, orderDesc = true).mapNotNull { dec(AuditEntry.serializer(), it.data) }.sortedBy { it.at }

    // ---------- joining ----------
    fun claim(): Membership? = membershipOf(api.rpc("claim_membership"))

    fun createFirm(name: String, timezone: String, admin: Employee, config: HrConfig): Membership =
        membershipOf(api.rpc("create_firm", buildJsonObject {
            put("p_name", name); put("p_timezone", timezone)
            put("p_employee", enc(Employee.serializer(), admin)); put("p_config", enc(HrConfig.serializer(), config))
        })) ?: throw CloudException("The firm was not created.")

    private fun membershipOf(e: JsonElement): Membership? {
        val o = e as? JsonObject ?: return null
        return Membership(o["firm_id"]!!.jsonPrimitive.content, o["employee_id"]!!.jsonPrimitive.content, o["name"]?.jsonPrimitive?.contentOrNull ?: "")
            .also { firmId = it.firmId }
    }
}
