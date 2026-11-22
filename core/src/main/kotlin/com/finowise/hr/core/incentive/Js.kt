package com.finowise.hr.core.incentive

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.floor

/**
 * The handful of JavaScript semantics the BOSS incentive code leans on (Number(), String(),
 * truthiness, \s, Math.round, toFixed, toLocaleString('en-IN')), reproduced so that the Kotlin
 * port gives the same answer on the same stored JSON — including odd values typed into a sheet.
 * `null` as a Kotlin argument stands for JavaScript `undefined` (a missing key).
 */
object Js {
    /** JavaScript's \s / String.prototype.trim whitespace set. */
    private const val WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private val WS_RUN = Regex("[$WS]+")
    private val WS_EDGES = Regex("^[$WS]+|[$WS]+$")
    private val DECIMAL = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")
    private val INFINITY = Regex("^[+-]?Infinity$")
    private val RADIX = Regex("^0([xXoObB])([0-9a-fA-F]+)$")

    fun trim(s: String): String = s.replace(WS_EDGES, "")

    fun isNumber(e: JsonElement?): Boolean =
        e is JsonPrimitive && e !is JsonNull && !e.isString && e.content != "true" && e.content != "false"

    fun isBoolean(e: JsonElement?): Boolean =
        e is JsonPrimitive && !e.isString && (e.content == "true" || e.content == "false")

    /** `Number(v)` as a JS double (may be NaN or infinite). */
    fun toNumber(e: JsonElement?): Double = when {
        e == null -> Double.NaN
        e is JsonNull -> 0.0
        isBoolean(e) -> if ((e as JsonPrimitive).content == "true") 1.0 else 0.0
        isNumber(e) -> (e as JsonPrimitive).content.toDoubleOrNull() ?: Double.NaN
        e is JsonPrimitive -> stringToNumber(e.content)
        e is JsonArray -> stringToNumber(toStr(e))
        else -> Double.NaN
    }

    fun stringToNumber(raw: String): Double {
        val s = trim(raw)
        if (s.isEmpty()) return 0.0
        if (INFINITY.matches(s)) return if (s.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        RADIX.matchEntire(s)?.let { m ->
            val radix = when (m.groupValues[1].lowercase()) { "x" -> 16; "o" -> 8; else -> 2 }
            return try { BigInteger(m.groupValues[2], radix).toDouble() } catch (_: NumberFormatException) { Double.NaN }
        }
        return if (DECIMAL.matches(s)) s.toDouble() else Double.NaN
    }

    /** BOSS's `incNum`: Number(v), or 0 when that is not finite. */
    fun num(e: JsonElement?): Double { val n = toNumber(e); return if (n.isFinite()) n else 0.0 }

    /** `String(v)`; `null` (undefined) gives "undefined". */
    fun toStr(e: JsonElement?): String = when {
        e == null -> "undefined"
        e is JsonNull -> "null"
        e is JsonPrimitive && e.isString -> e.content
        isBoolean(e) -> (e as JsonPrimitive).content
        e is JsonPrimitive -> numberToString(e.content.toDouble())
        e is JsonArray -> e.joinToString(",") { if (it is JsonNull) "" else toStr(it) }
        else -> "[object Object]"
    }

    fun truthy(e: JsonElement?): Boolean = when {
        e == null || e is JsonNull -> false
        isBoolean(e) -> (e as JsonPrimitive).content == "true"
        isNumber(e) -> { val d = (e as JsonPrimitive).content.toDouble(); d != 0.0 && !d.isNaN() }
        e is JsonPrimitive -> e.content.isNotEmpty()
        else -> true
    }

    /** `a || b` on JSON values. */
    fun or(a: JsonElement?, b: JsonElement?): JsonElement? = if (truthy(a)) a else b

    /** BOSS's `incKey`: squeezed, trimmed, lower-cased text. */
    fun key(e: JsonElement?): String =
        (if (e == null || e is JsonNull) "" else toStr(e)).replace(WS_RUN, " ").trim(' ').lowercase()

    fun key(s: String?): String = key(s?.let { JsonPrimitive(it) })

    /** JS `Number.prototype.toString()`. */
    fun numberToString(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        if (d == 0.0) return "0"
        val neg = d < 0
        val bd = BigDecimal(java.lang.Double.toString(Math.abs(d))).stripTrailingZeros()
        val digits = bd.unscaledValue().toString()
        val k = digits.length
        val n = k - bd.scale() // decimal exponent: value = 0.digits * 10^n
        val body = when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                val sign = if (e >= 0) "+" else "-"
                (if (k == 1) digits else digits[0] + "." + digits.substring(1)) + "e" + sign + Math.abs(e)
            }
        }
        return if (neg) "-$body" else body
    }

    /** JS `Math.round` (ties toward +infinity; keeps -0). */
    fun round(x: Double): Double {
        if (!x.isFinite()) return x
        val f = floor(x)
        val r = if (x - f >= 0.5) f + 1 else f
        return if (r == 0.0 && (x < 0 || (x == 0.0 && 1.0 / x < 0))) -0.0 else r
    }

    /** `String(Math.round(x))`. */
    fun roundStr(x: Double): String = numberToString(round(x))

    /** JS `x.toFixed(2)`. */
    fun toFixed2(x: Double): String {
        if (!x.isFinite()) return numberToString(x)
        if (Math.abs(x) >= 1e21) return numberToString(x)
        val s = BigDecimal(x).setScale(2, RoundingMode.HALF_UP).toPlainString()
        return if (s.startsWith("-") && BigDecimal(s).signum() == 0) s.substring(1) else s
    }

    /** `(r * 100).toFixed(2).replace(/\.?0+$/, '')`. */
    fun percent(rate: Double): String = toFixed2(rate * 100).replaceFirst(Regex("\\.?0+$"), "")

    /** Integer grouped the en-IN way: 12,34,56,789. */
    fun groupIndian(x: Double): String {
        val r = round(x)
        if (!r.isFinite()) return if (r.isNaN()) "NaN" else if (r > 0) "∞" else "-∞"
        val negZero = r == 0.0 && 1.0 / r < 0
        val neg = r < 0 || negZero
        val digits = BigDecimal(Math.abs(r)).toBigInteger().toString()
        val sb = StringBuilder()
        if (digits.length <= 3) sb.append(digits) else {
            val head = digits.substring(0, digits.length - 3)
            val tail = digits.substring(digits.length - 3)
            val groups = ArrayList<String>()
            var i = head.length
            while (i > 0) { groups.add(0, head.substring(maxOf(0, i - 2), i)); i -= 2 }
            sb.append(groups.joinToString(",")).append(',').append(tail)
        }
        return (if (neg) "-" else "") + sb
    }

    /** BOSS's `money(n)` with the default ₹ and en-IN settings. */
    fun money(n: Double, symbol: String = "₹"): String = symbol + groupIndian(if (n.isNaN()) 0.0 else n)

    /** A double as JSON the way JSON.stringify writes it (whole numbers without ".0"). */
    fun jnum(d: Double): JsonElement = when {
        !d.isFinite() -> JsonNull
        d == floor(d) && Math.abs(d) < 9.007199254740992E15 -> JsonPrimitive(d.toLong())
        else -> JsonPrimitive(d)
    }

    /** Rewrites whole-number doubles (5625000.0) as integers (5625000), as JSON.stringify would. */
    fun normalize(e: JsonElement): JsonElement = when {
        e is JsonObject -> JsonObject(e.mapValues { normalize(it.value) })
        e is JsonArray -> JsonArray(e.map { normalize(it) })
        isNumber(e) -> { val d = (e as JsonPrimitive).content.toDoubleOrNull(); if (d == null) e else jnum(d) }
        else -> e
    }

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** `new Date().toISOString()`. */
    fun iso(now: Instant): String = ISO.format(now)
}

internal fun JsonElement?.prop(name: String): JsonElement? = (this as? JsonObject)?.get(name)
internal fun JsonElement?.arr(): List<JsonElement>? = this as? JsonArray
internal fun JsonElement?.strOrNull(): String? = when {
    this == null || this is JsonNull -> null
    this is JsonPrimitive -> this.content
    else -> Js.toStr(this)
}
