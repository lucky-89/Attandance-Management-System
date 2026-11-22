@file:OptIn(ExperimentalSerializationApi::class)

package com.finowise.hr.core.appraisal

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** The Json instance every appraisal type is meant to be read and written with. */
val AppraisalJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

object Cycle {
    /** April to March. FY 2026 = Apr-2026 ... Mar-2027. */
    const val FY = "FY"
    /** January to December. Anything other than "FY" is treated as calendar, as in the engine. */
    const val CALENDAR = "calendar"
}

/* ============================ ENGINE CONFIG ============================ */

/** A KPI scores [value] when its achievement % reaches [min]; the highest band reached wins. */
@Serializable
data class Band(val min: JsDouble, @SerialName("val") val value: JsDouble)

/** One KPI of a role. [auto] is the engine's "meetings" / "opsPoints" marker; in this app every KPI is entered by hand. */
@Serializable
data class KpiDef(
    val key: String,
    val label: String,
    val weight: JsDouble = 0.0,
    val auto: String? = null,
    val bands: List<Band> = emptyList(),
)

@Serializable
data class RoleDef(val label: String, val kpis: List<KpiDef> = emptyList())

/** Weights of the four judged Cs; Contribution's weight is 100 minus their sum. */
@Serializable
data class CWeights(
    val curiosity: JsDouble,
    val competence: JsDouble,
    val commitment: JsDouble,
    val creativity: JsDouble,
)

@Serializable
data class PoolWeights(val abs: JsDouble, val sip: JsDouble, val lump: JsDouble)

@Serializable
data class Targets(val opsPoints: JsDouble, val meetings: JsDouble)

/** The engine's full CONFIG SHAPE (`DEFAULT_CONFIG`, or BOSS's merged `apprConfig()`). */
@Serializable
data class EngineConfig(
    val cycle: String = Cycle.FY,
    val cWeights: CWeights,
    val poolWeights: PoolWeights,
    val targets: Targets,
    val roles: Map<String, RoleDef>,
)

/* ===================== STORED SETTINGS (DB.config.appraisal) ===================== */

@Serializable
data class PartialCWeights(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val curiosity: JsDouble? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val competence: JsDouble? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val commitment: JsDouble? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val creativity: JsDouble? = null,
)

@Serializable
data class PartialPoolWeights(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val abs: JsDouble? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sip: JsDouble? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lump: JsDouble? = null,
)

@Serializable
data class PartialTargets(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val opsPoints: JsDouble? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val meetings: JsDouble? = null,
)

/** Company growth % for one appraisal year. Each slot is "" (not entered) or a number, as BOSS stores it. */
@Serializable
data class GrowthEntry(
    val abs: JsonElement = JsonPrimitive(""),
    val sip: JsonElement = JsonPrimitive(""),
    val lump: JsonElement = JsonPrimitive(""),
) {
    /** The figures as the engine receives them (BOSS passes each through incNum). */
    fun toGrowth(): Growth = Growth(Js.incNum(abs), Js.incNum(sip), Js.incNum(lump))
    val isEmpty: Boolean get() = Js.isBlank(abs) && Js.isBlank(sip) && Js.isBlank(lump)
    operator fun get(field: GrowthField): JsonElement = when (field) {
        GrowthField.ABS -> abs; GrowthField.SIP -> sip; GrowthField.LUMP -> lump
    }
}

enum class GrowthField(val key: String) { ABS("abs"), SIP("sip"), LUMP("lump") }

/**
 * The firm's own appraisal settings, stored sparse exactly like BOSS's `DB.config.appraisal`:
 * anything left null falls back to the engine default. [roleMap] keys are
 * `"role:<normalised role>"` or `"dept:<normalised department>"`; [growth] is keyed by year string.
 */
@Serializable
data class AppraisalSettings(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val cycle: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val cWeights: PartialCWeights? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val poolWeights: PartialPoolWeights? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val targets: PartialTargets? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val roles: Map<String, RoleDef>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val roleMap: Map<String, String>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val defaultRoleKey: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val growth: Map<String, GrowthEntry>? = null,
)

/* ============================ ENGINE INPUT ============================ */

/** Company growth %, for the appraisal year. */
data class Growth(val abs: Double = 0.0, val sip: Double = 0.0, val lump: Double = 0.0)

/** Opening/closing AUM split B2C and B2B, for [AppraisalEngine.creditedGrowthPct]. */
data class AumRow(val b2cO: Double = 0.0, val b2cC: Double = 0.0, val b2bO: Double = 0.0, val b2bC: Double = 0.0)

/**
 * `rec.rating` as BOSS stores it. Each of the Cs is "" or a number 0-100; [kpi] holds an achievement %
 * per KPI of the role, in order ("" / null where not entered); [scoreOverride] is null or a number.
 * Values are kept as JsonElement so old data (strings, holes) round-trips and computes like the engine.
 */
@Serializable
data class Rating(
    val curiosity: JsonElement = JsonPrimitive(""),
    val competence: JsonElement = JsonPrimitive(""),
    val commitment: JsonElement = JsonPrimitive(""),
    val creativity: JsonElement = JsonPrimitive(""),
    val kpi: List<JsonElement> = emptyList(),
    val scoreOverride: JsonElement = JsonNull,
    /** Index -> "BOSS" where a KPI was taken from a BOSS suggestion. Kept for round-trip only. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val kpiFrom: Map<String, String>? = null,
) {
    operator fun get(field: RatingField): JsonElement = when (field) {
        RatingField.CURIOSITY -> curiosity; RatingField.COMPETENCE -> competence
        RatingField.COMMITMENT -> commitment; RatingField.CREATIVITY -> creativity
        RatingField.SCORE_OVERRIDE -> scoreOverride
    }

    companion object {
        /** Build from plain numbers; null means "not entered". */
        fun of(
            curiosity: Double? = null, competence: Double? = null, commitment: Double? = null, creativity: Double? = null,
            kpi: List<Double?> = emptyList(), scoreOverride: Double? = null,
        ) = Rating(
            cell(curiosity), cell(competence), cell(commitment), cell(creativity),
            kpi.map(::cell), scoreOverride?.let(Js::json) ?: JsonNull,
        )

        internal fun cell(v: Double?): JsonElement = if (v == null) JsonPrimitive("") else Js.json(v)
    }
}

enum class RatingField(val key: String, val adminOnly: Boolean) {
    CURIOSITY("curiosity", true),
    COMPETENCE("competence", true),
    COMMITMENT("commitment", true),
    CREATIVITY("creativity", true),
    /** Replaces the computed 5 Cs score (0-100). BOSS lets a reporting manager set it. */
    SCORE_OVERRIDE("scoreOverride", false),
}

data class AppraisalInput(
    val config: EngineConfig = AppraisalEngine.DEFAULT_CONFIG,
    val roleKey: String? = null,
    /** Current ANNUAL CTC. */
    val salary: Double = 0.0,
    val growth: Growth? = null,
    val rating: Rating? = null,
)

/* ============================ ENGINE OUTPUT ============================ */

@Serializable
data class KpiScore(
    val key: String,
    val label: String,
    val weight: JsDouble,
    val achieved: JsDouble,
    val points: JsDouble,
)

@Serializable
data class Breakdown(
    val curiosity: JsDouble,
    val competence: JsDouble,
    val commitment: JsDouble,
    val creativity: JsDouble,
    val contribution: JsDouble,
    val contributionWeight: JsDouble,
    val kpis: List<KpiScore>,
)

/** `compute5C` result. */
data class FiveCs(
    val curiosity: Double,
    val competence: Double,
    val commitment: Double,
    val creativity: Double,
    val contribution: Double,
    val contributionWeight: Double,
    val kpis: List<KpiScore>,
    val total: Double,
)

/** `computeAppraisal` result; also what BOSS freezes into `snapshot` when an appraisal is finalised. */
@Serializable
data class AppraisalResult(
    /** Score actually used for the increment (the override if any). */
    val score: JsDouble,
    /** What the formula says, before any override. */
    val computedScore: JsDouble,
    val isOverridden: Boolean,
    val pool: JsDouble,
    val incrementPct: JsDouble,
    val salary: JsDouble,
    /** Appraisal amount, rounded to the rupee. */
    val amount: JsDouble,
    val newSalary: JsDouble,
    val monthlyCTC: JsDouble,
    val newMonthlyCTC: JsDouble,
    val breakdown: Breakdown,
)

/* ============================ STORED RECORD ============================ */

object AppraisalStatus {
    const val DRAFT = "Draft"
    const val FINALISED = "Finalised"
}

/** One row of BOSS's `hrAppraisals` store. */
@Serializable
data class AppraisalRecord(
    val id: String,
    val userId: String,
    val year: String,
    val rating: Rating = Rating(),
    val status: String = AppraisalStatus.DRAFT,
    val snapshot: AppraisalResult? = null,
    val createdAt: String? = null,
    val createdBy: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val updatedAt: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val finalisedAt: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val finalisedBy: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val reopenedAt: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val reopenedBy: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val deletedAt: String? = null,
) {
    val isFinalised: Boolean get() = status == AppraisalStatus.FINALISED
}
