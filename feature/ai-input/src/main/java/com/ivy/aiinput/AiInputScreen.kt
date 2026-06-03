package com.ivy.aiinput

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ivy.navigation.AiSettingsScreen
import com.ivy.navigation.navigation
import com.ivy.navigation.screenScopedViewModel

@Composable
fun AiInputScreenImpl(
    viewModel: AiInputViewModel = screenScopedViewModel(),
) {
    AiInputUi(
        uiState = viewModel.uiState(),
        onEvent = viewModel::onEvent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiInputUi(
    uiState: AiInputState,
    onEvent: (AiInputEvent) -> Unit,
) {
    val nav = navigation()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Добавить через AI") },
                navigationIcon = {
                    IconButton(onClick = { nav.back() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InputCard(uiState = uiState, onEvent = onEvent)

            uiState.error?.let { err ->
                ErrorCard(
                    error = err,
                    canRetry = uiState.inputText.isNotBlank() && uiState.phase != Phase.Parsing,
                    onRetry = { onEvent(AiInputEvent.Parse) },
                    onOpenAiSettings = { nav.navigateTo(AiSettingsScreen) },
                )
            }
        }
    }
}

@Composable
private fun InputCard(
    uiState: AiInputState,
    onEvent: (AiInputEvent) -> Unit,
) {
    val context = LocalContext.current
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { onEvent(AiInputEvent.InputChanged(it)) }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Опишите транзакцию",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Введите текстом или надиктуйте микрофоном — например: " +
                    "«вчера 1500 продукты Пятёрочка» или «зарплата 80000».",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "После распознавания откроется форма транзакции — там можно всё проверить и сохранить.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = uiState.inputText,
                onValueChange = { onEvent(AiInputEvent.InputChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Текст") },
                minLines = 2,
                maxLines = 5,
                enabled = uiState.phase != Phase.Parsing,
                trailingIcon = {
                    IconButton(
                        onClick = { startVoiceInput(context) { voiceLauncher.launch(it) } },
                        enabled = uiState.phase != Phase.Parsing,
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = "Голосовой ввод")
                    }
                },
            )
            Spacer(Modifier.height(4.dp))

            val canParse = uiState.inputText.isNotBlank() &&
                uiState.phase != Phase.Parsing &&
                !uiState.loadingContext
            Button(
                onClick = { onEvent(AiInputEvent.Parse) },
                modifier = Modifier.fillMaxWidth(),
                enabled = canParse,
            ) {
                if (uiState.phase == Phase.Parsing) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("  Распознаю…")
                } else {
                    Text("Распознать")
                }
            }
            if (uiState.loadingContext) {
                Text(
                    text = "Загружаю список ваших категорий и счетов…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Launches the system speech recognizer (the familiar mic dialog). The recognized text is
 * delivered back to the [voiceLauncher] callback and dropped into the input field — from there
 * the normal "Распознать" flow takes over. No RECORD_AUDIO permission is needed: the recognizer
 * app owns the microphone.
 */
private fun startVoiceInput(context: Context, launch: (Intent) -> Unit) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Назовите транзакцию")
    }
    try {
        launch(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "Голосовой ввод недоступен на устройстве",
            Toast.LENGTH_SHORT,
        ).show()
    }
}

@Composable
private fun ErrorCard(
    error: AiError,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val message = when (error) {
        AiError.NoAccounts ->
            "Сначала создайте хотя бы один счёт — к нему будет привязана транзакция."
        AiError.MissingApiKey ->
            "Не настроен ключ Gemini API. Откройте «Настройки → AI» и добавьте ключ."
        AiError.Network ->
            "Не удалось связаться с Gemini. Проверьте интернет-соединение и попробуйте снова."
        is AiError.Failed -> error.message
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Не получилось",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(text = message, style = MaterialTheme.typography.bodyMedium)

            when (error) {
                AiError.MissingApiKey -> Button(
                    onClick = onOpenAiSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Открыть настройки AI") }

                AiError.Network, is AiError.Failed -> if (canRetry) {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Повторить") }
                }

                AiError.NoAccounts -> Unit
            }
        }
    }
}
