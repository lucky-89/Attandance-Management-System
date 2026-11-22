package com.finowise.hr.core.incentive

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** One question the workbook leaves open (BOSS's INCENTIVE_QUERIES), shown until an admin notes it. */
data class IncentiveQuery(val id: String, val title: String, val detail: String)

/**
 * The firm's grid ("RE Incentive Template - Ver 2") as configuration, and the merge with the
 * overrides an admin has saved. Config is kept as JSON with exactly BOSS's `DB.config.incentive`
 * shape so a saved grid moves between BOSS and this app unchanged:
 *
 *   { source, slabCtc, slabBase: "matrix"|"individual", pointValue: number|null,
 *     productCredit:   [{cat, sub, weight, factor}],     // Lists!A23:E40
 *     insuranceCredit: [{cat, sub, weight, factor}],     // Lists!A58:E65
 *     sourceCredit:    [{label, weight, factor}],        // Lists!A44:C48
 *     meetingCredit:   [{label, weight, factor}],        // Lists!A51:C55
 *     streams: [{key, label, kind: "money"|"count", order,
 *                baseline: {of: "ctc"|"monthly"|"fixed", times?, value?, note},
 *                payout: "pctOfCredit"|"perThousandExcess"|"perExcessUnit", rateNote,
 *                bands: [{from: number|{of, times, plus?}, to: ..., slab, rate}]}],
 *     lifeSubs: [..], healthSubs: [..],
 *     tasks: [{id, name, description, source, matchField, matchValue, roles, departments, points, cap, active}],
 *     salesRoles?: [..], salesDepartments?: [..], settled?: {queryId: isoTime} }
 *
 * Only the factor column (the `factor` key) is read by the calculation; `weight` is carried for BOSS.
 */
object IncentiveConfig {
    const val WORKBOOK = "RE Incentive Template - Ver 2"

    /** Every number below is a cell of the workbook, transcribed by BOSS (defaultIncentiveConfig). */
    private val DEFAULT_JSON = """
    {"source": "RE Incentive Template - Ver 2",
     "slabCtc": 400000,
     "slabBase": "matrix",
     "pointValue": null,
     "productCredit": [
      {"cat": "Mutual Fund", "sub": "Equity", "weight": 1, "factor": 1},
      {"cat": "Mutual Fund", "sub": "Hybrid", "weight": 1, "factor": 1},
      {"cat": "Mutual Fund", "sub": "Multi Asset", "weight": 1, "factor": 1},
      {"cat": "Mutual Fund", "sub": "SIF", "weight": 1, "factor": 1},
      {"cat": "Mutual Fund", "sub": "Index Fund", "weight": 0.5, "factor": 0.5},
      {"cat": "Mutual Fund", "sub": "FOF", "weight": 0.5, "factor": 0.5},
      {"cat": "Mutual Fund", "sub": "Debt", "weight": 0.25, "factor": 0.25},
      {"cat": "PMS", "sub": "Equity", "weight": 1.25, "factor": 1.25},
      {"cat": "PMS", "sub": "Debt", "weight": 0.5, "factor": 0.5},
      {"cat": "AIF", "sub": "", "weight": 1.25, "factor": 1.25},
      {"cat": "Direct Equity", "sub": "Stocks Advisory", "weight": 1, "factor": 1},
      {"cat": "Direct Equity", "sub": "IMPS", "weight": 1, "factor": 1},
      {"cat": "Corporate FD", "sub": "Corporate FD", "weight": 0.1, "factor": 0.1},
      {"cat": "Bonds", "sub": "Bonds", "weight": 0.1, "factor": 0.1},
      {"cat": "NCD", "sub": "NCD", "weight": 0.1, "factor": 0.1},
      {"cat": "Structured Product", "sub": "Structured Product", "weight": 0.1, "factor": 0.1},
      {"cat": "LAS", "sub": "Loans Against MF", "weight": 0.5, "factor": 0.5}],
     "insuranceCredit": [
      {"cat": "Life", "sub": "Term Plan", "weight": 1, "factor": 1},
      {"cat": "Life", "sub": "Endowment", "weight": 1, "factor": 1},
      {"cat": "Life", "sub": "Annuity", "weight": 1, "factor": 1},
      {"cat": "Life", "sub": "Money Back", "weight": 1, "factor": 1},
      {"cat": "Life", "sub": "ULIP", "weight": 1, "factor": 1},
      {"cat": "Health", "sub": "Health", "weight": 1, "factor": 1},
      {"cat": "General", "sub": "General", "weight": 1, "factor": 1}],
     "sourceCredit": [
      {"label": "Self", "weight": 1.25, "factor": 1.25},
      {"label": "Company", "weight": 1, "factor": 1},
      {"label": "Leads of company recruited partners", "weight": 0.5, "factor": 0.5},
      {"label": "Leads of Mentor recruited partners", "weight": 0.25, "factor": 0.25}],
     "meetingCredit": [
      {"label": "Independent meeting", "weight": 1, "factor": 1},
      {"label": "Joint Meeting alongwith Senior", "weight": 0.25, "factor": 0.25},
      {"label": "Joint Meeting alongwith Colleague - Leader Role", "weight": 0.75, "factor": 0.75},
      {"label": "Joint Meeting alongwith Colleague - Support Role", "weight": 0.25, "factor": 0.25}],
     "streams": [
      {"key": "netSales", "label": "Net Sales", "kind": "money", "order": 1,
       "baseline": {"of": "ctc", "times": 5, "note": "5 Times CTC"},
       "payout": "pctOfCredit", "rateNote": "% of Net Credit",
       "bands": [
        {"from": {"of": "slabCtc", "times": 5}, "to": {"of": "slabCtc", "times": 7}, "slab": "Silver Slab", "rate": 0.0015},
        {"from": {"of": "slabCtc", "times": 7, "plus": 1}, "to": {"of": "slabCtc", "times": 10}, "slab": "Gold Slab", "rate": 0.002},
        {"from": {"of": "slabCtc", "times": 10, "plus": 1}, "to": 99999999, "slab": "Diamond Slab", "rate": 0.0025}]},
      {"key": "freshSip", "label": "Fresh SIP", "kind": "money", "order": 2,
       "baseline": {"of": "monthly", "times": 2, "note": "2 Times Monthly Salary"},
       "payout": "perThousandExcess", "rateNote": "Per Rs 1000 excess SIP",
       "bands": [
        {"from": {"of": "slabMonthly", "times": 2}, "to": {"of": "slabMonthly", "times": 4}, "slab": "Silver Slab", "rate": 40},
        {"from": {"of": "slabMonthly", "times": 4, "plus": 1}, "to": {"of": "slabMonthly", "times": 6}, "slab": "Gold Slab", "rate": 50},
        {"from": {"of": "slabMonthly", "times": 6, "plus": 1}, "to": 9999999, "slab": "Diamond Slab", "rate": 60}]},
      {"key": "meetings", "label": "Meetings", "kind": "count", "order": 3,
       "baseline": {"of": "fixed", "value": 25, "note": "Daily One Meeting"},
       "payout": "perExcessUnit", "rateNote": "Per excess meeting",
       "bands": [
        {"from": 25, "to": 40, "slab": "Silver Slab", "rate": 100},
        {"from": 41, "to": 75, "slab": "Gold Slab", "rate": 110},
        {"from": 76, "to": 9999, "slab": "Diamond Slab", "rate": 120}]},
      {"key": "newClients", "label": "New Clients", "kind": "count", "order": 4,
       "baseline": {"of": "fixed", "value": 5, "note": "Monthly Target"},
       "payout": "perExcessUnit", "rateNote": "Per excess client",
       "bands": [
        {"from": 6, "to": 10, "slab": "Silver Slab", "rate": 400},
        {"from": 11, "to": 15, "slab": "Gold Slab", "rate": 500},
        {"from": 16, "to": 9999, "slab": "Diamond Slab", "rate": 600}]},
      {"key": "callLogs", "label": "Call Logs", "kind": "count", "order": 5,
       "baseline": {"of": "fixed", "value": 250, "note": "10 Calls a Day"},
       "payout": "perExcessUnit", "rateNote": "Per excess call",
       "bands": [
        {"from": 251, "to": 500, "slab": "Silver Slab", "rate": 5},
        {"from": 501, "to": 750, "slab": "Gold Slab", "rate": 6},
        {"from": 751, "to": 9999, "slab": "Diamond Slab", "rate": 7}]},
      {"key": "lifeInsurance", "label": "Life insurance", "kind": "money", "order": 6,
       "baseline": {"of": "fixed", "value": 10000, "note": "Fresh LIP"},
       "payout": "perThousandExcess", "rateNote": "Per Rs 1000 excess premium",
       "bands": [
        {"from": 10000, "to": 50000, "slab": "Silver Slab", "rate": 100},
        {"from": 50001, "to": 100000, "slab": "Gold Slab", "rate": 110},
        {"from": 100001, "to": 9999999, "slab": "Diamond Slab", "rate": 120}]},
      {"key": "healthInsurance", "label": "Health insurance", "kind": "money", "order": 7,
       "baseline": {"of": "fixed", "value": 10000, "note": "Fresh GIP/HIP"},
       "payout": "perThousandExcess", "rateNote": "Per Rs 1000 excess premium",
       "bands": [
        {"from": 10000, "to": 30000, "slab": "Silver Slab", "rate": 50},
        {"from": 30001, "to": 100000, "slab": "Gold Slab", "rate": 60},
        {"from": 100001, "to": 9999999, "slab": "Diamond Slab", "rate": 70}]}],
     "lifeSubs": ["Term Plan"],
     "healthSubs": ["Health"],
     "tasks": []}
    """

    private val DEFAULT: JsonObject by lazy { IncentiveJson.json.parseToJsonElement(DEFAULT_JSON).jsonObject }

    /** BOSS's defaultIncentiveConfig(). */
    fun defaults(): JsonObject = DEFAULT

    private val TABLES = listOf("productCredit", "insuranceCredit", "sourceCredit", "meetingCredit", "streams", "lifeSubs", "healthSubs")

    /**
     * BOSS's incentiveConfig(): defaults overlaid with the saved overrides; a table the firm has
     * never touched (missing, not a list, or empty) reads the workbook's; tasks default to [].
     */
    fun merge(saved: JsonObject?): JsonObject {
        val d = defaults()
        val c = LinkedHashMap<String, JsonElement>(d)
        saved?.forEach { (k, v) -> c[k] = v }
        TABLES.forEach { k -> val v = c[k]; if (v !is JsonArray || v.isEmpty()) c[k] = d[k]!! }
        if (c["tasks"] !is JsonArray) c["tasks"] = JsonArray(emptyList())
        return JsonObject(c)
    }

    /** BOSS's incCfgSet: returns the new saved config (defaults + saved, with `path` set). */
    fun set(saved: JsonObject?, path: String, value: JsonElement): JsonObject {
        val cur = LinkedHashMap<String, JsonElement>(defaults())
        saved?.forEach { (k, v) -> cur[k] = v }
        cur[path] = value
        return JsonObject(cur)
    }

    /** BOSS's incCfgTableSet: edit one cell of a credit table ("factor"/"weight" are numbers). */
    fun setTableCell(saved: JsonObject?, table: String, index: Int, key: String, value: String): JsonObject {
        val list = merge(saved)[table].arr() ?: return saved ?: JsonObject(emptyMap())
        val row = list.getOrNull(index)
        if (index < 0 || row == null || !Js.truthy(row)) return saved ?: JsonObject(emptyMap())
        val v: JsonElement = if (key == "factor" || key == "weight") Js.jnum(Js.num(JsonPrimitive(value))) else JsonPrimitive(value)
        val edited = list.mapIndexed { i, r -> if (i == index && r is JsonObject) JsonObject(r + (key to v)) else r }
        return set(saved, table, JsonArray(edited))
    }

    /** BOSS's incCfgBandSet: edit one number (rate, or a plain from/to) of one band of one stream. */
    fun setBand(saved: JsonObject?, streamKey: String, bandIndex: Int, key: String, value: String): JsonObject {
        val streams = merge(saved)["streams"].arr() ?: return saved ?: JsonObject(emptyMap())
        val st = streams.firstOrNull { it.prop("key").let { k -> k is JsonPrimitive && k.isString && k.content == streamKey } }
            ?: return saved ?: JsonObject(emptyMap())
        val bands = st.prop("bands").arr() ?: return saved ?: JsonObject(emptyMap())
        val band = bands.getOrNull(bandIndex)
        if (bandIndex < 0 || band == null || !Js.truthy(band)) return saved ?: JsonObject(emptyMap())
        val v = Js.jnum(Js.num(JsonPrimitive(value)))
        val newBands = bands.mapIndexed { i, b -> if (i == bandIndex && b is JsonObject) JsonObject(b + (key to v)) else b }
        val newStreams = streams.map { s -> if (s === st) JsonObject((s as JsonObject) + ("bands" to JsonArray(newBands))) else s }
        return set(saved, "streams", JsonArray(newStreams))
    }

    fun setSlabBase(saved: JsonObject?, individual: Boolean) =
        set(saved, "slabBase", JsonPrimitive(if (individual) "individual" else "matrix"))
    fun setSlabCtc(saved: JsonObject?, value: String) = set(saved, "slabCtc", Js.jnum(Js.num(JsonPrimitive(value))))
    /** Blank text clears the point value (null = "not set", no non-sales payout). */
    fun setPointValue(saved: JsonObject?, value: String) =
        set(saved, "pointValue", if (value == "") JsonNull else Js.jnum(Js.toNumber(JsonPrimitive(value))))
    /** Comma-separated text, as the Setup field takes it. */
    fun setSalesRoles(saved: JsonObject?, csv: String) = set(saved, "salesRoles", csvList(csv))
    fun setSalesDepartments(saved: JsonObject?, csv: String) = set(saved, "salesDepartments", csvList(csv))
    fun setLifeSubs(saved: JsonObject?, subs: List<String>) = set(saved, "lifeSubs", JsonArray(subs.map { JsonPrimitive(it) }))
    fun setHealthSubs(saved: JsonObject?, subs: List<String>) = set(saved, "healthSubs", JsonArray(subs.map { JsonPrimitive(it) }))

    /** Marks a workbook query as noted (BOSS's incSettleQuery). */
    fun settleQuery(saved: JsonObject?, id: String, isoTime: String): JsonObject {
        val settled = LinkedHashMap<String, JsonElement>((saved?.get("settled") as? JsonObject) ?: emptyMap())
        settled[id] = JsonPrimitive(isoTime)
        val m = LinkedHashMap<String, JsonElement>(saved ?: emptyMap())
        m["settled"] = JsonObject(settled)
        return JsonObject(m)
    }

    fun openQueries(saved: JsonObject?): List<IncentiveQuery> {
        val settled = saved?.get("settled") as? JsonObject
        return QUERIES.filter { q -> !Js.truthy(settled?.get(q.id)) }
    }

    /**
     * BOSS's incTaskSave for the non-sales task bank (minus the BOSS record source: counts are
     * typed on the sheet here, so `source` is stored as "manual"). `id` null adds a task.
     */
    fun saveTask(
        saved: JsonObject?, id: String?, name: String, description: String = "",
        roles: List<String> = emptyList(), departments: List<String> = emptyList(),
        points: Double, cap: Double = 0.0, newId: () -> String = { "ITASK-" + java.util.UUID.randomUUID() },
    ): JsonObject {
        require(name.trim().isNotEmpty()) { "Give the task a name." }
        val tasks = merge(saved)["tasks"].arr().orEmpty().toMutableList()
        val row = mapOf(
            "id" to JsonPrimitive(if (id.isNullOrEmpty()) newId() else id),
            "name" to JsonPrimitive(name.trim()), "description" to JsonPrimitive(description.trim()),
            "source" to JsonPrimitive("manual"), "matchField" to JsonPrimitive(""), "matchValue" to JsonPrimitive(""),
            "roles" to JsonArray(roles.map { it.trim() }.filter { it.isNotEmpty() }.map { JsonPrimitive(it) }),
            "departments" to JsonArray(departments.map { it.trim() }.filter { it.isNotEmpty() }.map { JsonPrimitive(it) }),
            "points" to Js.jnum(points), "cap" to Js.jnum(cap), "active" to JsonPrimitive(true))
        val at = if (id.isNullOrEmpty()) -1 else tasks.indexOfFirst { Js.toStr(it.prop("id")) == id }
        if (at >= 0) tasks[at] = JsonObject(((tasks[at] as? JsonObject) ?: emptyMap()) + row) else tasks.add(JsonObject(row))
        return set(saved, "tasks", JsonArray(tasks))
    }

    fun removeTask(saved: JsonObject?, id: String): JsonObject =
        set(saved, "tasks", JsonArray(merge(saved)["tasks"].arr().orEmpty().filter { Js.toStr(it.prop("id")) != id }))

    private fun csvList(csv: String) = JsonArray(csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { JsonPrimitive(it) })

    val QUERIES: List<IncentiveQuery> = listOf(
        IncentiveQuery("slabCtc", "The slab bands are built from a different CTC than the qualifier",
            "REIS works the Net Sales and Fresh SIP qualifiers out from the person’s own pay (M8 = 3,60,000). The Slabs sheet builds its bands from a CTC of its own (C6 = 4,00,000). So on the sample sheet somebody qualifies at 18,00,000 of net credit but the lowest slab does not start until 20,00,000 — and the workbook’s own example sheet lands at 19,00,000, inside that gap. Kept as the workbook has it; switchable to the person’s own CTC in Setup."),
        IncentiveQuery("deadBand", "Qualified but below the lowest slab pays nothing",
            "A stream whose credit clears the baseline but falls under the first band has no slab and no rate. The workbook’s VLOOKUP returns the header row there, and then the rate lookup fails with #N/A. BOSS reports it plainly as “Qualified, below the lowest slab” and pays nothing, rather than showing an error. Resolving the CTC question above removes the gap for Net Sales and Fresh SIP."),
        IncentiveQuery("insuranceSubs", "Endowment, Annuity, Money Back, ULIP and General earn no stream",
            "The insurance section credits every one of them, but stream 6 sums only the sub-category “Term Plan” and stream 7 only “Health”. Everything else is credited on its line and then counted by nothing. Kept as the workbook has it; the sub-categories each stream counts are configurable in Setup."),
        IncentiveQuery("premiumTotal", "The insurance TOTAL cell over-counts",
            "REIS!M90 is SUM(M66:M88), which reaches up past the premium column into the New Client Acquisition block and adds its counts to the premium total — 107,008 where the premiums are 107,000. Nothing downstream reads that cell, so no payout was ever affected. BOSS totals the premiums only."),
        IncentiveQuery("pointValue", "Nothing says what a non-sales point is worth",
            "The workbook is the Relationship Executive grid and covers sales people only. There is no task bank in it, no point value, no payout slab and no eligibility rule for anybody else. The task bank and its points are configurable here and the module will award points, but it will not show a rupee payout until somebody sets the conversion in Setup."),
        IncentiveQuery("meetingWeight", "Meetings are credited by type in business, counted flat in stream 3",
            "A joint meeting is worth a quarter when it multiplies a business line, but the Meetings Summary adds all four types up at face value and stream 3 reads that raw total. Kept as the workbook has it."),
    )
}
