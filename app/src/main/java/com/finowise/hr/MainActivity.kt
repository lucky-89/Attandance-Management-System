package com.finowise.hr

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.finowise.hr.ui.AppRoot
import com.finowise.hr.ui.theme.FinowiseTheme

class MainActivity : ComponentActivity() {
    private val vm: AppVm by viewModels()
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { FinowiseTheme { AppRoot(vm) } }
    }

    override fun onResume() { super.onResume(); vm.refreshClock() }
}
