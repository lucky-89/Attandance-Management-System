package com.finowise.hr.core.incentive

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Kotlin port against the outputs BOSS's own JavaScript produced on the same inputs. */
class IncentiveOracleTest {

    @Test fun everyCalculationCaseMatchesBoss() {
        val cases = Fixtures.load("calc-cases.json").jsonArray
        assertTrue(cases.size >= 30)
        cases.forEach { c ->
            val o = c.jsonObject
            val name = Fixtures.str(o["name"])
            val merged = IncentiveConfig.merge(Fixtures.objOrNull(o["saved"]))
            Fixtures.assertSame(o["merged"], merged, "[$name].merged")
            val result = IncentiveEngine.calculate(merged, Fixtures.objOrNull(o["sheet"]))
            Fixtures.assertSame(o["result"], result.toJson(), "[$name].result")
            // round trip: a stored snapshot reads back to the same result
            Fixtures.assertSame(o["result"], IncentiveJson.decodeResult(o["result"]!!).toJson(), "[$name].decoded")
            assertEquals(Fixtures.str(o["total"]), IncentiveTexts.money(result.total), "[$name] total text")
            val texts = o["texts"]!!.jsonArray
            assertEquals(texts.size, result.streams.size, "[$name] stream count")
            texts.forEachIndexed { i, t ->
                val r = result.streams[i]
                val w = t.jsonObject
                val at = "[$name] stream ${r.key}"
                assertEquals(Fixtures.str(w["key"]), r.key, at)
                assertEquals(Fixtures.str(w["badge"]), IncentiveTexts.slabBadge(r), "$at badge")
                assertEquals(Fixtures.str(w["rate"]), IncentiveTexts.rateText(r), "$at rate")
                assertEquals(Fixtures.str(w["working"]), IncentiveTexts.workingText(r), "$at working")
                assertEquals(Fixtures.str(w["baseline"]), IncentiveTexts.figure(r, r.baseline), "$at baseline")
                assertEquals(Fixtures.str(w["credit"]), IncentiveTexts.figure(r, r.credit), "$at credit")
                assertEquals(Fixtures.str(w["incentive"]), IncentiveTexts.money(r.incentive), "$at incentive")
            }
        }
    }

    @Test fun categoryAssignmentMatchesBoss() {
        Fixtures.load("category-cases.json").jsonArray.forEach { c ->
            val o = c.jsonObject
            val cfg = IncentiveConfig.merge(Fixtures.objOrNull(o["saved"]))
            val p = Fixtures.person(o["person"]!!)
            val at = "${p.id} under ${o["saved"]}"
            assertEquals(Fixtures.str(o["category"]), IncentiveEngine.categoryOf(cfg, p), at)
            assertEquals(Fixtures.str(o["why"]), IncentiveEngine.categoryWhy(cfg, p), at)
            assertEquals(Fixtures.str(o["label"]), IncentiveEngine.categoryLabel(IncentiveEngine.categoryOf(cfg, p)), at)
            assertEquals(Fixtures.str(o["missing"]), IncentiveEngine.categoryOf(cfg, null), at)
        }
    }

    @Test fun edgeTextsAndMoneyMatchBoss() {
        val f = Fixtures.load("edge-texts.json").jsonObject
        f["edges"]!!.jsonArray.forEach { c ->
            val o = c.jsonObject
            val cfg = IncentiveConfig.merge(Fixtures.objOrNull(o["saved"]))
            val basis = IncentiveTexts.gridBasis(cfg)
            val stream = Fixtures.str(o["stream"])
            val bi = o["band"]!!.jsonPrimitive.content.toInt()
            val (from, to) = if (stream == "extra")
                IncentiveTexts.edgeText(JsonObject(mapOf("of" to JsonPrimitive("ctc"), "times" to JsonPrimitive(1.5), "plus" to JsonPrimitive(0.5))), basis) to
                    IncentiveTexts.edgeText(null, basis)
            else {
                val band = cfg["streams"]!!.jsonArray.first { it.jsonObject["key"]!!.jsonPrimitive.content == stream }
                    .jsonObject["bands"]!!.jsonArray[bi].jsonObject
                IncentiveTexts.edgeText(band["from"], basis) to IncentiveTexts.edgeText(band["to"], basis)
            }
            assertEquals(Fixtures.str(o["from"]), from, "$stream/$bi from")
            assertEquals(Fixtures.str(o["to"]), to, "$stream/$bi to")
        }
        f["money"]!!.jsonArray.forEach { m ->
            val n = m.jsonObject["n"]!!.jsonPrimitive.content.toDouble()
            assertEquals(Fixtures.str(m.jsonObject["text"]), IncentiveTexts.money(n), "money($n)")
        }
    }

    @Test fun configEditsMatchBoss() {
        var saved: JsonObject? = JsonObject(emptyMap())
        Fixtures.load("config-ops.json").jsonArray.forEach { step ->
            val o = step.jsonObject
            val op = Fixtures.str(o["op"])
            val a = o["args"]!!.jsonArray
            fun s(i: Int) = a[i].jsonPrimitive.content
            saved = when (op) {
                "set" -> IncentiveConfig.set(saved, s(0), a[1])
                "table" -> IncentiveConfig.setTableCell(saved, s(0), s(1).toInt(), s(2), s(3))
                "band" -> IncentiveConfig.setBand(saved, s(0), s(1).toInt(), s(2), s(3))
                else -> error(op)
            }
            Fixtures.assertSame(o["saved"], saved, "$op $a")
        }
    }

    @Test fun sheetLifecycleMatchesBoss() {
        val f = Fixtures.load("lifecycle.json").jsonObject
        val people = f["people"]!!.jsonArray.map { Fixtures.person(it) }.associateBy { it.id }
        val steps = f["steps"]!!.jsonArray.map { it.jsonObject }.associateBy { Fixtures.str(it["label"]) }
        val ignore = setOf("createdAt", "updatedAt", "finalisedAt", "reopenedAt")
        val now = Instant.parse("2026-10-31T10:00:00Z")

        fun cfgOf(label: String) = IncentiveConfig.merge(steps[label]!!["saved"] as JsonObject)
        fun personOf(label: String, id: String) =
            steps[label]!!["team"]!!.jsonArray.map { Fixtures.person(it) }.first { it.id == id }
        fun check(label: String, sheet: JsonObject, alerts: List<String> = emptyList(), confirms: List<String> = emptyList()) {
            val st = steps[label] ?: error("no step $label")
            val cfg = cfgOf(label)
            val person = personOf(label, Fixtures.str(sheet["userId"]))
            Fixtures.assertSame(st["sheet"], sheet, "[$label].sheet", ignore)
            assertEquals(Fixtures.str(st["kind"]), IncentiveSheets.sheetKind(cfg, sheet, person), "[$label].kind")
            Fixtures.assertSame(st["result"], IncentiveSheets.resultOf(cfg, sheet, person)?.toJson(), "[$label].result")
            assertEquals(Fixtures.str(st["guard"]), IncentiveSheets.guardEdit(sheet), "[$label].guard")
            assertEquals(st["alerts"]!!.jsonArray.map { it.jsonPrimitive.content }, alerts, "[$label].alerts")
            assertEquals(st["confirms"]!!.jsonArray.map { it.jsonPrimitive.content }, confirms, "[$label].confirms")
        }
        fun applied(e: SheetEdit) = assertIs<SheetEdit.Applied>(e).sheet

        // a sales month
        var cfg = cfgOf("blank sales")
        var s = IncentiveSheets.blankSheet(cfg, people["P1"]!!, "2026-10", "HR1", now, id = "INC-1")
        check("blank sales", s)
        s = applied(IncentiveSheets.addRow(s, "netSalesRows", now))
        s = applied(IncentiveSheets.addRow(s, "meetingRows", now))
        listOf("cat" to "Mutual Fund", "sub" to "Equity", "source" to "Self", "meeting" to "Independent meeting", "amount" to "2400000")
            .forEach { (k, v) -> s = applied(IncentiveSheets.setCell(s, "netSalesRows", 0, k, v, now)) }
        s = applied(IncentiveSheets.setCell(s, "meetingRows", 0, "label", "All", now))
        s = applied(IncentiveSheets.setCell(s, "meetingRows", 0, "n", "30", now))
        s = applied(IncentiveSheets.setCell(s, "meetingRows", 5, "n", "30", now))
        s = applied(IncentiveSheets.addRow(s, "netSalesRows", now))
        s = applied(IncentiveSheets.setCell(s, "netSalesRows", 1, "cat", "Gold", now))
        s = applied(IncentiveSheets.setCell(s, "netSalesRows", 1, "amount", "oops", now))
        s = applied(IncentiveSheets.setHeader(s, "annualCtc", "420000", now))
        s = applied(IncentiveSheets.setHeader(s, "branch", "Pune", now))
        check("edited", s)

        cfg = cfgOf("edited")
        val ask = assertIs<FinaliseOutcome.NeedsConfirmation>(IncentiveSheets.finalise(cfg, s, people["P1"], "HR1", false, now))
        s = ask.sheet
        check("finalise declined (problem line)", s, confirms = listOf(ask.message))
        val fin = assertIs<FinaliseOutcome.Finalised>(IncentiveSheets.finalise(cfg, s, people["P1"], "HR1", true, now))
        s = fin.sheet
        assertTrue(fin.outcome.fromSnapshot)
        check("finalised", s, confirms = listOf(ask.message))

        val refused = listOf(
            assertIs<FinaliseOutcome.Refused>(IncentiveSheets.finalise(cfg, s, people["P1"], "HR1", true, now)).reason,
            assertIs<SheetEdit.Refused>(IncentiveSheets.addRow(s, "sipRows", now)).reason,
            assertIs<SheetEdit.Refused>(IncentiveSheets.setCell(s, "netSalesRows", 0, "amount", "1", now)).reason,
            assertIs<SheetEdit.Refused>(IncentiveSheets.setHeader(s, "annualCtc", "1", now)).reason)
        check("finalised then edits refused", s, alerts = refused)
        check("finalised keeps snapshot after config change", s)
        val prompt = IncentiveSheets.reopenPrompt(s, "P1")
        check("reopen declined", s, confirms = listOf(prompt))
        s = applied(IncentiveSheets.reopen(s, "HR1", now))
        check("reopened", s, confirms = listOf(prompt))
        s = applied(IncentiveSheets.dropRow(s, "netSalesRows", 1, now))
        s = applied(IncentiveSheets.dropRow(s, "netSalesRows", 7, now))
        check("dropped problem line", s)
        cfg = cfgOf("dropped problem line")
        s = assertIs<FinaliseOutcome.Finalised>(IncentiveSheets.finalise(cfg, s, personOf("dropped problem line", "P1"), "HR1", false, now)).sheet
        check("finalised clean", s)

        // a non-sales month, counts typed on the sheet
        var n = IncentiveSheets.blankSheet(cfgOf("finalised clean"), people["P5"]!!, "2026-10", "HR1", now, id = "INC-2")
        n = JsonObject(n + ("taskCounts" to steps["non-sales draft, no point value"]!!["sheet"]!!.jsonObject["taskCounts"]!!))
        check("non-sales draft, no point value", n)
        val no = assertIs<FinaliseOutcome.Refused>(IncentiveSheets.finalise(cfgOf("non-sales draft, no point value"), n,
            personOf("non-sales draft, no point value", "P5"), "HR1", true, now))
        n = no.sheet!!
        check("non-sales finalise refused", n, alerts = listOf(no.reason))
        check("non-sales with point value", n)
        n = assertIs<FinaliseOutcome.Finalised>(IncentiveSheets.finalise(cfgOf("non-sales with point value"), n,
            personOf("non-sales with point value", "P5"), "HR1", true, now)).sheet
        check("non-sales finalised", n)

        // the method follows the person while the month is a draft
        val m = IncentiveSheets.blankSheet(cfgOf("override nonSales draft"), personOf("override nonSales draft", "P4"), "2026-11", "HR1", now, id = "INC-3")
        check("override nonSales draft", m)
        check("same draft after moving to sales", m)
    }
}
