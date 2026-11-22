package com.finowise.hr.core.appraisal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random

/** What the appraisal layer needs to know about an employee. */
data class AppraisalPerson(
    val id: String,
    val name: String = "",
    val role: String? = null,
    val department: String? = null,
    val branch: String? = null,
    /** Annual CTC. */
    val ctc: Double = 0.0,
    val reportingManagerId: String? = null,
    /** Pins this person to an appraisal role key; beats every mapping. */
    val appraisalRoleKey: String? = null,
    val active: Boolean = true,
)

/** Who is acting. [isAdmin] = BOSS's isAdmin()/hrIsAdmin(); [isHrStaff] = may see everybody but not rate. */
data class Viewer(val id: String, val isAdmin: Boolean = false, val isHrStaff: Boolean = false)

/** Result of an action: the new value, a refusal with BOSS's reason, or a question to confirm first. */
sealed class AppraisalOutcome<out T> {
    data class Ok<T>(val value: T) : AppraisalOutcome<T>()
    data class Denied(val reason: String) : AppraisalOutcome<Nothing>()
    /** Repeat the call with the confirmation flag set to go ahead. */
    data class NeedsConfirmation(val question: String) : AppraisalOutcome<Nothing>()

    fun getOrNull(): T? = (this as? Ok<T>)?.value
}

/** Why a person is on the KPI set they are on (BOSS's apprRoleWhy). */
enum class RoleSource { ACCOUNT, ROLE, DEPARTMENT, DEFAULT, NONE }

enum class RoleMapKind(val prefix: String) { ROLE("role"), DEPARTMENT("dept") }

data class YearRange(val from: LocalDate, val to: LocalDate)

/** What is in place and what is still missing for one person's appraisal (BOSS's apprMapping, standalone). */
data class AppraisalReadiness(
    val roleKey: String?,
    val role: RoleDef?,
    val roleSource: RoleSource,
    val salary: Double,
    val growth: GrowthEntry,
    /** Labels of every input not yet filled: "Annual CTC", "Role (KPI set)", growth figures, the Cs, each KPI. */
    val missing: List<String>,
)

/**
 * BOSS's appraisal integration layer, as pure functions over plain data. Nothing here stores anything:
 * every mutation returns the new record / settings for the caller to persist.
 */
object Appraisals {

    /* ----------------------------- configuration ----------------------------- */

    /** BOSS's incKey: collapse whitespace, trim, lower-case. */
    fun normaliseKey(s: String?): String = (s ?: "").replace(Regex("\\s+"), " ").trim().lowercase()

    fun roleMapKey(kind: RoleMapKind, name: String?): String = kind.prefix + ":" + normaliseKey(name)

    /** apprConfig(): the firm's own weights, targets and roles where set, the engine defaults elsewhere. */
    fun effectiveConfig(settings: AppraisalSettings?, base: EngineConfig = AppraisalEngine.DEFAULT_CONFIG): EngineConfig {
        val own = settings ?: return base
        val cw = own.cWeights; val pw = own.poolWeights; val t = own.targets
        return EngineConfig(
            cycle = own.cycle ?: base.cycle,
            cWeights = CWeights(
                cw?.curiosity ?: base.cWeights.curiosity, cw?.competence ?: base.cWeights.competence,
                cw?.commitment ?: base.cWeights.commitment, cw?.creativity ?: base.cWeights.creativity,
            ),
            poolWeights = PoolWeights(pw?.abs ?: base.poolWeights.abs, pw?.sip ?: base.poolWeights.sip, pw?.lump ?: base.poolWeights.lump),
            targets = Targets(t?.opsPoints ?: base.targets.opsPoints, t?.meetings ?: base.targets.meetings),
            roles = LinkedHashMap(base.roles).apply { own.roles?.let { putAll(it) } },
        )
    }

    /** The KPI set for anybody no mapping covers: the configured one if it exists, else operations, else the first. */
    fun defaultRoleKey(settings: AppraisalSettings?): String? {
        val roles = effectiveConfig(settings).roles
        val k = settings?.defaultRoleKey
        if (!k.isNullOrEmpty() && roles.containsKey(k)) return k
        return if (roles.containsKey("operations")) "operations" else roles.keys.firstOrNull()
    }

    fun roleSourceOf(person: AppraisalPerson, settings: AppraisalSettings?): RoleSource {
        if (!person.appraisalRoleKey.isNullOrEmpty()) return RoleSource.ACCOUNT
        val map = settings?.roleMap ?: emptyMap()
        if (!map[roleMapKey(RoleMapKind.ROLE, person.role)].isNullOrEmpty()) return RoleSource.ROLE
        if (person.department != null && !map[roleMapKey(RoleMapKind.DEPARTMENT, person.department)].isNullOrEmpty())
            return RoleSource.DEPARTMENT
        return if (defaultRoleKey(settings) != null) RoleSource.DEFAULT else RoleSource.NONE
    }

    /** apprRoleKeyOf: account pin, then role mapping, then department mapping, then the default. */
    fun roleKeyOf(person: AppraisalPerson?, settings: AppraisalSettings?): String? {
        if (person == null) return null
        if (!person.appraisalRoleKey.isNullOrEmpty()) return person.appraisalRoleKey
        val map = settings?.roleMap ?: emptyMap()
        map[roleMapKey(RoleMapKind.ROLE, person.role)]?.takeIf { it.isNotEmpty() }?.let { return it }
        if (person.department != null)
            map[roleMapKey(RoleMapKind.DEPARTMENT, person.department)]?.takeIf { it.isNotEmpty() }?.let { return it }
        return defaultRoleKey(settings)
    }

    fun setRoleMap(settings: AppraisalSettings?, viewer: Viewer, kind: RoleMapKind, name: String, roleKey: String?): AppraisalOutcome<AppraisalSettings> {
        if (!viewer.isAdmin) return AppraisalOutcome.Denied("The role mapping is HR’s to set.")
        val s = settings ?: AppraisalSettings()
        val map = LinkedHashMap(s.roleMap ?: emptyMap())
        val key = roleMapKey(kind, name)
        if (!roleKey.isNullOrEmpty()) map[key] = roleKey else map.remove(key)
        return AppraisalOutcome.Ok(s.copy(roleMap = map))
    }

    fun setDefaultRoleKey(settings: AppraisalSettings?, viewer: Viewer, roleKey: String?): AppraisalOutcome<AppraisalSettings> {
        if (!viewer.isAdmin) return AppraisalOutcome.Denied("The role mapping is HR’s to set.")
        return AppraisalOutcome.Ok((settings ?: AppraisalSettings()).copy(defaultRoleKey = roleKey))
    }

    /* --------------------------------- years --------------------------------- */

    fun yearRange(year: Int, cycle: String? = Cycle.FY): YearRange =
        if ((cycle ?: Cycle.FY) == Cycle.FY) YearRange(LocalDate.of(year, 4, 1), LocalDate.of(year + 1, 3, 31))
        else YearRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))

    /* ----------------------------- company growth ----------------------------- */

    /** apprGrowthFor: the year's three figures, "" where not entered. */
    fun growthFor(settings: AppraisalSettings?, year: String): GrowthEntry = settings?.growth?.get(year) ?: GrowthEntry()

    fun growthFor(settings: AppraisalSettings?, year: Int): GrowthEntry = growthFor(settings, year.toString())

    /** apprSetGrowth: admin only. null clears the figure back to "". */
    fun setGrowth(settings: AppraisalSettings?, viewer: Viewer, year: String, field: GrowthField, value: Double?): AppraisalOutcome<AppraisalSettings> {
        if (!viewer.isAdmin) return AppraisalOutcome.Denied("The company growth figures are HR’s to set.")
        val s = settings ?: AppraisalSettings()
        val row = growthFor(s, year)
        val v: JsonElement = if (value == null) JsonPrimitive("") else Js.json(Js.incNum(value))
        val next = when (field) {
            GrowthField.ABS -> row.copy(abs = v); GrowthField.SIP -> row.copy(sip = v); GrowthField.LUMP -> row.copy(lump = v)
        }
        return AppraisalOutcome.Ok(s.copy(growth = LinkedHashMap(s.growth ?: emptyMap()).apply { put(year, next) }))
    }

    /**
     * Year-on-year growth %, one decimal, from two manually entered totals (book AUM, SIP book or completed
     * lumpsum) a year apart — BOSS's apprGrowthAuto arithmetic. null when the opening figure is not positive.
     */
    fun growthPct(opening: Double, closing: Double): Double? =
        if (opening > 0) Js.round(((closing - opening) / opening) * 1000) / 10 else null

    /* ------------------------------ permissions ------------------------------ */

    /** Everybody below [managerId] on the reporting line, transitively. */
    fun reportsUnder(managerId: String, people: Collection<AppraisalPerson>): Set<String> {
        val out = LinkedHashSet<String>(); val seen = HashSet<String>(); val queue = ArrayDeque(listOf(managerId))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            for (p in people) {
                if (p.id in out || p.id == managerId) continue
                if (p.reportingManagerId != id || p.reportingManagerId == p.id) continue
                out.add(p.id); queue.addLast(p.id)
            }
        }
        return out
    }

    /** apprMaySee: their own always; everybody's for admin / HR staff; their reports' down the reporting line. */
    fun maySee(viewer: Viewer, subjectId: String, people: Collection<AppraisalPerson>): Boolean {
        if (subjectId == viewer.id) return true
        if (viewer.isAdmin || viewer.isHrStaff) return true
        return subjectId in reportsUnder(viewer.id, people)
    }

    /** apprMayRate: admin always; nobody rates themselves; otherwise only the subject's reporting manager. */
    fun mayRate(viewer: Viewer, subject: AppraisalPerson?): Boolean {
        if (viewer.isAdmin) return true
        if (subject == null || subject.id == viewer.id) return false
        return subject.reportingManagerId == viewer.id
    }

    /** apprMayJudge: the four Cs are entered by an administrator and nobody else. */
    fun mayJudge(viewer: Viewer): Boolean = viewer.isAdmin

    /** Whether [viewer] may edit [field] on an open appraisal of [subject]. */
    fun mayEdit(viewer: Viewer, subject: AppraisalPerson?, field: RatingField): Boolean =
        mayRate(viewer, subject) && (!field.adminOnly || mayJudge(viewer))

    /** apprPeople: active people the viewer may see. */
    fun visiblePeople(viewer: Viewer, people: Collection<AppraisalPerson>): List<AppraisalPerson> =
        people.filter { it.active && maySee(viewer, it.id, people) }

    /* -------------------------------- records -------------------------------- */

    fun recordFor(records: Collection<AppraisalRecord>, userId: String, year: String): AppraisalRecord? =
        records.firstOrNull { it.deletedAt.isNullOrEmpty() && it.userId == userId && it.year == year }

    fun recordFor(records: Collection<AppraisalRecord>, userId: String, year: Int): AppraisalRecord? =
        recordFor(records, userId, year.toString())

    /** BOSS's uid('APPR'): APPR-XXXXX1234. */
    fun newId(random: Random = Random.Default, nowMillis: Long = System.currentTimeMillis()): String {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return "APPR-" + (1..5).map { chars[random.nextInt(chars.length)] }.joinToString("") + nowMillis.toString().takeLast(4)
    }

    /** apprBlank. */
    fun blank(
        userId: String, year: String, createdBy: String?,
        id: String = newId(), createdAt: String = Instant.now().toString(),
    ) = AppraisalRecord(id = id, userId = userId, year = year, rating = Rating(), status = AppraisalStatus.DRAFT,
        snapshot = null, createdAt = createdAt, createdBy = createdBy)

    /** apprOpen: the existing record for the year, or a new blank one (only for someone who may rate). */
    fun open(records: Collection<AppraisalRecord>, viewer: Viewer, subject: AppraisalPerson, year: String,
             id: String = newId(), now: String = Instant.now().toString()): AppraisalOutcome<AppraisalRecord> {
        if (!mayRate(viewer, subject)) return AppraisalOutcome.Denied("Only their reporting manager or HR may open an appraisal.")
        return AppraisalOutcome.Ok(recordFor(records, subject.id, year) ?: blank(subject.id, year, viewer.id, id, now))
    }

    /** apprCompute: the frozen snapshot once finalised, otherwise worked out now from the current inputs. */
    fun compute(record: AppraisalRecord, subject: AppraisalPerson?, settings: AppraisalSettings?): AppraisalResult {
        record.snapshot?.let { if (record.isFinalised) return it }
        return AppraisalEngine.computeAppraisal(AppraisalInput(
            config = effectiveConfig(settings),
            roleKey = roleKeyOf(subject, settings),
            salary = subject?.ctc ?: 0.0,
            growth = growthFor(settings, record.year).toGrowth(),
            rating = record.rating,
        ))
    }

    /** apprGuard: null if [viewer] may change [record], else the reason. */
    fun guard(record: AppraisalRecord?, viewer: Viewer, subject: AppraisalPerson?): String? = when {
        record == null -> "There is no appraisal."
        !mayRate(viewer, subject) -> "Only their reporting manager or HR may change this."
        record.isFinalised -> "This appraisal is finalised. Reopen it first."
        else -> null
    }

    /** apprSetRating. null clears (to "" for a C, null for the override). The Cs are admin-only. */
    fun setRating(record: AppraisalRecord, viewer: Viewer, subject: AppraisalPerson?, field: RatingField, value: Double?,
                  now: String = Instant.now().toString()): AppraisalOutcome<AppraisalRecord> {
        guard(record, viewer, subject)?.let { return AppraisalOutcome.Denied(it) }
        if (field.adminOnly && !mayJudge(viewer))
            return AppraisalOutcome.Denied("Curiosity, Competence, Commitment and Creativity are entered by an administrator.")
        val v: JsonElement = when {
            value != null -> Js.json(Js.incNum(value))
            field == RatingField.SCORE_OVERRIDE -> JsonNull
            else -> JsonPrimitive("")
        }
        val r = record.rating
        val rating = when (field) {
            RatingField.CURIOSITY -> r.copy(curiosity = v); RatingField.COMPETENCE -> r.copy(competence = v)
            RatingField.COMMITMENT -> r.copy(commitment = v); RatingField.CREATIVITY -> r.copy(creativity = v)
            RatingField.SCORE_OVERRIDE -> r.copy(scoreOverride = v)
        }
        return AppraisalOutcome.Ok(record.copy(rating = rating, updatedAt = now))
    }

    /** apprSetKpi: achievement % for KPI [index] of the person's role. null clears it to "". */
    fun setKpi(record: AppraisalRecord, viewer: Viewer, subject: AppraisalPerson?, index: Int, value: Double?,
               now: String = Instant.now().toString()): AppraisalOutcome<AppraisalRecord> {
        require(index >= 0) { "KPI index must be >= 0" }
        guard(record, viewer, subject)?.let { return AppraisalOutcome.Denied(it) }
        val kpi = record.rating.kpi.toMutableList()
        while (kpi.size <= index) kpi.add(JsonNull) // a JS array hole, which JSON.stringify writes as null
        kpi[index] = if (value == null) JsonPrimitive("") else Js.json(Js.incNum(value))
        return AppraisalOutcome.Ok(record.copy(rating = record.rating.copy(kpi = kpi), updatedAt = now))
    }

    /**
     * apprFinalise: freezes [compute]'s result into `snapshot`. Needs a role key and a salary; with no growth
     * entered for the year it asks first, and goes ahead when [confirmZeroGrowth] is true.
     */
    fun finalise(record: AppraisalRecord, viewer: Viewer, subject: AppraisalPerson?, settings: AppraisalSettings?,
                 confirmZeroGrowth: Boolean = false, now: String = Instant.now().toString()): AppraisalOutcome<AppraisalRecord> {
        if (!mayRate(viewer, subject)) return AppraisalOutcome.Denied("Only their reporting manager or HR may finalise this.")
        val name = subject?.name?.takeIf { it.isNotEmpty() } ?: record.userId
        if (roleKeyOf(subject, settings) == null)
            return AppraisalOutcome.Denied("No appraisal role is mapped for $name. The Contribution half of the score has no KPIs without one.")
        if ((subject?.ctc ?: 0.0) == 0.0)
            return AppraisalOutcome.Denied("No salary on $name’s record, so there is nothing to apply an increment to.")
        if (growthFor(settings, record.year).isEmpty && !confirmZeroGrowth)
            return AppraisalOutcome.NeedsConfirmation("No company growth has been entered for ${record.year}, so the pool is 0% and the increment will be nothing. Finalise anyway?")
        val snap = compute(record, subject, settings)
        return AppraisalOutcome.Ok(record.copy(snapshot = snap, status = AppraisalStatus.FINALISED, finalisedAt = now, finalisedBy = viewer.id))
    }

    /** apprReopen: admin only. Back to Draft; the old snapshot stays on the record but is no longer used. */
    fun reopen(record: AppraisalRecord, viewer: Viewer, now: String = Instant.now().toString()): AppraisalOutcome<AppraisalRecord> {
        if (!viewer.isAdmin) return AppraisalOutcome.Denied("Only HR may reopen a finalised appraisal.")
        return AppraisalOutcome.Ok(record.copy(status = AppraisalStatus.DRAFT, reopenedAt = now, reopenedBy = viewer.id))
    }

    /** What is set and what is missing for [subject]'s appraisal for [year] (optionally given its record). */
    fun readiness(subject: AppraisalPerson, year: String, settings: AppraisalSettings?, record: AppraisalRecord? = null): AppraisalReadiness {
        val cfg = effectiveConfig(settings)
        val roleKey = roleKeyOf(subject, settings)
        val role = roleKey?.let { cfg.roles[it] }
        val growth = growthFor(settings, year)
        val missing = ArrayList<String>()
        if (subject.ctc == 0.0) missing.add("Annual CTC")
        if (roleKey == null) missing.add("Role (KPI set)")
        if (Js.isBlank(growth.abs)) missing.add("Company growth % (absolute)")
        if (Js.isBlank(growth.sip)) missing.add("Company growth % (SIP)")
        if (Js.isBlank(growth.lump)) missing.add("Company growth % (new lumpsum)")
        val r = record?.rating ?: Rating()
        RatingField.entries.filter { it.adminOnly && Js.isBlank(r[it]) }
            .forEach { f -> missing.add(f.key.replaceFirstChar { it.uppercase() }) }
        role?.kpis?.forEachIndexed { i, k -> if (Js.isBlank(r.kpi.getOrNull(i))) missing.add(k.label) }
        return AppraisalReadiness(roleKey, role, roleSourceOf(subject, settings), subject.ctc, growth, missing)
    }
}
