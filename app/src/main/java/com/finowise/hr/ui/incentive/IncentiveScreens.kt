package com.finowise.hr.ui.incentive

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.finowise.hr.AppVm
import com.finowise.hr.core.HrRules
import com.finowise.hr.core.Outcome
import com.finowise.hr.core.incentive.*
import com.finowise.hr.core.model.Employee
import com.finowise.hr.core.model.HrData
import com.finowise.hr.ui.common.*
import kotlinx.serialization.json.*
import java.time.YearMonth

fun Employee.incPerson() = IncentivePerson(id, name, role, department, branch, ctc ?: 0.0, parentUserId.ifBlank { null }, incentiveCategory.ifBlank { null })
private fun HrData.sheetFor(userId: String, month: String) =
    hrIncentive.find { it["userId"]?.jsonPrimitive?.contentOrNull == userId && it["month"]?.jsonPrimitive?.contentOrNull == month }
private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""
private fun incMayAdminister(r: HrRules) = r.isHrAdmin()
private fun incMaySee(r: HrRules, userId: String) = userId == r.currentUserId || r.isStaff()
private fun monthLabel(m: String) = YearMonth.parse(m).let { it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() } + " " + it.year }

private fun saveSheet(vm: AppVm, by: String, sheet: JsonObject, action: String): Outcome =
    vm.service.updateModules(by, action, sheet.str("id")) { d ->
        val id = sheet.str("id")
        d.copy(hrIncentive = if (d.hrIncentive.any { it.str("id") == id }) d.hrIncentive.map { if (it.str("id") == id) sheet else it } else d.hrIncentive + sheet)
    }

@Composable
fun IncentiveScreen(vm: AppVm, nav: NavController) {
    val data by vm.data.collectAsStateWithLifecycle()
    val r = vm.rules(data)
    var month by remember { mutableStateOf(r.today.take(7)) }
    val cfg = IncentiveConfig.merge(data.incentiveConfig)
    Screen("Incentive management", onBack = { nav.popBackStack() }) {
        if (!r.moduleOpen("hrincentive")) { Empty("Incentive Management has not been granted to this account."); return@Screen }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = YearMonth.parse(month).minusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
            Text(monthLabel(month), Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { month = YearMonth.parse(month).plusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
        }
        Note("Worked out from the firm's own workbook (${IncentiveConfig.WORKBOOK}). Sales staff are paid on seven streams; non-sales staff on task points.")
        val open = IncentiveConfig.openQueries(data.incentiveConfig)
        if (open.isNotEmpty() && incMayAdminister(r)) Note("${open.size} workbook question(s) still open — see Incentive grid.", "warn")
        var total = 0.0
        val rows = r.people().filter { incMaySee(r, it.id) }.sortedBy { it.name.lowercase() }.map { p ->
            val sheet = data.sheetFor(p.id, month)
            val res = IncentiveSheets.resultOf(cfg, sheet, p.incPerson())
            total += res?.total ?: 0.0
            Triple(p, sheet, res)
        }
        if (incMayAdminister(r)) Kpi(IncentiveTexts.money(total), IncentiveTexts.totalLabel(monthLabel(month)), "ok", Modifier.fillMaxWidth())
        Section("People") {
            rows.forEach { (p, sheet, res) ->
                val kind = IncentiveEngine.categoryLabel(IncentiveSheets.sheetKind(cfg, sheet, p.incPerson()))
                ListRow(p.name, "$kind · " + (if (sheet == null) "no sheet yet" else sheet.str("status")) +
                    (res?.let { " · " + IncentiveTexts.money(it.total) } ?: ""), { nav.navigate("incentiveSheet/${p.id}/$month") }) {
                    if (sheet != null) Chip(sheet.str("status"), if (sheet.str("status") == IncentiveSheets.FINALISED) "ok" else "warn")
                }
            }
        }
    }
}

@Composable
fun IncentiveSheetScreen(vm: AppVm, userId: String, month: String, onBack: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val r = vm.rules(data)
    val person = data.team.find { it.id == userId }
    val cfg = IncentiveConfig.merge(data.incentiveConfig)
    val admin = incMayAdminister(r)
    var confirm by remember { mutableStateOf<String?>(null) }
    var reopenAsk by remember { mutableStateOf(false) }
    Screen((person?.name ?: "") + " — " + monthLabel(month), onBack = onBack) {
        if (person == null || !incMaySee(r, userId)) { Empty("Restricted."); return@Screen }
        val ip = person.incPerson()
        val sheet = data.sheetFor(userId, month)
        Section("Category") {
            Text(IncentiveEngine.categoryLabel(IncentiveSheets.sheetKind(cfg, sheet, ip)), fontWeight = FontWeight.SemiBold)
            Note(IncentiveEngine.categoryWhy(cfg, ip))
            if (IncentiveSheets.sheetKind(cfg, sheet, ip) != IncentiveEngine.SALES && admin)
                Note("To pay on the sales workbook, name sales roles or departments in Incentive grid, or set this person's category in Employees.", "info")
        }
        if (sheet == null) {
            if (admin) Button(onClick = {
                vm.act(showOk = false) { saveSheet(vm, r.currentUserId!!, IncentiveSheets.blankSheet(cfg, ip, month, r.currentUserId!!, vm.service.now(), vm.service.uid("INC")), "Incentive Sheet Opened") }
            }, modifier = Modifier.fillMaxWidth()) { Text("Open the sheet for ${monthLabel(month)}") }
            else Empty("HR has not opened a sheet for this month yet.")
            return@Screen
        }
        val editable = admin && IncentiveSheets.guardEdit(sheet, admin).isEmpty()
        fun apply(e: SheetEdit, action: String = "Incentive Sheet Edited") = when (e) {
            is SheetEdit.Applied -> vm.act(showOk = false) { saveSheet(vm, r.currentUserId!!, e.sheet, action) }
            is SheetEdit.Refused -> vm.say(e.reason)
        }
        val now = vm.service.now()
        Section("Header", trailing = { Chip(sheet.str("status"), if (sheet.str("status") == IncentiveSheets.FINALISED) "ok" else "warn") }) {
            CellField("Annual CTC (₹)", sheet.str("annualCtc"), editable, number = true) { apply(IncentiveSheets.setHeader(sheet, "annualCtc", it, now, admin)) }
            CellField("Branch", sheet.str("branch"), editable) { apply(IncentiveSheets.setHeader(sheet, "branch", it, now, admin)) }
            CellField("Designation", sheet.str("designation"), editable) { apply(IncentiveSheets.setHeader(sheet, "designation", it, now, admin)) }
        }
        val kind = IncentiveSheets.sheetKind(cfg, sheet, ip)
        if (kind == IncentiveEngine.SALES) {
            val prodCats = (cfg["productCredit"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject) }
            val insCats = (cfg["insuranceCredit"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject) }
            val sources = (cfg["sourceCredit"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("label") }
            val meetings = (cfg["meetingCredit"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("label") }
            listOf("netSalesRows" to "Net sales", "sipRows" to "Fresh SIP", "insuranceRows" to "Insurance premium").forEach { (sec, title) ->
                val table = if (sec == "insuranceRows") insCats else prodCats
                val rows = (sheet[sec] as? JsonArray).orEmpty()
                Section(title) {
                    rows.forEachIndexed { i, el ->
                        val row = el as? JsonObject ?: return@forEachIndexed
                        Text("Line ${i + 1}", style = MaterialTheme.typography.labelLarge)
                        val cat = row.str("cat")
                        PickOrShow("Category", cat, table.map { it.str("cat") }.distinct(), editable) { apply(IncentiveSheets.setCell(sheet, sec, i, "cat", it, now, admin)) }
                        PickOrShow("Sub-category", row.str("sub"), table.filter { it.str("cat") == cat }.map { it.str("sub") }.distinct(), editable) {
                            apply(IncentiveSheets.setCell(sheet, sec, i, "sub", it, now, admin)) }
                        PickOrShow("Lead source", row.str("source"), sources, editable) { apply(IncentiveSheets.setCell(sheet, sec, i, "source", it, now, admin)) }
                        PickOrShow("Meeting type", row.str("meeting"), meetings, editable) { apply(IncentiveSheets.setCell(sheet, sec, i, "meeting", it, now, admin)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CellField("Amount (₹)", row.str("amount"), editable, Modifier.weight(1f), number = true) { apply(IncentiveSheets.setCell(sheet, sec, i, "amount", it, now, admin)) }
                            if (editable) IconButton(onClick = { apply(IncentiveSheets.dropRow(sheet, sec, i, now, admin)) }) { Icon(Icons.Default.Delete, "Remove line") }
                        }
                        HorizontalDivider()
                    }
                    if (editable) TextButton(onClick = { apply(IncentiveSheets.addRow(sheet, sec, now, admin)) }) { Text("+ Add line") }
                }
            }
            listOf("meetingRows" to "Meetings", "clientRows" to "New clients", "callRows" to "Call logs").forEach { (sec, title) ->
                val rows = (sheet[sec] as? JsonArray).orEmpty()
                Section(title) {
                    rows.forEachIndexed { i, el ->
                        val row = el as? JsonObject ?: return@forEachIndexed
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            CellField("Label", row.str("label"), editable, Modifier.weight(1.4f)) { apply(IncentiveSheets.setCell(sheet, sec, i, "label", it, now, admin)) }
                            CellField("Count", row.str("n"), editable, Modifier.weight(1f), number = true) { apply(IncentiveSheets.setCell(sheet, sec, i, "n", it, now, admin)) }
                            if (editable) IconButton(onClick = { apply(IncentiveSheets.dropRow(sheet, sec, i, now, admin)) }) { Icon(Icons.Default.Delete, "Remove") }
                        }
                    }
                    if (editable) TextButton(onClick = { apply(IncentiveSheets.addRow(sheet, sec, now, admin)) }) { Text("+ Add line") }
                }
            }
        } else {
            val tp = IncentiveEngine.taskPoints(cfg, sheet, ip)
            Section("Task points") {
                if (tp.rows.isEmpty()) Empty("No task in the bank applies to this person. An administrator adds tasks in Incentive grid.")
                tp.rows.forEach { row ->
                    val t = row.task as? JsonObject ?: return@forEach
                    CellField(t.str("name") + " (${t.str("points")} pts" + (if (t.str("cap") !in listOf("", "0")) ", cap ${t.str("cap")}" else "") + ")",
                        IncentiveTexts.let { _ -> if (row.found == 0.0) "" else row.found.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } },
                        editable, number = true) { apply(IncentiveSheets.setTaskCount(sheet, t.str("id"), it, now, admin)) }
                    Note("Counted ${com.finowise.hr.core.Fmt.num(row.counted)}${if (row.capped) " (capped)" else ""} → ${com.finowise.hr.core.Fmt.num(row.points)} points")
                }
            }
        }

        val res = IncentiveSheets.resultOf(cfg, sheet, ip)
        Section(if (res is IncentiveOutcome.Sales) IncentiveTexts.resultHeader(sheet.str("status") == IncentiveSheets.FINALISED)
                else if (sheet.str("status") == IncentiveSheets.FINALISED) "Result (as finalised)" else "Result (worked out now)") {
            when (res) {
                is IncentiveOutcome.Sales -> {
                    val rr = res.result
                    Note(IncentiveTexts.slabBaseNote(rr))
                    rr.streams.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.label ?: "", fontWeight = FontWeight.SemiBold)
                                Note("Baseline ${IncentiveTexts.figure(s, s.baseline)} (${s.baselineNote}) · credit ${IncentiveTexts.figure(s, s.credit)}")
                                Note(IncentiveTexts.rateText(s) + IncentiveTexts.rateNote(s).let { if (it.isNotBlank()) " · $it" else "" })
                                IncentiveTexts.workingText(s).takeIf { it.isNotBlank() }?.let { Note(it) }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Chip(IncentiveTexts.slabBadge(s), IncentiveTexts.slabTone(s))
                                Text(IncentiveTexts.money(s.incentive), fontWeight = FontWeight.Bold)
                            }
                        }
                        HorizontalDivider()
                    }
                    rr.problems.forEach { p -> Note("Not credited — ${p.section} line ${p.line}: " + p.why.joinToString("; "), "danger") }
                }
                is IncentiveOutcome.NonSales -> {
                    LabelValue("Points", com.finowise.hr.core.Fmt.num(res.taskPoints.points))
                    LabelValue("Rupees per point", res.taskPoints.rate?.let { IncentiveTexts.money(it) } ?: "not set yet")
                }
                null -> {}
            }
            Kpi(IncentiveTexts.money(res?.total ?: 0.0), IncentiveTexts.totalLabel(monthLabel(month)), "ok", Modifier.fillMaxWidth())
        }
        if (admin) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sheet.str("status") != IncentiveSheets.FINALISED) Button(onClick = {
                when (val f = IncentiveSheets.finalise(cfg, sheet, ip, r.currentUserId!!, false, vm.service.now(), admin)) {
                    is FinaliseOutcome.Finalised -> vm.act { saveSheet(vm, r.currentUserId!!, f.sheet, "Incentive Finalised").let { if (it.ok) Outcome.ok("Finalised: ${IncentiveTexts.money(f.outcome.total)}.") else it } }
                    is FinaliseOutcome.NeedsConfirmation -> confirm = f.message
                    is FinaliseOutcome.Refused -> vm.say(f.reason)
                }
            }, modifier = Modifier.weight(1f)) { Text("Finalise") }
            else OutlinedButton(onClick = { reopenAsk = true }, modifier = Modifier.weight(1f)) { Text("Reopen") }
        }
        if (sheet.str("status") == IncentiveSheets.FINALISED) Note("Finalised ${com.finowise.hr.core.Fmt.date(sheet.str("finalisedAt"))} by ${r.name(sheet.str("finalisedBy"))}. A rate changed later restates nothing here.")
        confirm?.let { msg ->
            AlertDialog(onDismissRequest = { confirm = null }, text = { Text(msg) },
                confirmButton = { TextButton(onClick = {
                    confirm = null
                    val f = IncentiveSheets.finalise(cfg, sheet, ip, r.currentUserId!!, true, vm.service.now(), admin)
                    if (f is FinaliseOutcome.Finalised) vm.act { saveSheet(vm, r.currentUserId!!, f.sheet, "Incentive Finalised") }
                    else if (f is FinaliseOutcome.Refused) vm.say(f.reason)
                }) { Text("Finalise anyway") } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
        }
        if (reopenAsk) AlertDialog(onDismissRequest = { reopenAsk = false }, text = { Text(IncentiveSheets.reopenPrompt(sheet, person.name)) },
            confirmButton = { TextButton(onClick = { reopenAsk = false; apply(IncentiveSheets.reopen(sheet, r.currentUserId!!, vm.service.now(), admin), "Incentive Reopened") }) { Text("Reopen") } },
            dismissButton = { TextButton(onClick = { reopenAsk = false }) { Text("Cancel") } })
    }
}

/** A text cell that saves when focus leaves it (or on Done). */
@Composable
fun CellField(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), number: Boolean = false, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(text, { text = it }, label = { Text(label) }, enabled = enabled, singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (number) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (text != value) onSave(text) }),
        modifier = modifier.onFocusChanged { st -> val f = st.isFocused; if (focused && !f && text != value) onSave(text); focused = f })
}


@Composable
private fun PickOrShow(label: String, value: String, options: List<String>, enabled: Boolean, onPick: (String) -> Unit) {
    if (enabled) Dropdown(label, value, options.map { it to it.ifBlank { "—" } }, onPick, Modifier.fillMaxWidth())
    else LabelValue(label, value.ifBlank { "—" })
}

@Composable
fun IncentiveSettingsScreen(vm: AppVm, onBack: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val r = vm.rules(data)
    val saved = data.incentiveConfig
    val cfg = IncentiveConfig.merge(saved)
    fun put(next: JsonObject, what: String) = vm.act(showOk = false) { vm.service.updateModules(r.currentUserId!!, what, "incentiveConfig") { it.copy(incentiveConfig = next) } }
    var taskName by remember { mutableStateOf("") }
    var taskPts by remember { mutableStateOf("") }
    var taskCap by remember { mutableStateOf("") }
    var taskRoles by remember { mutableStateOf("") }
    Screen("Incentive grid", onBack = onBack) {
        if (!incMayAdminister(r)) { Empty("For HR administrators."); return@Screen }
        Note("Every number is a cell of ${IncentiveConfig.WORKBOOK}. Changing one here changes the grid for months not yet finalised.")
        Section("Basis") {
            SwitchRow("Slab bands on each person's own CTC", cfg.str("slabBase") == "individual", { put(IncentiveConfig.setSlabBase(saved, it), "Incentive Slab Base Changed") },
                "Off: bands are built from the grid's CTC below (the workbook's way).")
            CellField("Grid CTC (₹)", cfg.str("slabCtc"), true, number = true) { put(IncentiveConfig.setSlabCtc(saved, it), "Incentive Slab CTC Changed") }
            CellField("Rupees per task point (non-sales)", cfg.str("pointValue").takeIf { it != "null" } ?: "", true, number = true) { put(IncentiveConfig.setPointValue(saved, it), "Incentive Point Value Changed") }
            CellField("Sales roles (comma separated)", (cfg["salesRoles"] as? JsonArray).orEmpty().joinToString(", ") { it.jsonPrimitive.content }, true) { put(IncentiveConfig.setSalesRoles(saved, it), "Incentive Sales Roles Changed") }
            CellField("Sales departments (comma separated)", (cfg["salesDepartments"] as? JsonArray).orEmpty().joinToString(", ") { it.jsonPrimitive.content }, true) { put(IncentiveConfig.setSalesDepartments(saved, it), "Incentive Sales Departments Changed") }
        }
        listOf("productCredit" to "Product factors", "insuranceCredit" to "Insurance factors", "sourceCredit" to "Lead source factors", "meetingCredit" to "Meeting type factors").forEach { (table, title) ->
            Section(title) {
                (cfg[table] as? JsonArray).orEmpty().forEachIndexed { i, el ->
                    val o = el as? JsonObject ?: return@forEachIndexed
                    val name = listOf(o.str("cat"), o.str("sub"), o.str("label")).filter { it.isNotBlank() }.joinToString(" · ")
                    CellField(name, o.str("factor"), true, number = true) { put(IncentiveConfig.setTableCell(saved, table, i, "factor", it), "Incentive Factor Changed") }
                }
            }
        }
        (cfg["streams"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { s ->
            Section(s.str("label") + " — slabs") {
                Note("Baseline: " + ((s["baseline"] as? JsonObject)?.str("note") ?: ""))
                (s["bands"] as? JsonArray).orEmpty().forEachIndexed { i, b ->
                    val o = b as? JsonObject ?: return@forEachIndexed
                    Text(o.str("slab"), fontWeight = FontWeight.SemiBold)
                    Note(IncentiveTexts.edgeText(o["from"], IncentiveTexts.gridBasis(cfg)) + " to " + IncentiveTexts.edgeText(o["to"], IncentiveTexts.gridBasis(cfg)))
                    CellField("Rate", o.str("rate"), true, number = true) { put(IncentiveConfig.setBand(saved, s.str("key"), i, "rate", it), "Incentive Rate Changed") }
                }
            }
        }
        Section("Task bank (non-sales)") {
            (cfg["tasks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { t ->
                ListRow(t.str("name"), "${t.str("points")} points" + (if (t.str("cap") !in listOf("", "0")) " · cap ${t.str("cap")}" else "") +
                    (t["roles"] as? JsonArray).orEmpty().joinToString(", ") { it.jsonPrimitive.content }.let { if (it.isNotBlank()) " · $it" else "" }) {
                    IconButton(onClick = { put(IncentiveConfig.removeTask(saved, t.str("id")), "Incentive Task Removed") }) { Icon(Icons.Default.Delete, "Remove") }
                }
            }
            Field("Task name", taskName, { taskName = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Points", taskPts, { taskPts = it }, Modifier.weight(1f), number = true)
                Field("Cap (0 = none)", taskCap, { taskCap = it }, Modifier.weight(1f), number = true)
            }
            Field("Roles (comma separated, blank = all)", taskRoles, { taskRoles = it })
            OutlinedButton(onClick = {
                val pts = taskPts.toDoubleOrNull()
                if (taskName.isBlank() || pts == null) vm.say("Give the task a name and its points.")
                else { put(IncentiveConfig.saveTask(saved, null, taskName, roles = taskRoles.split(","), points = pts, cap = taskCap.toDoubleOrNull() ?: 0.0, newId = { vm.service.uid("ITASK") }), "Incentive Task Added")
                    taskName = ""; taskPts = ""; taskCap = ""; taskRoles = "" }
            }) { Text("+ Add task") }
        }
        val queries = IncentiveConfig.QUERIES
        val open = IncentiveConfig.openQueries(saved).map { it.id }.toSet()
        Section("Questions about the workbook") {
            queries.forEach { q ->
                Text(q.title, fontWeight = FontWeight.SemiBold)
                Note(q.detail)
                if (q.id in open) TextButton(onClick = { put(IncentiveConfig.settleQuery(saved, q.id, vm.service.now().toString()), "Incentive Query Noted") }) { Text("Mark as noted") }
                else Note("Noted.", "ok")
                HorizontalDivider()
            }
        }
    }
}
