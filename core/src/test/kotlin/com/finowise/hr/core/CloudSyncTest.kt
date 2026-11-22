package com.finowise.hr.core

import com.finowise.hr.core.cloud.*
import com.finowise.hr.core.model.*
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.*

/** One shared "server" in memory; each HrService plays a phone. */
private class FakeServer {
    val rows = LinkedHashMap<Pair<String, String>, JsonObject>()
    var refuse: ((Row) -> String?)? = null
    var calls = 0
    fun api() = object : CloudApi {
        override fun selectRows(stores: List<String>, limit: Int?, orderDesc: Boolean) =
            rows.filter { it.key.first in stores }.map { Row(it.key.first, it.key.second, it.value) }
        override fun upsert(firmId: String, rows: List<Row>) { calls++; rows.forEach { r -> refuse?.invoke(r)?.let { throw CloudException(it) } }
            rows.forEach { this@FakeServer.rows[it.store to it.id] = it.data } }
        override fun insert(firmId: String, rows: List<Row>) { calls++
            rows.forEach { require(!this@FakeServer.rows.containsKey(it.store to it.id)) { "duplicate" }; this@FakeServer.rows[it.store to it.id] = it.data } }
        override fun rpc(name: String, body: JsonObject): JsonElement = JsonNull
    }
}

class CloudSyncTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private var now = LocalDateTime.parse("2026-11-16T09:30:00")
    private val clock = { now.atZone(zone).toInstant() }
    private val server = FakeServer()

    private fun phone(): HrService {
        val remote = CloudRemote(server.api()).also { it.firmId = "F1" }
        return HrService(MemoryPersistence(), clock, zone, remote).also { assertTrue(it.refresh().ok) }
    }

    private fun seedFirm(): HrService {
        server.rows["team" to "TM-A"] = JSON.encodeToJsonElement(Employee.serializer(), Employee("TM-A", "Asha", "asha@f.in", role = "Admin")).jsonObject
        server.rows["config" to "hrConfig"] = JSON.encodeToJsonElement(HrConfig.serializer(), HrConfig(location = LocationCfg(required = false))).jsonObject
        return phone()
    }

    @Test fun leaveAskedOnOnePhoneIsDecidedOnAnother() {
        val adminPhone = seedFirm()
        assertTrue(adminPhone.cloud)
        val bina = adminPhone.saveEmployee("TM-A", Employee("", "Bina", "bina@f.in", role = "Branch Head", branch = "Varanasi"), null)
        assertTrue(bina.ok, bina.message); assertTrue(bina.message.contains("Create account"))
        val ravi = adminPhone.saveEmployee("TM-A", Employee("", "Ravi", "ravi@f.in", role = "Executive", branch = "Varanasi", parentUserId = bina.id!!), null)
        assertTrue(ravi.ok)
        assertTrue(server.rows.containsKey("team" to ravi.id))
        assertFalse(server.rows["team" to ravi.id!!]!!.containsKey("passwordHash"))

        val raviPhone = phone(); val binaPhone = phone()
        assertEquals(3, raviPhone.data.team.size)
        val ask = raviPhone.submitLeave(ravi.id, LeaveForm("Casual Leave", "2026-11-18", "2026-11-18", "Full Day", CAT_PAID, "family", "No"))
        assertTrue(ask.ok, ask.message)
        // The approver's notification went out as a new row.
        assertTrue(server.rows.keys.any { it.first == "notifications" })
        assertTrue(server.rows.keys.any { it.first == "auditLog" })

        assertTrue(binaPhone.refresh().ok)
        val pending = binaPhone.rules(bina.id).leavePendingForMe()
        assertEquals(1, pending.size)
        assertTrue(binaPhone.decideLeave(bina.id, pending[0].id, true, "Enjoy").ok)

        raviPhone.refresh()
        assertEquals("Approved", raviPhone.data.hrLeave.single().status)
        assertEquals(11.0, raviPhone.rules(ravi.id).balances(ravi.id, "2026").paidLeft)
    }

    @Test fun punchesFromTwoPhonesNeverOverwriteEachOther() {
        val adminPhone = seedFirm()
        val ravi = adminPhone.saveEmployee("TM-A", Employee("", "Ravi", "ravi@f.in"), null).id!!
        val meera = adminPhone.saveEmployee("TM-A", Employee("", "Meera", "meera@f.in"), null).id!!
        val p1 = phone(); val p2 = phone()
        val gps = LocFix(true, "captured", 25.3, 82.9, 10)
        assertTrue(p1.punchIn(ravi, gps).ok)
        assertTrue(p2.punchIn(meera, gps).ok) // p2 has not seen Ravi's punch yet
        assertEquals(2, server.rows.keys.count { it.first == "hrAttendance" })
        p1.refresh()
        assertEquals(2, p1.data.hrAttendance.size)
    }

    @Test fun aRefusalLeavesTheServersVersionOnScreen() {
        val adminPhone = seedFirm()
        val ravi = adminPhone.saveEmployee("TM-A", Employee("", "Ravi", "ravi@f.in"), null).id!!
        val p = phone()
        server.refuse = { r -> if (r.store == "hrAttendance") "A punch in has to carry the time it was made." else null }
        val o = p.punchIn(ravi, LocFix(true, "captured", 1.0, 2.0, 5))
        assertFalse(o.ok)
        assertEquals("A punch in has to carry the time it was made.", o.message)
        assertTrue(p.data.hrAttendance.isEmpty())
    }

    @Test fun onlyChangedRowsAreSent() {
        val d = HrData(team = listOf(Employee("A", "Asha")), hrLeave = listOf(LeaveRequest("L1", "A", "2026-01-01")))
        val n = d.copy(hrLeave = d.hrLeave + LeaveRequest("L2", "A", "2026-02-01"),
            notifications = listOf(AppNotification("N1", "B", "hi")), auditLog = listOf(AuditEntry("X", "t", "A", action = "a")))
        val (up, ins) = CloudRemote.changes(d, n)
        assertEquals(listOf("hrLeave" to "L2"), up.map { it.store to it.id })
        assertEquals(setOf("notifications", "auditLog"), ins.map { it.store }.toSet())
        // and the mapping round-trips
        val rows = CloudRemote.rowsOf(n).map { Row(it.key.first, it.key.second, it.value) }
        assertEquals(n.copy(auditLog = emptyList()), CloudRemote.dataOf(rows))
    }

    @Test fun restoreIsRefusedInCloudMode() {
        val p = seedFirm()
        assertFalse(p.importJson("TM-A", p.exportJson()).ok)
    }
}
