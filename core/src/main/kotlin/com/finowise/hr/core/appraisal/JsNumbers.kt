package com.finowise.hr.core.appraisal

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * JavaScript number semantics the BOSS engine relies on, reproduced exactly so the
 * port gives bit-identical answers on the same (possibly messy) stored data.
 */
internal object Js {
    private val FLOAT_PREFIX = Regex("^[+-]?(Infinity|(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?)")
    private val FLOAT_FULL = Regex("^[+-]?(Infinity|(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?)$")
    private val HEX = Regex("^0[xX][0-9a-fA-F]+$")

    private fun isJsSpace(c: Char) = c.isWhitespace() || c == ' ' || c == '﻿'

    private fun literal(s: String): Double = when {
        s.endsWith("Infinity") -> if (s.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        else -> s.toDouble()
    }

    /** JS `parseFloat(String(v))`, NaN where JS gives NaN. */
    fun parseFloat(s: String): Double {
        val m = FLOAT_PREFIX.find(s.trimStart(::isJsSpace)) ?: return Double.NaN
        return literal(m.value)
    }

    /** JS `Number(v)` for a string. */
    fun number(s: String): Double {
        val t = s.trim(::isJsSpace)
        if (t.isEmpty()) return 0.0
        if (FLOAT_FULL.matches(t)) return literal(t)
        if (HEX.matches(t)) return t.substring(2).toLong(16).toDouble()
        return Double.NaN
    }

    /** The engine's `num(v)`: parseFloat, NaN -> 0. */
    fun num(e: JsonElement?): Double {
        val v = when (e) {
            null, is JsonNull -> Double.NaN
            is JsonPrimitive -> if (!e.isString && e.booleanOrNull != null) Double.NaN else parseFloat(e.content)
            is JsonArray -> if (e.size == 1) num(e[0]) else Double.NaN // String([x]) === String(x)
            is JsonObject -> Double.NaN
        }
        return if (v.isNaN()) 0.0 else v
    }

    /** BOSS's `incNum(v)`: Number(v), non-finite -> 0. */
    fun incNum(e: JsonElement?): Double {
        val v = when (e) {
            null, is JsonNull -> 0.0
            is JsonPrimitive -> if (!e.isString && e.booleanOrNull != null) (if (e.booleanOrNull == true) 1.0 else 0.0) else number(e.content)
            is JsonArray -> if (e.isEmpty()) 0.0 else if (e.size == 1) incNum(e[0]) else Double.NaN
            is JsonObject -> Double.NaN
        }
        return if (v.isFinite()) v else 0.0
    }

    fun incNum(d: Double?): Double = if (d != null && d.isFinite()) d else 0.0

    /** JS `Math.round`: round half towards +Infinity. */
    fun round(x: Double): Double {
        if (!x.isFinite() || Math.abs(x) >= 4.503599627370496E15) return x
        return Math.round(x).toDouble()
    }

    /** A Double as JSON the way JSON.stringify writes it (10, not 10.0; NaN/Infinity -> null). */
    fun json(d: Double): JsonElement = when {
        !d.isFinite() -> JsonNull
        d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15 -> JsonPrimitive(d.toLong())
        else -> JsonPrimitive(d)
    }

    /** True when the value is "" / null / missing — what BOSS treats as "not entered". */
    fun isBlank(e: JsonElement?): Boolean = e == null || e is JsonNull || (e is JsonPrimitive && e.isString && e.content.isEmpty())

    /** JS `!(v === null || v === undefined || v === '')`. */
    fun isPresent(e: JsonElement?): Boolean = !isBlank(e)
}

/** Serializes a Double the way JSON.stringify does, and reads numbers leniently. */
object JsNumberSerializer : KSerializer<Double> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("finowise.JsNumber", PrimitiveKind.DOUBLE)
    override fun serialize(encoder: Encoder, value: Double) {
        if (encoder is JsonEncoder) encoder.encodeJsonElement(Js.json(value)) else encoder.encodeDouble(value)
    }
    override fun deserialize(decoder: Decoder): Double =
        if (decoder is JsonDecoder) Js.incNum(decoder.decodeJsonElement()) else decoder.decodeDouble()
}

/** A Double that round-trips through JSON in JavaScript's number format. */
typealias JsDouble = @Serializable(with = JsNumberSerializer::class) Double
