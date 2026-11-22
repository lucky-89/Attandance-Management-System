package com.finowise.hr.core.incentive

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** The outcome of an edit to a sheet: the new sheet, or why the edit was refused. */
sealed class SheetEdit {
    data class Applied(val sheet: JsonObject) : SheetEdit()
    data class Refused(val reason: String) : SheetEdit()
}

/** The outcome of finalising a month. */
sealed class FinaliseOutcome {
    data class Finalised(val sheet: JsonObject, val outcome: IncentiveOutcome) : FinaliseOutcome()
    /**
     * Some lines could not be credited; call again with `acceptProblems = true` after the user agrees.
     * `sheet` carries the re-decided `kind`, exactly as BOSS sets it before asking.
     */
    data class NeedsConfirmation(val sheet: JsonObject, val problemCount: Int, val message: String) : FinaliseOutcome()
    /** Refused. `sheet` is null when nothing changed, else carries BOSS's re-decided `kind`. */
    data class Refused(val reason: String, val sheet: JsonObject? = null) : FinaliseOutcome()
}

/**
 * One person's month (BOSS's hrIncentives record), kept as JSON in exactly BOSS's shape:
 *
 *   { id, userId, month: "YYYY-MM", kind: "sales"|"nonSales", annualCtc, branch, designation,
 *     netSalesRows:[{cat, sub, source, meeting, amount}], sipRows:[…], insuranceRows:[…],
 *     meetingRows:[{label, n}], clientRows:[{label, n}], callRows:[{label, n}],
 *     status: "Draft"|"Finalised", snapshot: <result JSON>|null, ledger: [],
 *     createdAt, createdBy, updatedAt?, finalisedAt?, finalisedBy?, finalTotal?, reopenedAt?, reopenedBy?,
 *     taskCounts?: [{taskId, n}]   // standalone only: the non-sales counts BOSS read from CRM records }
 *
 * Every edit returns a new sheet; nothing here stores anything.
 */
object IncentiveSheets {
    const val DRAFT = "Draft"
    const val FINALISED = "Finalised"

    /** The money sections (lines with a product, lead source, meeting type and amount). */
    val MONEY_SECTIONS = listOf("netSalesRows", "sipRows", "insuranceRows")
    /** The counted sections (lines with a label and a count). */
    val COUNT_SECTIONS = listOf("meetingRows", "clientRows", "callRows")

    fun monthKey(now: Instant = Instant.now()): String = Js.iso(now).substring(0, 7)

    /** BOSS incBlankSheet: an empty draft for a person and month, CTC and branch copied off the person. */
    fun blankSheet(
        cfg: JsonObject, person: IncentivePerson, month: String, createdBy: String,
        now: Instant = Instant.now(), id: String = "INC-" + java.util.UUID.randomUUID().toString().substring(0, 8),
    ): JsonObject {
        val empty = JsonArray(emptyList())
        return JsonObject(linkedMapOf(
            "id" to JsonPrimitive(id), "userId" to JsonPrimitive(person.id), "month" to JsonPrimitive(month),
            "kind" to JsonPrimitive(IncentiveEngine.categoryOf(cfg, person)),
            "annualCtc" to Js.jnum(person.ctc), "branch" to JsonPrimitive(person.branch),
            "designation" to JsonPrimitive(person.role),
            "netSalesRows" to empty, "sipRows" to empty, "insuranceRows" to empty,
            "meetingRows" to empty, "clientRows" to empty, "callRows" to empty,
            "status" to JsonPrimitive(DRAFT), "snapshot" to JsonNull, "ledger" to empty,
            "createdAt" to JsonPrimitive(Js.iso(now)), "createdBy" to JsonPrimitive(createdBy)))
    }

    private fun isFinalised(sheet: JsonObject?) = sheet?.get("status").let { it is JsonPrimitive && it.isString && it.content == FINALISED }

    /** BOSS incGuardEdit: "" when the sheet may be edited, else why not. */
    fun guardEdit(sheet: JsonObject?, mayAdminister: Boolean = true): String = when {
        sheet == null -> "There is no form."
        !mayAdminister -> "Only HR may change an incentive form."
        isFinalised(sheet) -> "This month is finalised. Reopen it first."
        else -> ""
    }

    private fun with(sheet: JsonObject, vararg pairs: Pair<String, JsonElement>): JsonObject {
        val m = LinkedHashMap<String, JsonElement>(sheet)
        pairs.forEach { (k, v) -> m[k] = v }
        return JsonObject(m)
    }

    private fun stamp(now: Instant) = "updatedAt" to JsonPrimitive(Js.iso(now))

    private fun isCountSection(section: String) = section in COUNT_SECTIONS

    /** BOSS incAddRow: a blank line at the end of a section. */
    fun addRow(sheet: JsonObject, section: String, now: Instant = Instant.now(), mayAdminister: Boolean = true): SheetEdit {
        guardEdit(sheet, mayAdminister).takeIf { it.isNotEmpty() }?.let { return SheetEdit.Refused(it) }
        val rows = (Js.or(sheet[section], null).arr() ?: emptyList()).toMutableList()
        rows.add(if (isCountSection(section)) JsonObject(linkedMapOf("label" to JsonPrimitive(""), "n" to JsonPrimitive(0)))
        else JsonObject(linkedMapOf("cat" to JsonPrimitive(""), "sub" to JsonPrimitive(""), "source" to JsonPrimitive(""),
            "meeting" to JsonPrimitive(""), "amount" to JsonPrimitive(0))))
        return SheetEdit.Applied(with(sheet, section to JsonArray(rows), stamp(now)))
    }

    /** BOSS incDropRow (Array.splice semantics: a negative index counts from the end). */
    fun dropRow(sheet: JsonObject, section: String, index: Int, now: Instant = Instant.now(), mayAdminister: Boolean = true): SheetEdit {
        guardEdit(sheet, mayAdminister).takeIf { it.isNotEmpty() }?.let { return SheetEdit.Refused(it) }
        val current = sheet[section].arr()
        if (current == null) return SheetEdit.Applied(with(sheet, stamp(now)))
        val rows = current.toMutableList()
        val start = if (index < 0) maxOf(rows.size + index, 0) else minOf(index, rows.size)
        if (start < rows.size) rows.removeAt(start)
        return SheetEdit.Applied(with(sheet, section to JsonArray(rows), stamp(now)))
    }

    /**
     * BOSS incSetCell: set one cell of one line. `amount` and `n` are stored as numbers (Number(),
     * 0 when not a number); everything else is stored as the text given. A missing line is a no-op.
     */
    fun setCell(sheet: JsonObject, section: String, index: Int, key: String, value: String,
                now: Instant = Instant.now(), mayAdminister: Boolean = true): SheetEdit {
        guardEdit(sheet, mayAdminister).takeIf { it.isNotEmpty() }?.let { return SheetEdit.Refused(it) }
        val rows = sheet[section].arr() ?: return SheetEdit.Applied(sheet)
        val row = rows.getOrNull(index)
        if (index < 0 || row == null || !Js.truthy(row)) return SheetEdit.Applied(sheet)
        val v: JsonElement = if (key == "amount" || key == "n") Js.jnum(Js.num(JsonPrimitive(value))) else JsonPrimitive(value)
        val newRow = if (row is JsonObject) JsonObject(LinkedHashMap(row).apply { put(key, v) }) else row
        val newRows = rows.toMutableList().also { it[index] = newRow }
        return SheetEdit.Applied(with(sheet, section to JsonArray(newRows), stamp(now)))
    }

    /** BOSS incSetHeader: annualCtc (a number), branch, designation… */
    fun setHeader(sheet: JsonObject, key: String, value: String, now: Instant = Instant.now(), mayAdminister: Boolean = true): SheetEdit {
        guardEdit(sheet, mayAdminister).takeIf { it.isNotEmpty() }?.let { return SheetEdit.Refused(it) }
        val v: JsonElement = if (key == "annualCtc") Js.jnum(Js.num(JsonPrimitive(value))) else JsonPrimitive(value)
        return SheetEdit.Applied(with(sheet, key to v, stamp(now)))
    }

    /** Standalone addition: the count of one non-sales task for the month (replaces BOSS's CRM read). */
    fun setTaskCount(sheet: JsonObject, taskId: String, n: String, now: Instant = Instant.now(), mayAdminister: Boolean = true): SheetEdit {
        guardEdit(sheet, mayAdminister).takeIf { it.isNotEmpty() }?.let { return SheetEdit.Refused(it) }
        val rows = sheet["taskCounts"].arr().orEmpty().filter { Js.toStr(it.prop("taskId")) != taskId }.toMutableList()
        rows.add(JsonObject(linkedMapOf("taskId" to JsonPrimitive(taskId), "n" to Js.jnum(Js.num(JsonPrimitive(n))))))
        return SheetEdit.Applied(with(sheet, "taskCounts" to JsonArray(rows), stamp(now)))
    }

    /** BOSS incSheetKind: a finalised month keeps its method; a draft follows the person as they are now. */
    fun sheetKind(cfg: JsonObject, sheet: JsonObject?, person: IncentivePerson?): String {
        if (sheet == null) return IncentiveEngine.NON_SALES
        if (isFinalised(sheet)) return if (sheet["kind"].let { it is JsonPrimitive && it.isString && it.content == "sales" }) "sales" else "nonSales"
        return IncentiveEngine.categoryOf(cfg, person)
    }

    /**
     * BOSS incResultOf: a finalised sheet's stored snapshot, else worked out fresh by the method
     * the person is on now (`cfg` is the merged config).
     */
    fun resultOf(cfg: JsonObject, sheet: JsonObject?, person: IncentivePerson?): IncentiveOutcome? {
        if (sheet == null) return null
        val snap = sheet["snapshot"]
        if (isFinalised(sheet) && Js.truthy(snap) && snap is JsonObject) return IncentiveJson.decodeSnapshot(snap)
        if (sheetKind(cfg, sheet, person) != IncentiveEngine.SALES) {
            val pts = IncentiveEngine.taskPoints(cfg, sheet, person)
            return IncentiveOutcome.NonSales(pts, pts.payable ?: 0.0, pts.payable)
        }
        return IncentiveOutcome.Sales(IncentiveEngine.calculate(cfg, sheet))
    }

    fun confirmProblemsMessage(count: Int) =
        "$count line(s) could not be credited and will be left out of the total, exactly as the workbook leaves them out. Finalise anyway?"

    const val NO_POINT_VALUE = "A point is not worth anything yet.\n\nNobody has set the point-to-rupee conversion, and the " +
        "workbook does not supply one. Set it in Setup › Incentive Grid before finalising a non-sales month."

    /**
     * BOSS incFinalise: freezes the arithmetic onto the record (snapshot, finalTotal) so a rate
     * changed later restates nothing already signed off.
     */
    fun finalise(cfg: JsonObject, sheet: JsonObject, person: IncentivePerson?, by: String,
                 acceptProblems: Boolean = false, now: Instant = Instant.now(), mayAdminister: Boolean = true): FinaliseOutcome {
        if (!mayAdminister) return FinaliseOutcome.Refused("Only HR may finalise an incentive.")
        if (isFinalised(sheet)) return FinaliseOutcome.Refused("It is already finalised.")
        val kind = sheetKind(cfg, sheet, person)
        val withKind = with(sheet, "kind" to JsonPrimitive(kind))
        val outcome: IncentiveOutcome
        if (kind == IncentiveEngine.SALES) {
            val result = IncentiveEngine.calculate(cfg, withKind)
            if (result.problems.isNotEmpty() && !acceptProblems)
                return FinaliseOutcome.NeedsConfirmation(withKind, result.problems.size, confirmProblemsMessage(result.problems.size))
            outcome = IncentiveOutcome.Sales(result)
        } else {
            val pts = IncentiveEngine.taskPoints(cfg, withKind, person)
            if (pts.rate == null) return FinaliseOutcome.Refused(NO_POINT_VALUE, withKind)
            outcome = IncentiveOutcome.NonSales(pts, pts.payable!!, pts.payable, includePayable = false)
        }
        val snapshot = outcome.toJson()
        val ledger = if (outcome is IncentiveOutcome.NonSales) JsonArray(outcome.taskPoints.ids) else JsonArray(emptyList())
        val done = with(withKind, "snapshot" to snapshot, "ledger" to ledger, "status" to JsonPrimitive(FINALISED),
            "finalisedAt" to JsonPrimitive(Js.iso(now)), "finalisedBy" to JsonPrimitive(by), "finalTotal" to snapshot["total"]!!)
        return FinaliseOutcome.Finalised(done, IncentiveJson.decodeSnapshot(snapshot))
    }

    fun reopenPrompt(sheet: JsonObject, personName: String) =
        "Reopen " + personName + " for " + Js.toStr(sheet["month"]) + "?\n\nThe figures it was finalised on are kept " +
            "in the audit trail, but the form will be worked out fresh from here."

    /** BOSS incReopen (ask [reopenPrompt] first): back to Draft, worked out fresh from here. */
    fun reopen(sheet: JsonObject, by: String, now: Instant = Instant.now(), mayAdminister: Boolean = true): SheetEdit {
        if (!mayAdminister) return SheetEdit.Refused("Only HR may reopen an incentive.")
        return SheetEdit.Applied(with(sheet, "status" to JsonPrimitive(DRAFT),
            "reopenedAt" to JsonPrimitive(Js.iso(now)), "reopenedBy" to JsonPrimitive(by)))
    }
}
