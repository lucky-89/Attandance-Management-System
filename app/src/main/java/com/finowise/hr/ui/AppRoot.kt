package com.finowise.hr.ui

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.finowise.hr.AppVm
import com.finowise.hr.ui.admin.*
import com.finowise.hr.ui.appraisal.AppraisalEditScreen
import com.finowise.hr.ui.appraisal.AppraisalScreen
import com.finowise.hr.ui.appraisal.AppraisalSettingsScreen
import com.finowise.hr.ui.attendance.DayDetailScreen
import com.finowise.hr.ui.attendance.RegularizeScreen
import com.finowise.hr.ui.auth.LoginScreen
import com.finowise.hr.ui.auth.SetupScreen
import com.finowise.hr.ui.common.MessageDialog
import com.finowise.hr.ui.home.HomeScreen
import com.finowise.hr.ui.incentive.IncentiveScreen
import com.finowise.hr.ui.incentive.IncentiveSettingsScreen
import com.finowise.hr.ui.incentive.IncentiveSheetScreen
import com.finowise.hr.ui.leave.LeaveDetailScreen
import com.finowise.hr.ui.leave.LeaveRequestScreen
import com.finowise.hr.ui.more.*
import com.finowise.hr.ui.team.*

@Composable
fun AppRoot(vm: AppVm) {
    val data by vm.data.collectAsStateWithLifecycle()
    val userId by vm.userId.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    MessageDialog(message) { vm.say(null) }

    when {
        !vm.service.isSetUp -> SetupScreen(vm)
        userId == null || data.team.none { it.id == userId && it.isActive } -> LoginScreen(vm)
        else -> key(userId) { SignedIn(vm) }
    }
}

@Composable
private fun SignedIn(vm: AppVm) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen(vm, nav) }
        composable("day/{userId}/{date}") { e -> DayDetailScreen(vm, nav, e.arg("userId"), e.arg("date")) }
        composable("regularize/{date}") { e -> RegularizeScreen(vm, e.arg("date"), back) }
        composable("leaveRequest?date={date}") { e -> LeaveRequestScreen(vm, e.arguments?.getString("date")?.takeIf { it.isNotBlank() }, back) }
        composable("leave/{id}") { e -> LeaveDetailScreen(vm, e.arg("id"), back) }
        composable("monthBoard") { MonthBoardScreen(vm, nav) }
        composable("today/{kind}") { e -> TeamTodayScreen(vm, nav, e.arg("kind")) }
        composable("person/{userId}") { e -> PersonHistoryScreen(vm, nav, e.arg("userId")) }
        composable("balances") { LeaveBalancesScreen(vm, nav) }
        composable("ledger/{userId}/{year}") { e -> LedgerScreen(vm, e.arg("userId"), e.arg("year"), back) }
        composable("settings") { HrSettingsScreen(vm, back) }
        composable("employees") { EmployeesScreen(vm, nav) }
        composable("employee/{id}") { e -> EmployeeEditScreen(vm, e.arg("id"), back) }
        composable("notifications") { NotificationsScreen(vm, back) }
        composable("audit") { AuditScreen(vm, back) }
        composable("backup") { BackupScreen(vm, back) }
        composable("password") { PasswordScreen(vm, back) }
        composable("incentive") { IncentiveScreen(vm, nav) }
        composable("incentiveSheet/{userId}/{month}") { e -> IncentiveSheetScreen(vm, e.arg("userId"), e.arg("month"), back) }
        composable("incentiveSettings") { IncentiveSettingsScreen(vm, back) }
        composable("appraisal") { AppraisalScreen(vm, nav) }
        composable("appraisalEdit/{userId}/{year}") { e -> AppraisalEditScreen(vm, e.arg("userId"), e.arg("year"), back) }
        composable("appraisalSettings") { AppraisalSettingsScreen(vm, back) }
    }
}

private fun androidx.navigation.NavBackStackEntry.arg(name: String) = arguments?.getString(name) ?: ""
@Suppress("unused") private fun NavHostController.noop() = Unit
