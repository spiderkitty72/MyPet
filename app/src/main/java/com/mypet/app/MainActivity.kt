package com.mypet.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mypet.app.ui.CollectionScreen
import com.mypet.app.ui.DeviceScreen
import com.mypet.app.ui.SettingsDialog
import com.mypet.app.ui.StartScreen

class MainActivity : ComponentActivity() {
    private val vm: PetViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PetAlarms.createChannel(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFE9B949),
                    surface = Color(0xFF2F2D38),
                    background = Color(0xFF26252D),
                ),
            ) {
                val state by vm.state.collectAsStateWithLifecycle()
                var showSettings by remember { mutableStateOf(false) }

                val askNotifications = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }
                LaunchedEffect(state.screen) {
                    if (state.screen == Screen.DEVICE && Build.VERSION.SDK_INT >= 33) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                BackHandler(enabled = state.screen == Screen.COLLECTION) {
                    vm.show(if (state.pet != null) Screen.DEVICE else Screen.START)
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF26252D))
                        .safeDrawingPadding(),
                ) {
                    when (state.screen) {
                        Screen.START -> StartScreen(state, vm)
                        Screen.DEVICE -> DeviceScreen(state, vm, onSettings = { showSettings = true })
                        Screen.COLLECTION -> CollectionScreen(state, vm)
                    }
                }
                if (showSettings) SettingsDialog(state, vm, onDismiss = { showSettings = false })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        PetAlarms.inForeground = true
        PetAlarms.clearNotification(this)
        vm.onForeground()
    }

    override fun onStop() {
        PetAlarms.inForeground = false
        vm.onBackground()
        super.onStop()
    }
}
