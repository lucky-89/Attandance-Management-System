package com.finowise.hr.core.incentive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.fail

/** Fixtures written by the node oracle (BOSS's own incentive functions, run on the same inputs). */
object Fixtures {
    fun load(name: String): JsonElement {
        val text = Fixtures::class.java.getResourceAsStream("/incentive/$name")?.bufferedReader()?.readText()
            ?: error("missing fixture $name")
        return Json.parseToJsonElement(text)
    }

    fun person(e: JsonElement): IncentivePerson {
        val o = e.jsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        return IncentivePerson(id = s("id")!!, name = s("name") ?: "", role = s("role") ?: "",
            department = s("department") ?: "", branch = s("branch") ?: "",
            ctc = s("ctc")?.toDouble() ?: 0.0, incentiveCategory = s("incentiveCategory"))
    }

    fun objOrNull(e: JsonElement?): JsonObject? = e as? JsonObject

    /**
     * Deep equality as JavaScript would see it after JSON.stringify: numbers compared as doubles
     * (exactly), a missing key equal to null (JS drops undefined), keys in `ignore` skipped.
     */
    fun assertSame(expected: JsonElement?, actual: JsonElement?, path: String = "$", ignore: Set<String> = emptySet()) {
        val e = expected ?: JsonNull
        val a = actual ?: JsonNull
        when {
            e is JsonObject && a is JsonObject -> (e.keys + a.keys).filter { it !in ignore }.forEach { k ->
                assertSame(e[k], a[k], "$path.$k", ignore)
            }
            e is JsonArray && a is JsonArray -> {
                if (e.size != a.size) fail("$path: length ${a.size}, expected ${e.size}\n  actual:   $a\n  expected: $e")
                e.indices.forEach { assertSame(e[it], a[it], "$path[$it]", ignore) }
            }
            e is JsonNull || a is JsonNull -> if (e != a) fail("$path: got $a, expected $e")
            e is JsonPrimitive && a is JsonPrimitive -> {
                if (Js.isNumber(e) && Js.isNumber(a)) {
                    val x = e.content.toDouble(); val y = a.content.toDouble()
                    if (x != y) fail("$path: got $y, expected $x")
                } else if (e.isString != a.isString || e.content != a.content) fail("$path: got $a, expected $e")
            }
            else -> fail("$path: got $a, expected $e")
        }
    }

    fun str(e: JsonElement?) = e!!.jsonPrimitive.content
}
