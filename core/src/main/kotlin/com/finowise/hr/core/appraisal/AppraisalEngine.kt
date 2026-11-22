package com.finowise.hr.core.appraisal

import java.time.LocalDate

/**
 * Kotlin port of BOSS's `appraisal-engine.js` — pure functions only.
 *
 *   Company Growth Pool % = 50% Absolute growth + 25% SIP growth + 25% New Lumpsum growth
 *   5 Cs Score (0-100)    = Curiosity + Competence + Commitment + Creativity (ratings, weighted)
 *                           + Contribution (role KPIs, banded)
 *   Increment %           = Pool % x (5 Cs Score / 100)
 *   Appraisal Amount      = Current Annual Salary x Increment %
 *   New Salary            = Current Annual Salary + Appraisal Amount
 *   An override (0-100) replaces the computed 5 Cs score.
 *
 * Arithmetic is done in the same order as the JS so results are bit-identical.
 */
object AppraisalEngine {
    private fun b(min: Double, v: Double) = Band(min, v)

    val STD10_BANDS: List<Band> = listOf(b(100.0, 10.0), b(75.0, 7.5), b(50.0, 5.0), b(25.0, 2.5), b(0.0, 0.0))

    val DEFAULT_CONFIG: EngineConfig = EngineConfig(
        cycle = Cycle.FY,
        cWeights = CWeights(curiosity = 5.0, competence = 20.0, commitment = 20.0, creativity = 5.0),
        poolWeights = PoolWeights(abs = 0.5, sip = 0.25, lump = 0.25),
        targets = Targets(opsPoints = 2400.0, meetings = 300.0),
        roles = linkedMapOf(
            "clientsRM" to RoleDef(
                "Clients RM", listOf(
                    KpiDef("meetings", "Meetings Target Achieved", 30.0, "meetings",
                        listOf(b(100.0, 30.0), b(75.0, 25.0), b(50.0, 15.0), b(25.0, 10.0), b(0.0, 0.0))),
                    KpiDef("newClientLeads", "New Clients Conversion to Leads", 10.0, null, STD10_BANDS),
                    KpiDef("addlBiz", "Additional Business to Review Meeting Ratio", 10.0, null, STD10_BANDS),
                )
            ),
            "partnersRM" to RoleDef(
                "Partners RM", listOf(
                    KpiDef("webinar", "Lead to Live Webinar Attendance Ratio", 10.0, null, STD10_BANDS),
                    KpiDef("nismPass", "NISM Enrollment to Pass Ratio", 10.0, null, STD10_BANDS),
                    KpiDef("empanellment", "NISM Pass to Empanellment Ratio", 10.0, null, STD10_BANDS),
                    KpiDef("activation", "Empanellment to Business Activation Ratio", 10.0, null, STD10_BANDS),
                    KpiDef("partnerQuery", "Partners Query Registered & Resolve Ratio", 10.0, null, STD10_BANDS),
                )
            ),
            "operations" to RoleDef(
                "Operations", listOf(
                    KpiDef("completedTasks", "Completed Tasks Ratio", 30.0, "opsPoints",
                        listOf(b(100.0, 30.0), b(50.0, 25.0), b(25.0, 15.0), b(0.0, 0.0))),
                    KpiDef("projectCompleted", "Project Completed %", 10.0, null, STD10_BANDS),
                    KpiDef("queryResolved", "Clients Query Registered to Resolved Ratio", 10.0, null, STD10_BANDS),
                )
            ),
        ),
    )

    /** Points earned by one KPI: first band (highest min first) that the achievement % reaches. */
    fun scoreFromBand(bands: List<Band>?, achieved: Double): Double {
        val sorted = (bands ?: emptyList()).sortedWith { x, y -> y.min.compareTo(x.min) }
        for (band in sorted) if (achieved >= band.min) return band.value
        return 0.0
    }

    /** [scoreFromBand] on a raw stored value, read with the engine's `num()` (parseFloat, NaN -> 0). */
    fun scoreFromBand(bands: List<Band>?, achieved: kotlinx.serialization.json.JsonElement?): Double =
        scoreFromBand(bands, Js.num(achieved))

    /** Pool % from the three company growth figures (each a percentage). */
    fun companyGrowthPool(growth: Growth?, poolWeights: PoolWeights? = null): Double {
        val w = poolWeights ?: DEFAULT_CONFIG.poolWeights
        val g = growth ?: Growth()
        return g.abs * w.abs + g.sip * w.sip + g.lump * w.lump
    }

    /** One growth % from raw AUM: B2C growth at 100%, B2B at 10%, over total opening AUM; 2 decimals. */
    fun creditedGrowthPct(row: AumRow): Double {
        val credited = (row.b2cC - row.b2cO) * 1.0 + (row.b2bC - row.b2bO) * 0.10
        val base = row.b2cO + row.b2bO
        return if (base > 0) Js.round((credited / base) * 10000) / 100 else 0.0
    }

    /** The 5 Cs. [roleKey] picks the KPI set from config.roles (null or unknown = no Contribution KPIs). */
    fun compute5C(config: EngineConfig, roleKey: String?, rating: Rating?): FiveCs {
        val w = config.cWeights
        val r = rating ?: Rating()
        val cur = (Js.num(r.curiosity) / 100) * w.curiosity
        val comp = (Js.num(r.competence) / 100) * w.competence
        val com = (Js.num(r.commitment) / 100) * w.commitment
        val cre = (Js.num(r.creativity) / 100) * w.creativity

        val role = if (roleKey.isNullOrEmpty()) null else config.roles[roleKey]
        var contribution = 0.0
        val kpis = ArrayList<KpiScore>()
        role?.kpis?.forEachIndexed { i, k ->
            val achieved = Js.num(r.kpi.getOrNull(i))
            val pts = scoreFromBand(k.bands, achieved)
            kpis.add(KpiScore(k.key, k.label, k.weight, achieved, pts))
            contribution += pts
        }
        val contributionWeight = 100 - (w.curiosity + w.competence + w.commitment + w.creativity)
        return FiveCs(cur, comp, com, cre, contribution, contributionWeight, kpis, cur + comp + com + cre + contribution)
    }

    /** The full appraisal. `salary` is the current ANNUAL CTC. */
    fun computeAppraisal(input: AppraisalInput): AppraisalResult {
        val config = input.config
        val rating = input.rating ?: Rating()
        val c = compute5C(config, input.roleKey, rating)

        val ov = rating.scoreOverride
        val isOverridden = Js.isPresent(ov)
        val score = if (isOverridden) maxOf(0.0, minOf(100.0, Js.num(ov))) else c.total

        val pool = companyGrowthPool(input.growth, config.poolWeights)
        val salary = input.salary
        val incrementPct = pool * (score / 100)
        val amount = salary * (incrementPct / 100)
        val newSalary = Js.round(salary + amount)

        return AppraisalResult(
            score = score,
            computedScore = c.total,
            isOverridden = isOverridden,
            pool = pool,
            incrementPct = incrementPct,
            salary = salary,
            amount = Js.round(amount),
            newSalary = newSalary,
            monthlyCTC = salary / 12,
            newMonthlyCTC = newSalary / 12,
            breakdown = Breakdown(c.curiosity, c.competence, c.commitment, c.creativity,
                c.contribution, c.contributionWeight, c.kpis),
        )
    }

    /* ------------------------- appraisal year helpers ------------------------- */

    val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val ISO_DAY = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

    /** 'Sep-2026' style key. */
    fun monthKey(date: LocalDate?): String = if (date == null) "" else MONTHS[date.monthValue - 1] + "-" + date.year

    /**
     * 'Sep-2026' from a 'YYYY-MM-DD…' string (only the first 10 characters are read). Like the engine, a
     * day past the month's end rolls over (2026-02-30 -> Mar-2026); anything unparseable gives "".
     */
    fun monthKey(date: String?): String {
        if (date.isNullOrEmpty()) return ""
        val m = ISO_DAY.find(date.take(10)) ?: return ""
        val (y, mo, d) = m.destructured
        val month = mo.toInt(); val day = d.toInt()
        if (month !in 1..12 || day !in 1..31) return ""
        return monthKey(LocalDate.of(y.toInt(), month, 1).plusDays((day - 1).toLong()))
    }

    /** The 12 month keys an appraisal year covers. */
    fun appraisalMonthKeys(year: Int, cycle: String? = Cycle.FY): List<String> =
        if ((cycle ?: Cycle.FY) == Cycle.FY)
            (3 until 12).map { MONTHS[it] + "-" + year } + (0 until 3).map { MONTHS[it] + "-" + (year + 1) }
        else MONTHS.map { "$it-$year" }

    /** "FY 2026–27" or "2026". */
    fun appraisalYearLabel(year: Int, cycle: String? = Cycle.FY): String =
        if ((cycle ?: Cycle.FY) == Cycle.FY) "FY " + year + "–" + (year + 1).toString().drop(2) else year.toString()

    /** Which appraisal year [today] falls in. */
    fun currentAppraisalYear(cycle: String? = Cycle.FY, today: LocalDate = LocalDate.now()): Int =
        if ((cycle ?: Cycle.FY) == Cycle.FY) (if (today.monthValue >= 4) today.year else today.year - 1) else today.year

    /** Achievement % of [total] against [target], 1 decimal, ready for `rating.kpi[i]`; 0 if no target. */
    fun achievementPct(total: Double, target: Double): Double =
        if (target > 0) Js.round((total / target) * 1000) / 10 else 0.0
}
