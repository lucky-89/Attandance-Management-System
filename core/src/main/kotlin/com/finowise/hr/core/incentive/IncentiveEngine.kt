package com.finowise.hr.core.incentive

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The calculation, ported line for line from BOSS (incCreditRow … incCalculate). Pure: hand it the
 * same merged config and sheet and it gives the same answer.
 *
 *  1. Each business line is credited: PCW x LSW x MW = composite multiplier; amount x multiplier =
 *     weighted net credit. A line whose product, lead source or meeting type is not on the lists
 *     is left out of the totals (the workbook's SUMIF(...,"<>#N/A")) and reported with a reason.
 *  2. Seven streams each get a baseline, a net credit, a slab and a rate.
 *  3. A stream pays only when its credit is STRICTLY above its baseline; payout is a % of credit,
 *     a rate per excess unit, or a rate per 1,000 of excess.
 *  4. The month is the streams added up: no cap, floor or deduction.
 */
object IncentiveEngine {
    const val SALES = "sales"
    const val NON_SALES = "nonSales"

    // ---- crediting ------------------------------------------------------------------------

    fun findProduct(cfg: JsonObject, section: String, cat: JsonElement?, sub: JsonElement?): JsonElement? {
        val table = if (section == "insurance") cfg["insuranceCredit"] else cfg["productCredit"]
        val want = Js.key(cat) + "|" + Js.key(sub)
        return table.arr().orEmpty().firstOrNull { Js.key(it.prop("cat")) + "|" + Js.key(it.prop("sub")) == want }
    }

    fun findLabel(list: JsonElement?, label: JsonElement?): JsonElement? {
        val want = Js.key(label)
        return list.arr().orEmpty().firstOrNull { Js.key(it.prop("label")) == want }
    }

    /** One line of business, credited (BOSS incCreditRow). */
    fun creditRow(cfg: JsonObject, section: String, row: JsonElement): PricedLine {
        val amount = Js.num(row.prop("amount"))
        val p = findProduct(cfg, section, row.prop("cat"), row.prop("sub"))
        val s = findLabel(cfg["sourceCredit"], row.prop("source"))
        val m = findLabel(cfg["meetingCredit"], row.prop("meeting"))
        val problems = ArrayList<String>()
        fun str(e: JsonElement?) = Js.toStr(Js.or(e, JsonPrimitive("")))
        if (p == null) problems.add("“" + Js.trim(str(row.prop("cat")) + " / " + str(row.prop("sub"))) +
            "” is not on the " + (if (section == "insurance") "insurance" else "product") + " credit list")
        if (s == null) problems.add("“" + str(row.prop("source")) + "” is not a lead source on the list")
        if (m == null) problems.add("“" + str(row.prop("meeting")) + "” is not a meeting type on the list")
        val ok = p != null && s != null && m != null
        val pcw = p?.let { Js.num(it.prop("factor")) }
        val lsw = s?.let { Js.num(it.prop("factor")) }
        val mw = m?.let { Js.num(it.prop("factor")) }
        val mult = if (ok) pcw!! * lsw!! * mw!! else null
        return PricedLine(row, amount, pcw, lsw, mw, mult, if (ok) amount * mult!! else null, ok, problems)
    }

    /** The lines of one section that carry anything, credited (BOSS incSectionRows). */
    fun sectionRows(cfg: JsonObject, section: String, rows: JsonElement?): List<PricedLine> =
        rows.arr().orEmpty().filter { r ->
            Js.truthy(r) && listOf("cat", "sub", "source", "meeting", "amount").any { Js.truthy(r.prop(it)) }
        }.map { creditRow(cfg, section, it) }

    /** The two TOTAL cells under a section, over the lines that resolved. */
    fun sectionTotal(priced: List<PricedLine>): SectionTotal {
        var amount = 0.0; var credit = 0.0
        priced.filter { it.ok }.forEach { amount += it.amount }
        priced.filter { it.ok }.forEach { credit += it.credit!! }
        return SectionTotal(amount, credit, priced.size, priced.count { !it.ok })
    }

    // ---- baselines and bands --------------------------------------------------------------

    fun basis(cfg: JsonObject, sheet: JsonObject): IncentiveBasis {
        val ctc = Js.num(sheet["annualCtc"])
        val slabCtc = if (isIndividual(cfg)) ctc else Js.num(cfg["slabCtc"])
        return IncentiveBasis(ctc, ctc / 12, slabCtc, slabCtc / 12)
    }

    private fun isIndividual(cfg: JsonObject) = cfg["slabBase"].let { it is JsonPrimitive && it.isString && it.content == "individual" }

    private fun isStr(e: JsonElement?, v: String) = e is JsonPrimitive && e.isString && e.content == v

    /** A band edge: a plain number, or {of, times, plus} read off the basis. Null when absent. */
    fun edge(edge: JsonElement?, basis: IncentiveBasis): Double? {
        if (edge == null || edge is JsonNull) return null
        if (Js.isNumber(edge)) return (edge as JsonPrimitive).content.toDouble()
        val of = edge.prop("of")
        val base = when {
            isStr(of, "slabCtc") -> basis.slabCtc
            isStr(of, "slabMonthly") -> basis.slabMonthly
            isStr(of, "monthly") -> basis.monthly
            else -> basis.ctc
        }
        return base * Js.num(edge.prop("times")) + Js.num(edge.prop("plus"))
    }

    fun baseline(stream: JsonElement, basis: IncentiveBasis): Double {
        val b = Js.or(stream.prop("baseline"), null)
        if (isStr(b.prop("of"), "fixed")) return Js.num(b.prop("value"))
        val base = if (isStr(b.prop("of"), "monthly")) basis.monthly else basis.ctc
        return base * Js.num(b.prop("times"))
    }

    private fun bands(stream: JsonElement): List<JsonElement> = stream.prop("bands").arr().orEmpty()

    /** The last band whose floor the credit reaches (the workbook's approximate VLOOKUP). */
    fun bandFor(stream: JsonElement, credit: Double, basis: IncentiveBasis): JsonElement? {
        var found: JsonElement? = null
        bands(stream).forEach { b ->
            val from = edge(b.prop("from"), basis)
            if (from != null && credit >= from) found = b
        }
        return found
    }

    /** One stream, start to finish (BOSS incStreamResult). */
    fun streamResult(stream: JsonElement, credit: Double, basis: IncentiveBasis): StreamResult {
        val baseline = baseline(stream, basis)
        val qualified = credit > baseline
        val band = if (qualified) bandFor(stream, credit, basis) else null
        val slab = if (!qualified) "Not Qualified" else if (band != null) band.prop("slab").strOrNull() else "No Slab"
        val rate = if (band != null) Js.num(band.prop("rate")) else 0.0
        val excess = credit - baseline
        val payout = stream.prop("payout").strOrNull()
        val payoutIs = { v: String -> isStr(stream.prop("payout"), v) }
        var incentive = 0.0
        if (band != null) {
            incentive = when {
                payoutIs("pctOfCredit") -> credit * rate
                payoutIs("perThousandExcess") -> excess * rate / 1000
                else -> excess * rate
            }
        }
        val note = Js.or(Js.or(stream.prop("baseline"), null).prop("note"), JsonPrimitive(""))
        val bs = bands(stream)
        return StreamResult(
            key = stream.prop("key").strOrNull(), label = stream.prop("label").strOrNull(),
            kind = stream.prop("kind").strOrNull(), payout = payout, rateNote = stream.prop("rateNote").strOrNull(),
            baseline = baseline, baselineNote = Js.toStr(note), credit = credit, qualified = qualified,
            belowLowest = qualified && band == null, slab = slab, rate = rate, excess = excess,
            incentive = incentive, floor = if (bs.isNotEmpty()) edge(bs[0].prop("from"), basis) else null)
    }

    // ---- the month --------------------------------------------------------------------------

    private class Built(val sections: IncentiveSections, val totals: IncentiveTotals, val premiumTotal: Double, val credits: StreamCredits)

    private fun sheetCredits(cfg: JsonObject, sheet: JsonObject): Built {
        val inv = sectionRows(cfg, "investment", sheet["netSalesRows"])
        val sip = sectionRows(cfg, "investment", sheet["sipRows"])
        val ins = sectionRows(cfg, "insurance", sheet["insuranceRows"])
        fun subTotal(priced: List<PricedLine>, subs: JsonElement?): Double {
            val keys = subs.arr().orEmpty().map { Js.key(it) }
            var s = 0.0
            priced.filter { p -> p.ok && keys.any { it == Js.key(p.row.prop("sub")) } }.forEach { s += it.credit!! }
            return s
        }
        fun count(list: JsonElement?): Double {
            var s = 0.0
            list.arr().orEmpty().forEach { r -> s += Js.num(if (Js.truthy(r)) r.prop("n") else r) }
            return s
        }
        var premium = 0.0
        ins.filter { it.ok }.forEach { premium += it.amount }
        val invT = sectionTotal(inv)
        val sipT = sectionTotal(sip)
        return Built(
            IncentiveSections(inv, sip, ins),
            IncentiveTotals(invT, sipT, sectionTotal(ins)),
            premium,
            StreamCredits(
                netSales = invT.credit, freshSip = sipT.credit,
                meetings = count(sheet["meetingRows"]), newClients = count(sheet["clientRows"]),
                callLogs = count(sheet["callRows"]),
                lifeInsurance = subTotal(ins, cfg["lifeSubs"]), healthInsurance = subTotal(ins, cfg["healthSubs"])))
    }

    /** BOSS incCalculate over an already merged config (see [IncentiveConfig.merge]). */
    fun calculate(cfg: JsonObject, sheet: JsonObject?): IncentiveResult {
        val sh = sheet ?: JsonObject(emptyMap())
        val basis = basis(cfg, sh)
        val built = sheetCredits(cfg, sh)
        val streams = cfg["streams"].arr().orEmpty()
            .sortedWith { a, b -> val d = Js.num(a.prop("order")) - Js.num(b.prop("order")); if (d < 0) -1 else if (d > 0) 1 else 0 }
            .map { s ->
                val k = s.prop("key")
                val credit = built.credits[Js.toStr(k)] ?: 0.0
                streamResult(s, credit, basis)
            }
        val problems = ArrayList<LineProblem>()
        listOf("investment" to built.sections.investment, "sip" to built.sections.sip, "insurance" to built.sections.insurance)
            .forEach { (k, list) -> list.forEachIndexed { i, p -> if (!p.ok) problems.add(LineProblem(k, i + 1, p.problems)) } }
        var total = 0.0
        streams.forEach { total += it.incentive }
        return IncentiveResult(basis, built.sections, built.totals, built.premiumTotal, built.credits, streams, problems,
            total, cfg["slabBase"].strOrNull(), basis.slabCtc)
    }

    /**
     * Convenience: merge the saved overrides, then calculate. The person is not read by the sales
     * grid: as in BOSS, the CTC comes from the sheet's own `annualCtc` header (copied from the
     * person when the sheet was opened), so changing someone's CTC never restates a month.
     */
    fun calculate(savedConfig: JsonObject?, sheet: JsonObject?, person: IncentivePerson?): IncentiveResult {
        val cfg = IncentiveConfig.merge(savedConfig)
        return calculate(cfg, sheet)
    }

    // ---- who is paid which way ----------------------------------------------------------------

    private fun names(cfg: JsonObject, key: String) = cfg[key].arr().orEmpty().map { Js.key(it) }

    /** BOSS incCategoryOf: account override, then sales roles, then sales departments. */
    fun categoryOf(cfg: JsonObject, person: IncentivePerson?): String {
        val u = person ?: return NON_SALES
        if (u.incentiveCategory == SALES || u.incentiveCategory == NON_SALES) return u.incentiveCategory
        val roles = names(cfg, "salesRoles")
        if (roles.isNotEmpty() && Js.key(u.role) in roles) return SALES
        val depts = names(cfg, "salesDepartments")
        if (depts.isNotEmpty() && departments(u).any { Js.key(it) in depts }) return SALES
        return NON_SALES
    }

    /** BOSS incCategoryWhy: which rule decided it, in words. */
    fun categoryWhy(cfg: JsonObject, person: IncentivePerson?): String {
        val u = person ?: return ""
        if (u.incentiveCategory == SALES || u.incentiveCategory == NON_SALES) return "set on this account"
        val roles = names(cfg, "salesRoles")
        if (roles.isNotEmpty() && Js.key(u.role) in roles) return "their role, " + u.role
        val depts = names(cfg, "salesDepartments")
        val hit = departments(u).firstOrNull { Js.key(it) in depts }
        if (hit != null) return "their department, $hit"
        return "no sales rule matches them"
    }

    fun categoryLabel(cat: String) = if (cat == SALES) "Sales" else "Non-sales"

    private fun departments(u: IncentivePerson) = if (u.department.isNotEmpty()) listOf(u.department) else emptyList()

    // ---- the non-sales task bank (counts typed on the sheet) --------------------------------

    /** BOSS incTaskApplies. */
    fun taskApplies(task: JsonElement?, person: IncentivePerson?): Boolean {
        if (!Js.truthy(task)) return false
        if (task.prop("active").let { Js.isBoolean(it) && (it as JsonPrimitive).content == "false" }) return false
        val roles = task.prop("roles").arr().orEmpty().map { Js.key(it) }
        if (roles.isNotEmpty() && Js.key(person?.role ?: "") !in roles) return false
        val depts = task.prop("departments").arr().orEmpty().map { Js.key(it) }
        if (depts.isNotEmpty() && (person?.let { departments(it) } ?: emptyList()).none { Js.key(it) in depts }) return false
        return true
    }

    /**
     * Points for a non-sales month. BOSS read these from CRM records; the standalone app takes
     * the counts typed on the sheet (`taskCounts: [{taskId, n}]`). Cap and point value as BOSS.
     */
    fun taskPoints(cfg: JsonObject, sheet: JsonObject?, person: IncentivePerson?): TaskPointsResult {
        val counts = sheet?.get("taskCounts").arr().orEmpty()
        val rows = cfg["tasks"].arr().orEmpty().filter { taskApplies(it, person) }.map { tk ->
            var found = 0.0
            counts.filter { c -> Js.truthy(c) && c.prop("taskId") == tk.prop("id") }
                .forEach { found += Js.num(it.prop("n")) }
            val cap = Js.num(tk.prop("cap"))
            val counted = if (cap > 0) minOf(found, cap) else found
            TaskPointsRow(tk, found, 0.0, counted, cap > 0 && found > cap, counted * Js.num(tk.prop("points")))
        }
        var points = 0.0
        rows.forEach { points += it.points }
        val value = cfg["pointValue"]
        val rate = if (value == null || value is JsonNull || (value is JsonPrimitive && value.isString && value.content == "")) null else Js.num(value)
        return TaskPointsResult(rows, points, rate, rate?.let { points * it })
    }
}
