package com.finowise.hr.core.incentive

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** The words the BOSS screens show next to the figures, as plain strings (no HTML). */
object IncentiveTexts {
    /** BOSS money(): ₹ and en-IN grouping, rounded to the rupee. */
    fun money(n: Double): String = Js.money(n)

    /** Baseline / credit cell: money for money streams, a rounded count otherwise. */
    fun figure(r: StreamResult, value: Double): String = if (r.kind == "money") money(value) else Js.roundStr(value)

    /** The slab badge text (BOSS incSlabBadge without the markup). */
    fun slabBadge(r: StreamResult): String = when {
        !r.qualified -> "Not Qualified"
        r.belowLowest -> "Qualified, below the lowest slab"
        else -> r.slab ?: "undefined"
    }

    /** The badge's tone: muted / warn / ok / info (BOSS INC_SLAB_TONE). */
    fun slabTone(r: StreamResult): String = when {
        !r.qualified -> "muted"
        r.belowLowest -> "warn"
        else -> when (r.slab) { "Diamond Slab" -> "ok"; "Gold Slab" -> "warn"; else -> "info" }
    }

    /** BOSS incRateText. */
    fun rateText(r: StreamResult): String = when {
        !r.qualified || r.belowLowest -> "—"
        r.payout == "pctOfCredit" -> Js.percent(r.rate) + "%"
        else -> money(r.rate)
    }

    /** The rate note shown under the rate, when it applies. */
    fun rateNote(r: StreamResult): String = if (r.qualified && !r.belowLowest && !r.rateNote.isNullOrEmpty()) r.rateNote else ""

    /** BOSS incWorkingText: how the payout was worked out, in one line. */
    fun workingText(r: StreamResult): String {
        if (!r.qualified) return "Below the baseline, so nothing is due."
        if (r.belowLowest) return "Above the baseline but under the lowest slab" +
            (if (r.floor != null) " (" + (if (r.kind == "money") money(r.floor) else Js.roundStr(r.floor)) + ")" else "") +
            ", so there is no rate to apply."
        if (r.payout == "pctOfCredit")
            return money(r.credit) + " × " + Js.percent(r.rate) + "% = " + money(r.incentive)
        val ex = if (r.kind == "money") money(r.excess) else Js.roundStr(r.excess)
        if (r.payout == "perThousandExcess")
            return ex + " over the baseline × " + money(r.rate) + " per 1,000 = " + money(r.incentive)
        return ex + " over the baseline × " + money(r.rate) + " each = " + money(r.incentive)
    }

    /** BOSS incEdgeText: a band edge as the Setup grid shows it, e.g. "2800001  [CTC × 7 + 1]". */
    fun edgeText(e: JsonElement?, basis: IncentiveBasis): String {
        if (e == null || e is JsonNull) return ""
        if (Js.isNumber(e)) return Js.numberToString((e as JsonPrimitive).content.toDouble())
        val of = e.prop("of")
        val plus = e.prop("plus")
        return Js.roundStr(IncentiveEngine.edge(e, basis)!!) + "  [" +
            (if (of is JsonPrimitive && of.isString && of.content == "slabMonthly") "monthly salary" else "CTC") +
            " × " + Js.toStr(e.prop("times")) + (if (Js.truthy(plus)) " + " + Js.toStr(plus) else "") + "]"
    }

    /** The basis the Setup grid shows edges against: the grid's own CTC (BOSS renderSettingsIncentiveGrid). */
    fun gridBasis(cfg: kotlinx.serialization.json.JsonObject): IncentiveBasis {
        val c = Js.num(cfg["slabCtc"])
        return IncentiveBasis(c, c / 12, c, c / 12)
    }

    /** The note under a line that could not be credited. */
    fun notCredited(line: PricedLine): String =
        "Not credited — " + line.problems.joinToString("; ") + ". The workbook leaves a line like this out of the total, and so does this."

    /** The line under the result table. */
    fun slabBaseNote(result: IncentiveResult): String =
        "Slab bands are built from " + (if (result.slabBase == "individual") "this person’s own CTC"
        else "the grid’s CTC of " + money(result.slabCtc)) + ", which is how the workbook does it." +
            (if (result.slabBase == "individual") "" else " Switchable in Setup › Incentive Grid.")

    fun resultHeader(finalised: Boolean): String =
        "Every stream pays only when its net credit is strictly above its baseline. " +
            (if (finalised) "These are the figures this month was finalised on." else "Worked out now, from the lines above and the grid in Setup.")

    fun totalLabel(month: String) = "Total incentive for $month"
}
