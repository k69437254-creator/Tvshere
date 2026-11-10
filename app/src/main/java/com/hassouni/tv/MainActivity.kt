package com.hassouni.tv

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.hassouni.tv.ui.AppRoot
import com.hassouni.tv.ui.MainViewModel
import com.hassouni.tv.ui.HassouniTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val requestCamera = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                vm.setCameraPermission(granted)
            }

            LaunchedEffect(Unit) {
                if (hasCameraPermission()) vm.setCameraPermission(true)
                else requestCamera.launch(Manifest.permission.CAMERA)
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                HassouniTheme { AppRoot(vm) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (hasCameraPermission()) vm.setCameraPermission(true)
    }

    override fun onStart() {
        super.onStart()
        if (hasCameraPermission()) vm.setCameraPermission(true)
    }
}
