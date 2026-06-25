package com.notimirror

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.notimirror.ui.DashboardScreen
import com.notimirror.ui.DebugScreen
import com.notimirror.ui.PairingScreen
import com.notimirror.ui.PermissionsScreen
import com.notimirror.ui.SettingsScreen
import com.notimirror.ui.theme.NotiMirrorTheme
import com.notimirror.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val app get() = application as NotiMirrorApp
    private val vm: MainViewModel by viewModels { MainViewModel.Factory(app.container) }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            NotiMirrorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val keepAwake by vm.keepScreenAwake.collectAsState()
                    LaunchedEffect(keepAwake) {
                        if (keepAwake) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }

                    val requiredPermissions = buildList {
                        add(Manifest.permission.BLUETOOTH_SCAN)
                        add(Manifest.permission.BLUETOOTH_CONNECT)
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    val permState = rememberMultiplePermissionsState(requiredPermissions)

                    if (!permState.allPermissionsGranted) {
                        PermissionsScreen(permState)
                    } else {
                        AppNavigation(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppNavigation(vm: MainViewModel) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "dashboard") {
        composable("dashboard") {
            DashboardScreen(
                vm = vm,
                onNavigatePairing = { navController.navigate("pairing") },
                onNavigateDebug = { navController.navigate("debug") },
                onNavigateSettings = { navController.navigate("settings") }
            )
        }
        composable("pairing") {
            PairingScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable("debug") {
            DebugScreen(vm = vm, onBack = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(vm = vm, onBack = { navController.popBackStack() })
        }
    }
}
