package com.finowise.hr.ui.approvals

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.finowise.hr.AppVm
import com.finowise.hr.core.*
import com.finowise.hr.ui.common.*

private sealed interface Pending {
    data class Leave(val id: String, val yes: Boolean) : Pending
    data class Comp(val id: String, val yes: Boolean) : Pending
    data class Reg(val id: String, val yes: Boolean) : Pending
}

@Composable
fun ApprovalsTab(vm: AppVm, r: HrRules) {
    val me = r.currentUserId ?: return
    var open by remember { mutableStateOf<Pending?>(null) }
    val leave = r.leavePendingForMe(); val comp = r.compOffPendingForMe(); val regs = r.regPendingForMe()
    if (leave.isEmpty() && comp.isEmpty() && regs.isEmpty())
        Empty("Nothing is waiting for you. Leave requests come to the branch head or reporting manager; regularizations and comp-offs to HR.")

    if (leave.isNotEmpty()) Section("Leave requests (${leave.size})") {
        leave.forEach { l ->
            Text(r.name(l.userId), fontWeight = FontWeight.SemiBold)
            Note(Fmt.date(l.from) + (if (l.to != l.from) " – " + Fmt.date(l.to) else "") + " · " + Fmt.daysLabel(r.leaveDays(l)) + " · " +
                LEAVE_CATEGORY_LABEL[r.categoryOf(l)] + " · " + l.type + (if (l.session.isNotBlank()) " (${l.session})" else ""))
            if (l.reason.isNotBlank()) Note("“${l.reason}”")
            r.balanceProblem(l, true).takeIf { it.isNotEmpty() }?.let { Note(it, "danger") }
            Buttons({ open = Pending.Leave(l.id, true) }, { open = Pending.Leave(l.id, false) })
            HorizontalDivider()
        }
    }
    if (comp.isNotEmpty()) Section("Comp-off to verify (${comp.size})") {
        comp.forEach { c ->
            Text(r.name(c.userId), fontWeight = FontWeight.SemiBold)
            Note(Fmt.date(c.date) + " · " + c.kind + " · " + Fmt.duration(c.minutes.toDouble()) + " worked → " + Fmt.daysLabel(c.credit))
            val rec = r.recordOf(c.userId, c.date)
            Note("Punched " + r.clock(r.firstIn(rec)) + " – " + r.clock(r.lastOut(rec)))
            Buttons({ open = Pending.Comp(c.id, true) }, { open = Pending.Comp(c.id, false) }, "Reject")
            HorizontalDivider()
        }
    }
    if (regs.isNotEmpty()) Section("Regularizations (${regs.size})") {
        regs.forEach { g ->
            Text(r.name(g.userId) + " · " + Fmt.date(g.date), fontWeight = FontWeight.SemiBold)
            Note("Recorded " + r.clock(g.recorded?.`in`) + " – " + r.clock(g.recorded?.out) + " (" + Fmt.num(g.recorded?.pct ?: 0.0) + "%)")
            Note("Asks for " + g.periods.joinToString(", ") { it.from + "–" + it.to } + " · adds " + Fmt.duration(g.addsMinutes.toDouble()))
            Note("“${g.reason}”")
            Buttons({ open = Pending.Reg(g.id, true) }, { open = Pending.Reg(g.id, false) }, "Reject")
            HorizontalDivider()
        }
    }

    when (val p = open) {
        is Pending.Leave -> DecisionDialog(if (p.yes) "Approve leave" else "Decline leave", p.yes, onDismiss = { open = null }) { n ->
            open = null; vm.act { vm.service.decideLeave(me, p.id, p.yes, n) } }
        is Pending.Comp -> DecisionDialog(if (p.yes) "Approve comp-off" else "Reject comp-off", p.yes, onDismiss = { open = null }) { n ->
            open = null; vm.act { vm.service.decideCompOff(me, p.id, p.yes, n) } }
        is Pending.Reg -> DecisionDialog(if (p.yes) "Approve regularization" else "Reject regularization", p.yes, onDismiss = { open = null }) { n ->
            open = null; vm.act { vm.service.decideRegularization(me, p.id, p.yes, n) } }
        null -> {}
    }
}

@Composable
private fun Buttons(approve: () -> Unit, decline: () -> Unit, no: String = "Decline") {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = approve, Modifier.weight(1f)) { Text("Approve") }
        OutlinedButton(onClick = decline, Modifier.weight(1f)) { Text(no) }
    }
}
