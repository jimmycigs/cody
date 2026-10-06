package com.jimmycigs.pocketai

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jimmycigs.pocketai.ui.ChatScreen
import com.jimmycigs.pocketai.ui.MainViewModel
import com.jimmycigs.pocketai.ui.MemoryScreen
import com.jimmycigs.pocketai.ui.ModelStatus
import com.jimmycigs.pocketai.ui.Screen
import com.jimmycigs.pocketai.ui.SetupScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PocketTheme {
                Surface { PocketApp() }
            }
        }
    }
}

@Composable
private fun PocketApp(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            vm.clearNotice()
        }
    }

    val modelUsable = state.model == ModelStatus.Ready
    BackHandler(enabled = state.screen == Screen.MEMORY || (state.screen == Screen.SETUP && modelUsable)) {
        vm.show(Screen.CHAT)
    }

    when (state.screen) {
        Screen.SETUP -> SetupScreen(
            status = state.model,
            canGoBack = modelUsable,
            onPickModel = vm::importModel,
            onBack = { vm.show(Screen.CHAT) },
        )
        Screen.MEMORY -> MemoryScreen(
            state = state,
            snackbar = snackbar,
            onBack = { vm.show(Screen.CHAT) },
            onAutoLearnChange = vm::setAutoLearn,
            onAdd = vm::addMemory,
            onEdit = vm::editMemory,
            onDelete = vm::deleteMemory,
            onForgetEverything = vm::forgetEverything,
            onExport = vm::exportMemories,
            onImport = vm::importMemories,
        )
        Screen.CHAT -> ChatScreen(
            state = state,
            snackbar = snackbar,
            onSend = vm::send,
            onOpenMemory = { vm.show(Screen.MEMORY) },
            onNewChat = vm::newChat,
            onChangeModel = { vm.show(Screen.SETUP) },
        )
    }
}

@Composable
private fun PocketTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
