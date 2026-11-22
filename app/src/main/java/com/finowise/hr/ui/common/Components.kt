package com.finowise.hr.ui.common

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.finowise.hr.core.Fmt
import com.finowise.hr.ui.theme.toneColor
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {},
           fab: @Composable () -> Unit = {}, scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary))
        },
        floatingActionButton = fab,
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().let { if (scroll) it.verticalScroll(rememberScrollState()) else it }.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun Section(title: String? = null, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                trailing()
            }
            content()
        }
    }
}

@Composable
fun Kpi(value: String, label: String, tone: String = "", modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(10.dp)) {
            Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (tone.isEmpty()) MaterialTheme.colorScheme.onSurface else toneColor(tone),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** KPI tiles, two or three a row. */
@Composable
fun KpiGrid(items: List<Triple<String, String, String>>, perRow: Int = 2) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { Kpi(it.first, it.second, it.third, Modifier.weight(1f)) }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun Chip(text: String, tone: String) {
    val c = toneColor(tone)
    Text(text, color = c, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.background(c.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp))
}

fun statusTone(s: String) = when (s) {
    "Approved", "Allocated" -> "ok"; "Pending" -> "warn"; "Declined", "Rejected" -> "danger"; else -> "muted"
}

@Composable
fun Note(text: String, tone: String = "") =
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (tone.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else toneColor(tone))

@Composable
fun Empty(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))

@Composable
fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(0.42f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.58f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun MessageDialog(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        text = { Text(message, modifier = Modifier.verticalScroll(rememberScrollState())) })
}

/** Approve / decline with remarks; a decline must say why. */
@Composable
fun DecisionDialog(title: String, approving: Boolean, details: @Composable ColumnScope.() -> Unit = {},
                   onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var note by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                details()
                OutlinedTextField(note, { note = it }, label = { Text(if (approving) "Remarks (optional)" else "Reason (required)") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                if (!approving) Note("They are told no, and they are owed the why — it goes to them with the decision.")
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(note) }, enabled = approving || note.isNotBlank(),
                colors = if (approving) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text(if (approving) "Approve" else "Decline")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun DateField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val ctx = LocalContext.current
    val open = {
        val d = runCatching { LocalDate.parse(value) }.getOrElse { LocalDate.now() }
        DatePickerDialog(ctx, { _, y, m, day -> onChange(LocalDate.of(y, m + 1, day).toString()) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }
    PickerBox(label, if (value.isBlank()) "" else Fmt.date(value), Icons.Default.CalendarMonth, enabled, modifier, open)
}

@Composable
fun TimeField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val open = {
        val p = value.split(":").mapNotNull { it.toIntOrNull() }
        TimePickerDialog(ctx, { _, h, m -> onChange("%02d:%02d".format(h, m)) }, p.getOrElse(0) { 9 }, p.getOrElse(1) { 0 }, true).show()
    }
    PickerBox(label, value, Icons.Default.Schedule, true, modifier, open)
}

@Composable
private fun PickerBox(label: String, shown: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, modifier: Modifier, open: () -> Unit) {
    Box(modifier) {
        OutlinedTextField(shown, {}, readOnly = true, enabled = enabled, label = { Text(label) }, trailingIcon = { Icon(icon, null) },
            modifier = Modifier.fillMaxWidth())
        Box(Modifier.matchParentSize().clickable(enabled = enabled) { open() })
    }
}

/** A pick-one field. Options are (value, label). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Dropdown(label: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }, modifier) {
        OutlinedTextField(options.find { it.first == value }?.second ?: value, {}, readOnly = true, label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(open, { open = false }) {
            options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onChange(v); open = false }) }
        }
    }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(),
          number: Boolean = false, password: Boolean = false, minLines: Int = 1, supporting: String? = null) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = modifier, minLines = minLines, singleLine = minLines == 1,
        keyboardOptions = KeyboardOptions(keyboardType = when { number -> KeyboardType.Decimal; password -> KeyboardType.Password; else -> KeyboardType.Text }),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        supportingText = supporting?.let { { Text(it) } })
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, note: String? = null) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (note != null) Note(note)
        }
        Switch(checked, onChange)
    }
}

@Composable
fun ListRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable { onClick() } else it }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
    HorizontalDivider()
}
