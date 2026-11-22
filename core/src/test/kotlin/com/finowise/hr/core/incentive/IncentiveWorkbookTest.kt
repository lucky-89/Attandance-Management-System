package com.finowise.hr.core.incentive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The assertions of BOSS's tests/suites/incentive-sales.js, written directly against the port. */
class IncentiveWorkbookTest {
    private val sample = Json.parseToJsonElement("""
    {"id":"INC-SAMPLE","userId":"","month":"2026-08","kind":"sales","annualCtc":360000,"branch":"","designation":"","status":"Draft",
     "netSalesRows":[
      {"cat":"Mutual Fund","sub":"Equity","source":"Self","meeting":"Independent meeting ","amount":4500000},
      {"cat":"Mutual Fund","sub":"Hybrid","source":"Company","meeting":"Independent meeting ","amount":875000},
      {"cat":"Direct Equity","sub":"Stocks Advisory","source":"Self","meeting":"Independent meeting ","amount":600000}],
     "sipRows":[
      {"cat":"Mutual Fund","sub":"Equity","source":"Self","meeting":"Independent meeting ","amount":124000},
      {"cat":"Mutual Fund","sub":"Equity","source":"Company","meeting":"Independent meeting ","amount":100000}],
     "meetingRows":[{"label":"Individual","n":13},{"label":"Joint Meeting alongwith Senior","n":18},
      {"label":"Joint Meeting alongwith Colleague with Support Role","n":3}],
     "clientRows":[{"label":"Reference by existing Client","n":4}],
     "callRows":[{"label":"Incoming","n":180},{"label":"Outgoing","n":200}],
     "insuranceRows":[
      {"cat":"Life","sub":"Term Plan","source":"Company","meeting":"Independent meeting ","amount":38000},
      {"cat":"Life","sub":"Term Plan","source":"Company","meeting":"Joint Meeting alongwith Senior","amount":45000},
      {"cat":"Health","sub":"Health","source":"Company","meeting":"Independent meeting ","amount":24000}]}
    """).jsonObject
    private val cfg = IncentiveConfig.merge(null)

    private fun netSales(amount: Double, meeting: String = "Independent meeting ") = JsonObject(sample + ("netSalesRows" to
        Json.parseToJsonElement("""[{"cat":"Mutual Fund","sub":"Equity","source":"Company","meeting":"$meeting","amount":$amount}]""")))

    @Test fun linesStreamsAndTheThirtySixThousand() {
        val r = IncentiveEngine.calculate(cfg, sample)
        val say = { p: PricedLine -> listOf(p.pcw, p.lsw, p.mw, p.mult, p.credit).joinToString("/") { Js.numberToString(it!!) } }
        assertEquals(listOf("1/1.25/1/1.25/5625000", "1/1/1/1/875000", "1/1.25/1/1.25/750000"), r.sections.investment.map(say))
        assertEquals(listOf("1/1/1/1/38000", "1/1/0.25/0.25/11250", "1/1/1/1/24000"), r.sections.insurance.map(say))
        assertEquals(7250000.0, r.totals.investment.credit)
        assertEquals(255000.0, r.totals.sip.credit)
        assertEquals(107000.0, r.premiumTotal) // not REIS!M90's 107,008
        assertEquals(listOf(
            "netSales|1800000|7250000|Diamond Slab|0.0025|18125", "freshSip|60000|255000|Diamond Slab|60|11700",
            "meetings|25|34|Silver Slab|100|900", "newClients|5|4|Not Qualified|0|0", "callLogs|250|380|Silver Slab|5|650",
            "lifeInsurance|10000|49250|Silver Slab|100|3925", "healthInsurance|10000|24000|Silver Slab|50|700"),
            r.streams.map { listOf(it.key, it.baseline.roundToLong(), it.credit.roundToLong(), it.slab, Js.numberToString(it.rate), it.incentive.roundToLong()).joinToString("|") })
        assertEquals(36000L, r.total.roundToLong())
        assertTrue(r.problems.isEmpty())
    }

    @Test fun bandEdgesAndTheDeadBand() {
        val got = listOf(1999999.0, 2000000.0, 2800000.0, 2800001.0, 4000000.0, 4000001.0).map {
            val s = IncentiveEngine.calculate(cfg, netSales(it)).stream("netSales")!!; s.slab + "@" + Js.numberToString(s.rate)
        }
        assertEquals(listOf("No Slab@0", "Silver Slab@0.0015", "Silver Slab@0.0015", "Gold Slab@0.002", "Gold Slab@0.002", "Diamond Slab@0.0025"), got)
        val dead = IncentiveEngine.calculate(cfg, netSales(1900000.0)).stream("netSales")!!
        assertTrue(dead.qualified); assertTrue(dead.belowLowest); assertEquals(0.0, dead.incentive); assertEquals(2000000.0, dead.floor)
        assertEquals("Above the baseline but under the lowest slab (₹20,00,000), so there is no rate to apply.", IncentiveTexts.workingText(dead))
        val own = IncentiveEngine.calculate(IncentiveConfig.merge(IncentiveConfig.setSlabBase(null, true)), netSales(1900000.0)).stream("netSales")!!
        assertEquals("Silver Slab", own.slab); assertEquals(2850L, own.incentive.roundToLong())
    }

    @Test fun strictlyAboveTheBaseline() {
        fun meetings(n: Int) = IncentiveEngine.calculate(cfg, JsonObject(sample + ("meetingRows" to
            Json.parseToJsonElement("""[{"label":"x","n":$n}]""")))).stream("meetings")!!
        assertFalse(meetings(25).qualified); assertEquals(0.0, meetings(25).incentive)
        assertEquals("Silver Slab", meetings(26).slab); assertEquals(100.0, meetings(26).incentive)
    }

    @Test fun unknownLinesAreDroppedLoudlyAndMeetingTypesWeighted() {
        val crypto = Json.parseToJsonElement("""{"cat":"Crypto","sub":"Doge","source":"Self","meeting":"Independent meeting ","amount":9999999}""")
        val s = JsonObject(sample + ("netSalesRows" to kotlinx.serialization.json.JsonArray(sample["netSalesRows"]!!.jsonArray + crypto)))
        val r = IncentiveEngine.calculate(cfg, s)
        assertEquals(7250000.0, r.totals.investment.credit); assertEquals(1, r.totals.investment.dropped)
        assertEquals(1, r.problems.size); assertTrue(r.problems[0].why.joinToString(" ").contains("Crypto"))
        mapOf("Independent meeting" to 100000.0, "Joint Meeting alongwith Senior" to 25000.0,
            "Joint Meeting alongwith Colleague - Leader Role" to 75000.0, "Joint Meeting alongwith Colleague - Support Role" to 25000.0)
            .forEach { (m, want) -> assertEquals(want, IncentiveEngine.calculate(cfg, netSales(100000.0, m)).totals.investment.credit, m) }
    }

    @Test fun jsCompat() {
        assertEquals("₹1,23,45,679", Js.money(12345678.5))
        assertEquals("₹-0", Js.money(-0.4))
        assertEquals("0.25", Js.percent(0.0025)); assertEquals("0.15", Js.percent(0.0015)); assertEquals("20.5", Js.percent(0.205))
        assertEquals(12.0, Js.num(JsonPrimitive(" 12 "))); assertEquals(0.0, Js.num(JsonPrimitive("1,000")))
        assertEquals(26.0, Js.num(JsonPrimitive("0x1A"))); assertEquals(0.0, Js.num(JsonPrimitive("Infinity")))
        assertEquals("1e+21", Js.numberToString(1e21)); assertEquals("0.000001", Js.numberToString(1e-6)); assertEquals("1e-7", Js.numberToString(1e-7))
        assertEquals("joint meeting alongwith senior", Js.key(JsonPrimitive("  Joint Meeting  alongwith\tSenior ")))
    }

    @Test fun taskBankAndManualCounts() {
        var saved = IncentiveConfig.saveTask(null, null, "KYC update", points = 2.0, cap = 3.0, newId = { "T1" })
        saved = IncentiveConfig.setPointValue(saved, "10")
        val c = IncentiveConfig.merge(saved)
        val p = IncentivePerson("U1", role = "Clerk", incentiveCategory = "nonSales", ctc = 240000.0)
        var sheet = IncentiveSheets.blankSheet(c, p, "2026-11", "HR1", id = "INC-X")
        sheet = assertIs<SheetEdit.Applied>(IncentiveSheets.setTaskCount(sheet, "T1", "5")).sheet
        val out = assertIs<IncentiveOutcome.NonSales>(IncentiveSheets.resultOf(c, sheet, p))
        assertEquals(3.0, out.taskPoints.rows[0].counted); assertTrue(out.taskPoints.rows[0].capped)
        assertEquals(60.0, out.total)
        val unset = IncentiveConfig.merge(IncentiveConfig.setPointValue(saved, ""))
        assertNull(IncentiveEngine.taskPoints(unset, sheet, p).payable)
        assertIs<FinaliseOutcome.Refused>(IncentiveSheets.finalise(unset, sheet, p, "HR1"))
        val fin = assertIs<FinaliseOutcome.Finalised>(IncentiveSheets.finalise(c, sheet, p, "HR1"))
        assertEquals(60.0, Js.num(fin.sheet["finalTotal"]))
        // a later change to the grid does not restate a finalised month
        val changed = IncentiveConfig.merge(IncentiveConfig.setPointValue(saved, "99"))
        assertEquals(60.0, IncentiveSheets.resultOf(changed, fin.sheet, p)!!.total)
        assertEquals(6, IncentiveConfig.openQueries(null).size)
        assertEquals(5, IncentiveConfig.openQueries(IncentiveConfig.settleQuery(null, "slabCtc", "2026-11-21T00:00:00.000Z")).size)
    }
}
