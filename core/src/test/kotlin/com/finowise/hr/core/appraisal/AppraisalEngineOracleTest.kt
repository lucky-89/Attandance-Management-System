package com.finowise.hr.core.appraisal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Asserts the Kotlin port matches BOSS's appraisal-engine.js exactly, on fixtures the node oracle
 * (scratchpad/appr-oracle/generate.js) produced by running a copy of the original engine.
 */
class AppraisalEngineOracleTest {

    private fun load(name: String): JsonObject {
        val text = javaClass.getResourceAsStream("/appraisal/$name")?.bufferedReader()?.readText()
            ?: fail("fixture $name missing")
        return AppraisalJson.parseToJsonElement(text).jsonObject
    }

    private val computeFx by lazy { load("compute-cases.json") }
    private val helperFx by lazy { load("helper-cases.json") }

    /** Deep JSON equality, numbers compared as exact doubles (so 10 == 10.0, and nothing else is tolerated). */
    private fun assertJsonEquals(expected: JsonElement, actual: JsonElement, path: String) {
        when (expected) {
            is JsonObject -> {
                if (actual !is JsonObject) fail("$path: expected object, got $actual")
                assertEquals(expected.keys, actual.keys, "$path: keys")
                expected.forEach { (k, v) -> assertJsonEquals(v, actual.getValue(k), "$path.$k") }
            }
            is JsonArray -> {
                if (actual !is JsonArray) fail("$path: expected array, got $actual")
                assertEquals(expected.size, actual.size, "$path: size")
                expected.forEachIndexed { i, v -> assertJsonEquals(v, actual[i], "$path[$i]") }
            }
            is JsonNull -> assertEquals(JsonNull, actual, path)
            is JsonPrimitive -> {
                if (actual !is JsonPrimitive || actual is JsonNull) fail("$path: expected $expected, got $actual")
                if (expected.isString || expected.booleanOrNull != null) assertEquals(expected, actual, path)
                else {
                    assertTrue(!actual.isString, "$path: expected a number, got string $actual")
                    val e = expected.double; val a = actual.double
                    if (e != a) fail("$path: expected $e but the port gives $a")
                }
            }
        }
    }

    private fun growthOf(e: JsonElement): Growth? = if (e is JsonNull) null else e.jsonObject.let {
        Growth(Js.num(it["abs"]), Js.num(it["sip"]), Js.num(it["lump"]))
    }

    @Test
    fun defaultConfigIsTheEnginesOwn() {
        assertJsonEquals(computeFx.getValue("defaultConfig"),
            AppraisalJson.encodeToJsonElement(EngineConfig.serializer(), AppraisalEngine.DEFAULT_CONFIG), "DEFAULT_CONFIG")
        assertJsonEquals(helperFx.getValue("std10"),
            AppraisalJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Band.serializer()), AppraisalEngine.STD10_BANDS), "STD10")
        assertEquals(listOf("clientsRM", "partnersRM", "operations"), AppraisalEngine.DEFAULT_CONFIG.roles.keys.toList())
    }

    @Test
    fun computeAppraisalMatchesTheOracleOnEveryCase() {
        val custom = AppraisalJson.decodeFromJsonElement<EngineConfig>(computeFx.getValue("customConfig"))
        val cases = computeFx.getValue("cases").jsonArray
        assertTrue(cases.size > 900, "expected the full oracle set, got ${cases.size}")
        for (c in cases) {
            val case = c.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val inp = case.getValue("input").jsonObject
            val config = when (val cfg = inp.getValue("config")) {
                is JsonNull -> AppraisalEngine.DEFAULT_CONFIG
                else -> if (cfg.jsonPrimitive.content == "custom") custom else fail("$name: unknown config")
            }
            val roleKey = (inp.getValue("roleKey") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            val salary = Js.num(inp["salary"])
            val rating = inp.getValue("rating").let { if (it is JsonNull) null else AppraisalJson.decodeFromJsonElement<Rating>(it) }
            val out = AppraisalEngine.computeAppraisal(AppraisalInput(config, roleKey, salary, growthOf(inp.getValue("growth")), rating))
            assertJsonEquals(case.getValue("expected"),
                AppraisalJson.encodeToJsonElement(AppraisalResult.serializer(), out), name)
            // compute5C's total is the computed score
            assertEquals(out.computedScore, AppraisalEngine.compute5C(config, roleKey, rating).total, name)
        }
    }

    @Test
    fun scoreFromBandMatches() {
        for (c in helperFx.getValue("bands").jsonArray) {
            val o = c.jsonObject
            val bands = AppraisalJson.decodeFromJsonElement<List<Band>>(o.getValue("bands"))
            assertEquals(o.getValue("expected").jsonPrimitive.double,
                AppraisalEngine.scoreFromBand(bands, o.getValue("achieved")), "bands=$bands achieved=${o["achieved"]}")
        }
    }

    @Test
    fun poolCreditedAndAchievementMatch() {
        for (c in helperFx.getValue("pool").jsonArray) {
            val o = c.jsonObject
            val w = AppraisalJson.decodeFromJsonElement<PoolWeights>(o.getValue("poolWeights"))
            assertEquals(o.getValue("expected").jsonPrimitive.double,
                AppraisalEngine.companyGrowthPool(growthOf(o.getValue("growth")), w), o.toString())
        }
        for (c in helperFx.getValue("credited").jsonArray) {
            val r = c.jsonObject.getValue("row").jsonObject
            val row = AumRow(Js.num(r["b2cO"]), Js.num(r["b2cC"]), Js.num(r["b2bO"]), Js.num(r["b2bC"]))
            assertEquals(c.jsonObject.getValue("expected").jsonPrimitive.double, AppraisalEngine.creditedGrowthPct(row), r.toString())
        }
        for (c in helperFx.getValue("achievement").jsonArray) {
            val o = c.jsonObject
            assertEquals(o.getValue("expected").jsonPrimitive.double,
                AppraisalEngine.achievementPct(o.getValue("total").jsonPrimitive.double, o.getValue("target").jsonPrimitive.double), o.toString())
        }
    }

    @Test
    fun yearHelpersMatch() {
        for (c in helperFx.getValue("monthKey").jsonArray) {
            val o = c.jsonObject
            val d = o.getValue("date").let { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals(o.getValue("expected").jsonPrimitive.content, AppraisalEngine.monthKey(d), "monthKey($d)")
        }
        for (c in helperFx.getValue("years").jsonArray) {
            val o = c.jsonObject
            val y = o.getValue("year").jsonPrimitive.int; val cy = o.getValue("cycle").jsonPrimitive.content
            assertEquals(o.getValue("monthKeys").jsonArray.map { it.jsonPrimitive.content }, AppraisalEngine.appraisalMonthKeys(y, cy))
            assertEquals(o.getValue("label").jsonPrimitive.content, AppraisalEngine.appraisalYearLabel(y, cy))
        }
        for (c in helperFx.getValue("currentYear").jsonArray) {
            val o = c.jsonObject
            assertEquals(o.getValue("expected").jsonPrimitive.int, AppraisalEngine.currentAppraisalYear(
                o.getValue("cycle").jsonPrimitive.content, LocalDate.parse(o.getValue("today").jsonPrimitive.content)), o.toString())
        }
    }
}
