package com.finowise.hr.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The records the HR module keeps. Field names follow the BOSS web app's
 * stores (team, hrConfig, hrAttendance, hrLeave, hrCompOff,
 * hrRegularizations) so that a backup is the same JSON shape either side.
 */

/** A person in the firm: BOSS's `team` record, plus the local sign-in. */
@Serializable
data class Employee(
    val id: String,
    val name: String = "",
    val email: String = "",
    val employeeId: String = "",
    /** "Admin" makes the account an administrator, as in BOSS. */
    val role: String = "",
    val designation: String = "",
    val department: String = "",
    val branch: String = "",
    /** Reporting manager (BOSS: parentUserId). */
    val parentUserId: String = "",
    val mobile: String = "",
    val status: String = "Active",
    /** HR grants: attendance, incentive, appraisal, staff, admin → 1 / 0. Unset = default. */
    val hrAccess: Map<String, Int> = emptyMap(),
    /** Annual CTC, read by Incentive and Appraisal. */
    val ctc: Double? = null,
    val joiningDate: String = "",
    /** Incentive category override ("" = worked out from role/department). */
    val incentiveCategory: String = "",
    /** Appraisal role-key override ("" = mapping / default). */
    val appraisalRoleKey: String = "",
    // Local sign-in (not in BOSS).
    val passwordHash: String = "",
    val passwordSalt: String = "",
    val mustChangePassword: Boolean = false,
    val createdAt: String = "",
) {
    val isActive: Boolean get() = status.isBlank() || status.equals("Active", ignoreCase = true)
    val isAdmin: Boolean get() = role.equals("Admin", ignoreCase = true)
}

@Serializable
data class LeaveType(val name: String = "", val unit: String = "full")

/** An older configuration stored leave types as plain names; they read as whole days. */
object LeaveTypeSerializer : JsonTransformingSerializer<LeaveType>(LeaveType.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive) buildJsonObject { put("name", element.content); put("unit", "full") } else element
}

@Serializable
data class Holiday(val date: String = "", val name: String = "")

@Serializable
data class AccessCfg(val branchHeadRole: String = "")

@Serializable
data class LocationCfg(val required: Boolean = true, val note: String = "")

@Serializable
data class RemindersCfg(
    val enabled: Boolean = true,
    val inAfter: String = "09:10",
    val outAfter: String = "19:00",
    val repeatMinutes: Int = 30,
)

@Serializable
data class LeaveBalanceCfg(val enabled: Boolean = false, val perYear: Map<String, Double> = emptyMap())

@Serializable
data class AnnualLeaveCfg(
    val paidPerYear: Double = 12.0,
    val compOffEnabled: Boolean = true,
    val compOffFullHours: Double = 9.0,
    val compOffHalfHours: Double = 4.5,
    /** 'line' (leave route), 'hr' (an HR administrator) or one person's id. */
    val compOffApprover: String = "line",
)

@Serializable
data class DayCfg(
    /** e.g. "09:30" — office start; blank = not configured (no lateness shown). */
    val start: String = "",
    val end: String = "",
    val graceMinutes: String = "",
    /** What counts as a full day; 9 when blank. */
    val fullDayHours: String = "",
    val punchInFrom: String = "08:45",
    val punchOutUntil: String = "20:45",
)

@Serializable
data class HrConfig(
    val firmName: String = "",
    val access: AccessCfg = AccessCfg(),
    val location: LocationCfg = LocationCfg(),
    val reminders: RemindersCfg = RemindersCfg(),
    val leaveBalance: LeaveBalanceCfg = LeaveBalanceCfg(),
    val annualLeave: AnnualLeaveCfg = AnnualLeaveCfg(),
    val day: DayCfg = DayCfg(),
    /** ["Sat"] etc. — three-letter English day names, as BOSS stores them. */
    val weeklyOff: List<String> = emptyList(),
    val holidays: List<Holiday> = emptyList(),
    val leaveTypes: List<@Serializable(with = LeaveTypeSerializer::class) LeaveType> = DEFAULT_LEAVE_TYPES,
    val lateRule: String = "",
    val departments: List<String> = emptyList(),
    val branches: List<String> = emptyList(),
    val roles: List<String> = listOf("Admin", "Branch Head", "Manager", "Executive"),
) {
    companion object {
        val DEFAULT_LEAVE_TYPES = listOf(LeaveType("Full Day", "full"), LeaveType("Half Day", "half"), LeaveType("Comp Off", "full"))
    }
}

/** Where a punch happened, or why it is not known. Never an invented coordinate. */
@Serializable
data class LocStamp(
    val status: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracy: Int? = null,
    val at: String = "",
    val why: String = "",
)

@Serializable
data class OutHistory(val out: String? = null, val outBy: String? = null, val outLoc: LocStamp? = null)

@Serializable
data class Session(
    val `in`: String? = null,
    val out: String? = null,
    val inBy: String? = null,
    val outBy: String? = null,
    val inLoc: LocStamp? = null,
    val outLoc: LocStamp? = null,
    val outHistory: List<OutHistory> = emptyList(),
)

@Serializable
data class AttendanceRecord(
    val id: String,
    val userId: String,
    val date: String,
    val sessions: List<Session> = emptyList(),
    val createdAt: String = "",
    val createdBy: String = "",
    val updatedAt: String = "",
)

@Serializable
data class LeaveEmployee(
    val name: String = "", val employeeId: String = "", val department: String = "", val designation: String = "",
    val branch: String = "", val manager: String = "", val email: String = "", val mobile: String = "",
)

@Serializable
data class Cancellation(
    val dates: List<String> = emptyList(),
    val keep: List<String> = emptyList(),
    val reason: String = "",
    val at: String = "",
    val by: String = "",
    val wasStatus: String = "",
)

@Serializable
data class LeaveRequest(
    val id: String,
    val userId: String,
    val from: String,
    val to: String = "",
    val type: String = "",
    /** Paid / Unpaid / Comp-Off. */
    val category: String = "",
    val unit: String = "full",
    val reason: String = "",
    /** Pending / Approved / Declined / Cancelled / Partially Cancelled. */
    val status: String = "Pending",
    val approverId: String = "",
    val approverWhy: String = "",
    val requestedAt: String = "",
    val requestedBy: String = "",
    val at: String = "",
    val duration: String = "",
    val session: String = "",
    val delegated: String = "",
    val handoverTo: String = "",
    val delegateEmail: String = "",
    val delegatedTasks: List<String> = emptyList(),
    val employee: LeaveEmployee? = null,
    val decidedBy: String = "",
    val decidedAt: String = "",
    val remarks: String = "",
    val charged: Double? = null,
    val cancellation: Cancellation? = null,
    val remainderOf: String = "",
    val remainders: List<String> = emptyList(),
)

@Serializable
data class HistoryEntry(
    val at: String = "",
    val by: String = "",
    val what: String = "",
    val action: String = "",
    val from: String = "",
    val to: String = "",
    val note: String = "",
)

@Serializable
data class CompOff(
    val id: String,
    val userId: String,
    val date: String,
    val kind: String = "",
    val minutes: Int = 0,
    val hours: Double = 0.0,
    val credit: Double = 0.0,
    /** Pending / Approved / Rejected / Not Eligible. */
    val status: String = "Pending",
    val approverId: String = "",
    val approverWhy: String = "",
    val attendanceId: String = "",
    val createdAt: String = "",
    val createdBy: String = "",
    val updatedAt: String = "",
    val decidedBy: String = "",
    val decidedAt: String = "",
    val remarks: String = "",
    val creditedAt: String? = null,
    val history: List<HistoryEntry> = emptyList(),
)

@Serializable
data class Period(val from: String = "", val to: String = "")

@Serializable
data class RecordedDay(val `in`: String = "", val out: String = "", val minutes: Int = 0, val pct: Double = 0.0)

@Serializable
data class Regularization(
    val id: String,
    val userId: String,
    val date: String,
    val periods: List<Period> = emptyList(),
    val reason: String = "",
    /** Pending / Approved / Rejected. */
    val status: String = "Pending",
    val requestedAt: String = "",
    val requestedBy: String = "",
    val recorded: RecordedDay? = null,
    val addsMinutes: Int = 0,
    val decidedBy: String = "",
    val decidedAt: String = "",
    val remarks: String = "",
    val pctAfter: Double? = null,
    val history: List<HistoryEntry> = emptyList(),
)

/** Stands in for BOSS's in-app notifications and emails. */
@Serializable
data class AppNotification(
    val id: String,
    val userId: String,
    val title: String,
    val body: String = "",
    val kind: String = "",
    val at: String = "",
    val read: Boolean = false,
)

@Serializable
data class AuditField(val field: String, val oldValue: String = "", val newValue: String = "")

@Serializable
data class AuditEntry(
    val id: String,
    val at: String,
    val by: String,
    val module: String = "HR",
    val recordId: String = "",
    val action: String,
    val fields: List<AuditField> = emptyList(),
)

/**
 * Everything the app stores. Incentive and Appraisal keep their own JSON
 * (config, monthly sheets, appraisal records) in BOSS's shapes.
 */
@Serializable
data class HrData(
    val version: Int = 1,
    val team: List<Employee> = emptyList(),
    val hrConfig: HrConfig = HrConfig(),
    val hrAttendance: List<AttendanceRecord> = emptyList(),
    val hrLeave: List<LeaveRequest> = emptyList(),
    val hrCompOff: List<CompOff> = emptyList(),
    val hrRegularizations: List<Regularization> = emptyList(),
    val incentiveConfig: JsonObject? = null,
    val hrIncentive: List<JsonObject> = emptyList(),
    val appraisalConfig: JsonObject? = null,
    val hrAppraisals: List<JsonObject> = emptyList(),
    val notifications: List<AppNotification> = emptyList(),
    val auditLog: List<AuditEntry> = emptyList(),
)

@Suppress("unused")
private val keepSerializer: KSerializer<LeaveType> = LeaveTypeSerializer
