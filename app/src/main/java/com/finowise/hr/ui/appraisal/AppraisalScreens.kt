package com.finowise.hr.ui.appraisal

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.finowise.hr.AppVm
import com.finowise.hr.core.Fmt
import com.finowise.hr.core.HrRules
import com.finowise.hr.core.Outcome
import com.finowise.hr.core.appraisal.*
import com.finowise.hr.core.incentive.IncentiveTexts
import com.finowise.hr.core.model.Employee
import com.finowise.hr.core.model.HrData
import com.finowise.hr.ui.common.*
import com.finowise.hr.ui.incentive.CellField
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

fun Employee.apprPerson() = AppraisalPerson(id, name, role, department, branch, ctc ?: 0.0, parentUserId.ifBlank { null }, appraisalRoleKey.ifBlank { null }, isActive)
private fun HrRules.viewer() = Viewer(currentUserId ?: "", isAdmin = isHrAdmin(), isHrStaff = isStaff())
private fun HrData.apprSettings(): AppraisalSettings? = appraisalConfig?.let { runCatching { AppraisalJson.decodeFromJsonElement(AppraisalSettings.serializer(), it) }.getOrNull() }
private fun HrData.apprRecords(): List<AppraisalRecord> = hrAppraisals.mapNotNull { runCatching { AppraisalJson.decodeFromJsonElement(AppraisalRecord.serializer(), it) }.getOrNull() }
    .filter { it.deletedAt == null }
private fun money(n: Double) = IncentiveTexts.money(n)
private fun pct(n: Double) = Fmt.num(n) + "%"
private fun cellText(e: kotlinx.serialization.json.JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" } ?: ""

private fun saveRecord(vm: AppVm, by: String, rec: AppraisalRecord, action: String): Outcome {
    val json = AppraisalJson.encodeToJsonElement(AppraisalRecord.serializer(), rec).jsonObject
    return vm.service.updateModules(by, action, rec.id) { d ->
        val has = d.hrAppraisals.any { (it["id"] as? JsonPrimitive)?.contentOrNull == rec.id }
        d.copy(hrAppraisals = if (has) d.hrAppraisals.map { if ((it["id"] as? JsonPrimitive)?.contentOrNull == rec.id) json else it } else d.hrAppraisals + json)
    }
}
private fun saveSettings(vm: AppVm, by: String, s: AppraisalSettings, action: String) =
    vm.service.updateModules(by, action, "appraisalConfig") { it.copy(appraisalConfig = AppraisalJson.encodeToJsonElement(AppraisalSettings.serializer(), s).jsonObject) }

@Composable
private fun YearPicker(year: Int, cycle: String?, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(year - 1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
        Text(AppraisalEngine.appraisalYearLabel(year, cycle), Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = { onChange(year + 1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
    }
}

@Composable
fun AppraisalScreen(vm: AppVm, nav: NavController) {
    val data by vm.data.collectAsStateWithLifecycle()
    val r = vm.rules(data)
    val settings = data.apprSettings()
    val cycle = Appraisals.effectiveConfig(settings).cycle
    var year by remember { mutableIntStateOf(AppraisalEngine.currentAppraisalYear(cycle, java.time.LocalDate.now(vm.service.zone))) }
    Screen("Appraisal management", onBack = { nav.popBackStack() }) {
        if (!r.moduleOpen("hrappraisal")) { Empty("Appraisal Management has not been granted to this account."); return@Screen }
        YearPicker(year, cycle) { year = it }
        val g = Appraisals.growthFor(settings, year.toString())
        Section("Company growth ${AppraisalEngine.appraisalYearLabel(year, cycle)}") {
            if (g.isEmpty) Note("Not entered yet — the increment pool is 0% until an administrator enters it in Appraisal settings.", "warn")
            else {
                val pool = AppraisalEngine.companyGrowthPool(g.toGrowth(), Appraisals.effectiveConfig(settings).poolWeights)
                Note("Absolute ${cellText(g.abs)}% · SIP ${cellText(g.sip)}% · New lumpsum ${cellText(g.lump)}% → pool ${pct(pool)}")
            }
        }
        val people = Appraisals.visiblePeople(r.viewer(), data.team.filter { it.isActive }.map { it.apprPerson() }).sortedBy { it.name.lowercase() }
        val records = data.apprRecords()
        Section("People") {
            if (people.isEmpty()) Empty("Nobody whose appraisal you may see. A reporting manager sees the people under them; HR sees everyone.")
            people.forEach { p ->
                val rec = Appraisals.recordFor(records, p.id, year.toString())
                val res = rec?.let { Appraisals.compute(it, p, settings) }
                ListRow(p.name, (Appraisals.roleKeyOf(p, settings)?.let { Appraisals.effectiveConfig(settings).roles[it]?.label } ?: "No KPI set") +
                    (res?.let { " · score ${Fmt.num(it.score)} · +${pct(it.incrementPct)} · new CTC ${money(it.newSalary)}" } ?: " · not started"),
                    { nav.navigate("appraisalEdit/${p.id}/$year") }) {
                    rec?.let { Chip(it.status, if (it.isFinalised) "ok" else "warn") }
                }
            }
        }
    }
}

@Composable
fun AppraisalEditScreen(vm: AppVm, userId: String, yearStr: String, onBack: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val r = vm.rules(data)
    val settings = data.apprSettings()
    val cfg = Appraisals.effectiveConfig(settings)
    val people = data.team.map { it.apprPerson() }
    val p = people.find { it.id == userId }
    val viewer = r.viewer()
    var confirm by remember { mutableStateOf<String?>(null) }
    Screen((p?.name ?: "") + " — " + AppraisalEngine.appraisalYearLabel(yearStr.toInt(), cfg.cycle), onBack = onBack) {
        if (p == null || !Appraisals.maySee(viewer, userId, people)) { Empty("Restricted."); return@Screen }
        val rec = Appraisals.recordFor(data.apprRecords(), userId, yearStr)
        val mayRate = Appraisals.mayRate(viewer, p)
        val ready = Appraisals.readiness(p, yearStr, settings, rec)
        Section("Basis") {
            LabelValue("Annual CTC", if (p.ctc > 0) money(p.ctc) else "not set — HR adds it in Employees")
            LabelValue("KPI set", (ready.role?.label ?: "none") + " (" + ready.roleSource.name.lowercase() + ")")
            if (ready.missing.isNotEmpty()) Note("Still missing: " + ready.missing.joinToString(", "), "warn")
        }
        if (rec == null) {
            if (mayRate) Button(onClick = {
                when (val o = Appraisals.open(data.apprRecords(), viewer, p, yearStr, vm.service.uid("APR"), vm.service.now().toString())) {
                    is AppraisalOutcome.Ok -> vm.act(showOk = false) { saveRecord(vm, viewer.id, o.value, "Appraisal Opened") }
                    is AppraisalOutcome.Denied -> vm.say(o.reason)
                    else -> {}
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Start this appraisal") }
            else Empty("Not started. Their reporting manager or HR starts it.")
            return@Screen
        }
        val editable = Appraisals.guard(rec, viewer, p) == null
        fun apply(o: AppraisalOutcome<AppraisalRecord>, action: String = "Appraisal Edited") = when (o) {
            is AppraisalOutcome.Ok -> vm.act(showOk = false) { saveRecord(vm, viewer.id, o.value, action) }
            is AppraisalOutcome.Denied -> vm.say(o.reason)
            is AppraisalOutcome.NeedsConfirmation -> confirm = o.question
        }
        val now = vm.service.now().toString()
        Section("The four Cs (0–100)", trailing = { Chip(rec.status, if (rec.isFinalised) "ok" else "warn") }) {
            if (!Appraisals.mayJudge(viewer)) Note("Entered by an administrator.")
            RatingField.entries.filter { it.adminOnly }.forEach { f ->
                CellField(f.key.replaceFirstChar { it.uppercase() } + " (weight ${Fmt.num(when (f) {
                    RatingField.CURIOSITY -> cfg.cWeights.curiosity; RatingField.COMPETENCE -> cfg.cWeights.competence
                    RatingField.COMMITMENT -> cfg.cWeights.commitment; else -> cfg.cWeights.creativity })}%)",
                    cellText(rec.rating[f]), editable && Appraisals.mayEdit(viewer, p, f), number = true) { v ->
                    apply(Appraisals.setRating(rec, viewer, p, f, v.toDoubleOrNull(), now)) }
            }
        }
        Section("Contribution — KPI achievement %") {
            val kpis = ready.role?.kpis ?: emptyList()
            if (kpis.isEmpty()) Empty("No KPI set is mapped for this person. An administrator maps roles in Appraisal settings.")
            kpis.forEachIndexed { i, k ->
                CellField(k.label + " (weight ${Fmt.num(k.weight)})", cellText(rec.rating.kpi.getOrNull(i)), editable, number = true) { v ->
                    apply(Appraisals.setKpi(rec, viewer, p, i, v.toDoubleOrNull(), now)) }
            }
            if (kpis.isNotEmpty()) KpiHelper()
        }
        Section("Score override") {
            CellField("Override the computed score (blank = none)", cellText(rec.rating.scoreOverride), editable, number = true) { v ->
                apply(Appraisals.setRating(rec, viewer, p, RatingField.SCORE_OVERRIDE, v.toDoubleOrNull(), now)) }
        }
        val res = Appraisals.compute(rec, p, settings)
        Section(if (rec.isFinalised) "Result (as finalised)" else "Result (worked out now)") {
            KpiGrid(listOf(
                Triple(Fmt.num(res.score) + if (res.isOverridden) " *" else "", "Score" + if (res.isOverridden) " (override; formula ${Fmt.num(res.computedScore)})" else "", "info"),
                Triple(pct(res.pool), "Company pool", ""),
                Triple(pct(res.incrementPct), "Increment", "ok"),
                Triple(money(res.amount), "Appraisal amount", "ok"),
                Triple(money(res.newSalary), "New annual CTC", "ok"),
                Triple(money(res.newMonthlyCTC), "New monthly CTC (was ${money(res.monthlyCTC)})", ""),
            ))
            val b = res.breakdown
            Note("Curiosity ${Fmt.num(b.curiosity)} · Competence ${Fmt.num(b.competence)} · Commitment ${Fmt.num(b.commitment)} · Creativity ${Fmt.num(b.creativity)} · " +
                "Contribution ${Fmt.num(b.contribution)} (weight ${Fmt.num(b.contributionWeight)}%)")
            b.kpis.forEach { k -> Note("${k.label}: ${Fmt.num(k.achieved)}% achieved → ${Fmt.num(k.points)} points of ${Fmt.num(k.weight)}") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!rec.isFinalised && mayRate) Button(onClick = { apply(Appraisals.finalise(rec, viewer, p, settings, false, now), "Appraisal Finalised") }, Modifier.weight(1f)) { Text("Finalise") }
            if (rec.isFinalised && viewer.isAdmin) OutlinedButton(onClick = { apply(Appraisals.reopen(rec, viewer, now), "Appraisal Reopened") }, Modifier.weight(1f)) { Text("Reopen") }
        }
        if (rec.isFinalised) Note("Finalised ${Fmt.date(rec.finalisedAt)} by ${r.name(rec.finalisedBy)}. Later changes to growth, weights or CTC do not restate it.")
        confirm?.let { q ->
            AlertDialog(onDismissRequest = { confirm = null }, text = { Text(q) },
                confirmButton = { TextButton(onClick = { confirm = null; apply(Appraisals.finalise(rec, viewer, p, settings, true, now), "Appraisal Finalised") }) { Text("Finalise anyway") } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
        }
    }
}

/** Achievement % from a total and a target, for KPIs typed in by hand. */
@Composable
private fun KpiHelper() {
    var total by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Field("Achieved", total, { total = it }, Modifier.weight(1f), number = true)
        Field("Target", target, { target = it }, Modifier.weight(1f), number = true)
    }
    val t = total.toDoubleOrNull(); val g = target.toDoubleOrNull()
    if (t != null && g != null) Note("= " + Fmt.num(AppraisalEngine.achievementPct(t, g)) + "% achievement")
}

@Composable
fun AppraisalSettingsScreen(vm: AppVm, onBack: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val r = vm.rules(data)
    val viewer = r.viewer()
    val settings = data.apprSettings()
    val cfg = Appraisals.effectiveConfig(settings)
    var year by remember { mutableIntStateOf(AppraisalEngine.currentAppraisalYear(cfg.cycle, java.time.LocalDate.now(vm.service.zone))) }
    fun apply(o: AppraisalOutcome<AppraisalSettings>, action: String) = when (o) {
        is AppraisalOutcome.Ok -> vm.act(showOk = false) { saveSettings(vm, viewer.id, o.value, action) }
        is AppraisalOutcome.Denied -> vm.say(o.reason)
        else -> {}
    }
    val roleOptions = listOf("" to "—") + cfg.roles.map { it.key to it.value.label }
    Screen("Appraisal settings", onBack = onBack) {
        if (!viewer.isAdmin) { Empty("For HR administrators."); return@Screen }
        Section("Company growth") {
            YearPicker(year, cfg.cycle) { year = it }
            val g = Appraisals.growthFor(settings, year.toString())
            GrowthField.entries.forEach { f ->
                CellField(when (f) { GrowthField.ABS -> "Absolute AUM growth %"; GrowthField.SIP -> "SIP book growth %"; GrowthField.LUMP -> "New lumpsum growth %" },
                    cellText(g[f]), true, number = true) { v -> apply(Appraisals.setGrowth(settings, viewer, year.toString(), f, v.toDoubleOrNull()), "Appraisal Growth Set") }
            }
            GrowthCalc()
        }
        Section("Who is on which KPI set") {
            Dropdown("Default KPI set", Appraisals.defaultRoleKey(settings) ?: "", roleOptions, { apply(Appraisals.setDefaultRoleKey(settings, viewer, it.ifBlank { null }), "Appraisal Default Role Set") })
            Note("A person's own pin (Employees) beats a role mapping, which beats a department mapping, which beats the default.")
            data.hrConfig.roles.forEach { role ->
                val k = settings?.roleMap?.get(Appraisals.roleMapKey(RoleMapKind.ROLE, role)) ?: ""
                Dropdown("Role: $role", k, roleOptions, { apply(Appraisals.setRoleMap(settings, viewer, RoleMapKind.ROLE, role, it.ifBlank { null }), "Appraisal Role Mapped") })
            }
            data.hrConfig.departments.forEach { dept ->
                val k = settings?.roleMap?.get(Appraisals.roleMapKey(RoleMapKind.DEPARTMENT, dept)) ?: ""
                Dropdown("Department: $dept", k, roleOptions, { apply(Appraisals.setRoleMap(settings, viewer, RoleMapKind.DEPARTMENT, dept, it.ifBlank { null }), "Appraisal Department Mapped") })
            }
        }
        Section("KPI sets") {
            cfg.roles.forEach { (_, role) ->
                Text(role.label, fontWeight = FontWeight.SemiBold)
                Note(role.kpis.joinToString(" · ") { it.label + " (" + Fmt.num(it.weight) + ")" })
            }
            Note("Weights: Curiosity ${Fmt.num(cfg.cWeights.curiosity)}, Competence ${Fmt.num(cfg.cWeights.competence)}, Commitment ${Fmt.num(cfg.cWeights.commitment)}, " +
                "Creativity ${Fmt.num(cfg.cWeights.creativity)}; Contribution the rest. Pool: absolute ${Fmt.num(cfg.poolWeights.abs)}, SIP ${Fmt.num(cfg.poolWeights.sip)}, lumpsum ${Fmt.num(cfg.poolWeights.lump)}.")
        }
    }
}

@Composable
private fun GrowthCalc() {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    Note("Growth from two totals a year apart:")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("Opening", a, { a = it }, Modifier.weight(1f), number = true)
        Field("Closing", b, { b = it }, Modifier.weight(1f), number = true)
    }
    val x = a.toDoubleOrNull(); val y = b.toDoubleOrNull()
    if (x != null && y != null) Note(Appraisals.growthPct(x, y)?.let { "= " + Fmt.num(it) + "% growth" } ?: "Opening must be above zero.")
}

@Suppress("unused") private val keep: JsonObject? = null
