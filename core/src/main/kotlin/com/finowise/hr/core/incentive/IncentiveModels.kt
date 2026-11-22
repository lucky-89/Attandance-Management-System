package com.finowise.hr.core.incentive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/*
 * The result of a month's calculation. Field names and nesting are exactly the object BOSS's
 * incCalculate returns (and stores as `snapshot` on a finalised sheet), so `toJson()` writes a
 * BOSS-compatible snapshot and `IncentiveJson.decodeResult` reads one BOSS wrote.
 */

/** A person in the standalone app, as far as incentive needs one. `ctc` is the annual CTC. */
data class IncentivePerson(
    val id: String,
    val name: String = "",
    val role: String = "",
    val department: String = "",
    val branch: String = "",
    val ctc: Double = 0.0,
    val reportingManager: String? = null,
    /** "sales" or "nonSales" to override the central rule; anything else (or null) means "follow the rules". */
    val incentiveCategory: String? = null,
)

@Serializable
data class IncentiveBasis(val ctc: Double, val monthly: Double, val slabCtc: Double, val slabMonthly: Double)

/** One business line, credited: PCW x LSW x MW = multiplier, amount x multiplier = credit. */
@Serializable
data class PricedLine(
    /** The sheet row exactly as stored. */
    val row: JsonElement,
    val amount: Double,
    val pcw: Double?,
    val lsw: Double?,
    val mw: Double?,
    val mult: Double?,
    val credit: Double?,
    val ok: Boolean,
    /** Why the line could not be credited (empty when ok). */
    val problems: List<String>,
)

@Serializable
data class SectionTotal(val amount: Double, val credit: Double, val lines: Int, val dropped: Int)

@Serializable
data class IncentiveSections(
    val investment: List<PricedLine>,
    val sip: List<PricedLine>,
    val insurance: List<PricedLine>,
)

@Serializable
data class IncentiveTotals(val investment: SectionTotal, val sip: SectionTotal, val insurance: SectionTotal)

/** The seven net credits, named as BOSS names them. */
@Serializable
data class StreamCredits(
    val netSales: Double,
    val freshSip: Double,
    val meetings: Double,
    val newClients: Double,
    val callLogs: Double,
    val lifeInsurance: Double,
    val healthInsurance: Double,
) {
    operator fun get(key: String): Double? = when (key) {
        "netSales" -> netSales; "freshSip" -> freshSip; "meetings" -> meetings
        "newClients" -> newClients; "callLogs" -> callLogs
        "lifeInsurance" -> lifeInsurance; "healthInsurance" -> healthInsurance
        else -> null
    }
}

/** One stream: baseline, credit, slab, rate and payout. */
@Serializable
data class StreamResult(
    val key: String? = null,
    val label: String? = null,
    /** "money" or "count". */
    val kind: String? = null,
    /** "pctOfCredit", "perThousandExcess" or (anything else) per excess unit. */
    val payout: String? = null,
    val rateNote: String? = null,
    val baseline: Double,
    val baselineNote: String = "",
    val credit: Double,
    val qualified: Boolean,
    /** Qualified (credit strictly above baseline) but under the first band: no slab, pays nothing. */
    val belowLowest: Boolean,
    /** Band name, or "Not Qualified" / "No Slab". */
    val slab: String? = null,
    val rate: Double,
    val excess: Double,
    val incentive: Double,
    /** The first band's floor, or null when the stream has no bands. */
    val floor: Double? = null,
)

@Serializable
data class LineProblem(val section: String, val line: Int, val why: List<String>)

@Serializable
data class IncentiveResult(
    val basis: IncentiveBasis,
    val sections: IncentiveSections,
    val totals: IncentiveTotals,
    /** Premiums only — not REIS!M90, which wrongly adds the New Client counts. */
    val premiumTotal: Double,
    val credits: StreamCredits,
    val streams: List<StreamResult>,
    val problems: List<LineProblem>,
    val total: Double,
    val slabBase: String? = null,
    val slabCtc: Double,
) {
    fun stream(key: String): StreamResult? = streams.firstOrNull { it.key == key }
    fun toJson(): JsonObject = Js.normalize(IncentiveJson.json.encodeToJsonElement(serializer(), this)).jsonObject
}

/** One task of the non-sales bank, with the count typed on the sheet. */
@Serializable
data class TaskPointsRow(
    val task: JsonElement,
    val found: Double,
    val already: Double = 0.0,
    val counted: Double,
    val capped: Boolean,
    val points: Double,
    val ids: List<JsonElement> = emptyList(),
)

@Serializable
data class TaskPointsResult(
    val rows: List<TaskPointsRow>,
    val points: Double,
    /** Rupees per point; null until an admin sets it. */
    val rate: Double? = null,
    /** points x rate, or null while rate is unset. */
    val payable: Double? = null,
    val ids: List<JsonElement> = emptyList(),
)

/** What a sheet is worth: worked out now (draft) or as it was finalised (snapshot). */
sealed class IncentiveOutcome {
    abstract val total: Double
    /** True when this came off a finalised sheet's stored snapshot rather than being worked out now. */
    abstract val fromSnapshot: Boolean
    abstract fun toJson(): JsonObject

    data class Sales(val result: IncentiveResult, override val fromSnapshot: Boolean = false) : IncentiveOutcome() {
        override val total get() = result.total
        override fun toJson() = result.toJson()
    }

    data class NonSales(
        val taskPoints: TaskPointsResult,
        override val total: Double,
        val payable: Double?,
        override val fromSnapshot: Boolean = false,
        /** BOSS's finalised snapshot has no `payable` key; a draft result does. */
        val includePayable: Boolean = true,
    ) : IncentiveOutcome() {
        override fun toJson(): JsonObject {
            val m = LinkedHashMap<String, JsonElement>()
            m["taskPoints"] = IncentiveJson.json.encodeToJsonElement(TaskPointsResult.serializer(), taskPoints)
            m["total"] = Js.jnum(total)
            if (includePayable) m["payable"] = payable?.let { Js.jnum(it) } ?: kotlinx.serialization.json.JsonNull
            m["streams"] = kotlinx.serialization.json.JsonArray(emptyList())
            m["problems"] = kotlinx.serialization.json.JsonArray(emptyList())
            return Js.normalize(JsonObject(m)).jsonObject
        }
    }
}

object IncentiveJson {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true; isLenient = true }

    fun decodeResult(e: JsonElement): IncentiveResult = json.decodeFromJsonElement(IncentiveResult.serializer(), e)

    /** Reads a stored snapshot (BOSS or this app) back into an outcome. */
    fun decodeSnapshot(snapshot: JsonObject): IncentiveOutcome =
        if (snapshot.containsKey("taskPoints")) {
            val tp = json.decodeFromJsonElement(TaskPointsResult.serializer(), snapshot["taskPoints"]!!)
            IncentiveOutcome.NonSales(tp, Js.num(snapshot["total"]),
                snapshot["payable"]?.let { if (it is kotlinx.serialization.json.JsonNull) null else Js.num(it) } ?: tp.payable,
                fromSnapshot = true, includePayable = snapshot.containsKey("payable"))
        } else IncentiveOutcome.Sales(decodeResult(snapshot), fromSnapshot = true)
}
