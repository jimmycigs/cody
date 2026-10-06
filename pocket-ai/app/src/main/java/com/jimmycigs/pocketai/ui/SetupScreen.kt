package com.jimmycigs.pocketai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

const val MODEL_PAGE = "https://huggingface.co/litert-community/Gemma3-1B-IT"

@Composable
fun SetupScreen(
    status: ModelStatus,
    canGoBack: Boolean,
    onPickModel: (android.net.Uri) -> Unit,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPickModel(uri)
    }
    val working = status is ModelStatus.Importing || status == ModelStatus.Loading || status == ModelStatus.Checking

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Set up your offline AI", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pocket AI needs a model file: the \"brain\" that runs on your phone. You only do this once. " +
                "After that, everything works with no internet at all.",
        )
        Text("1. Open the model page in your browser, sign in to Hugging Face and accept Google's Gemma license.")
        TextButton(onClick = { uriHandler.openUri(MODEL_PAGE) }) { Text("Open Gemma 3 1B model page") }
        Text("2. In \"Files and versions\", download gemma3-1b-it-int4.task (about 550 MB).")
        Text("3. Tap below and pick that file from your Downloads folder.")

        when (status) {
            is ModelStatus.Importing -> {
                Text("Installing model… ${(status.progress * 100).toInt()}%")
                LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
            }
            ModelStatus.Loading, ModelStatus.Checking -> {
                Text("Loading the model…")
                CircularProgressIndicator()
            }
            is ModelStatus.Failed -> Text(
                "Problem: ${status.message}",
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }

        Button(
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Choose model file (.task)") }

        if (canGoBack && !working) {
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to chat") }
        }

        Text(
            "Tip: once installed, you can delete the downloaded file from Downloads; Pocket AI keeps its own copy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
