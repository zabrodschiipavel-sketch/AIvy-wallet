package com.ivy.settings.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ivy.navigation.navigation
import com.ivy.navigation.screenScopedViewModel

private const val ConnectionSuccessArgb = 0xFF2E7D32

@Composable
fun AiSettingsScreenImpl(
    viewModel: AiSettingsViewModel = screenScopedViewModel(),
) {
    AiSettingsUi(
        uiState = viewModel.uiState(),
        onEvent = viewModel::onEvent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiSettingsUi(
    uiState: AiSettingsState,
    onEvent: (AiSettingsEvent) -> Unit,
) {
    val nav = navigation()
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("AI · Gemini") },
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
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(horizontal = 16.dp, vertical = 16.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ApiKeyCard(uiState = uiState, onEvent = onEvent)
            HowToGetKeyCard()
        }
    }
}

@Composable
private fun ApiKeyCard(
    uiState: AiSettingsState,
    onEvent: (AiSettingsEvent) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "API-ключ Gemini",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Хранится локально, зашифрован (AES-256). С устройства не уходит, кроме вызовов Gemini.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = uiState.apiKeyInput,
                onValueChange = { onEvent(AiSettingsEvent.ApiKeyChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ключ") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (uiState.hasSavedKey) {
                Text(
                    text = "Ключ сохранён.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = { onEvent(AiSettingsEvent.SaveKey) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Сохранить")
            }
            OutlinedButton(
                onClick = { onEvent(AiSettingsEvent.TestConnection) },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.testStatus !is TestStatus.Running,
            ) {
                if (uiState.testStatus is TestStatus.Running) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.height(0.dp))
                    Text("  Проверяю…")
                } else {
                    Text("Проверить связь")
                }
            }
            if (uiState.hasSavedKey) {
                OutlinedButton(
                    onClick = { onEvent(AiSettingsEvent.ClearKey) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Удалить ключ")
                }
            }

            TestStatusLine(status = uiState.testStatus)
        }
    }
}

@Composable
private fun TestStatusLine(status: TestStatus) {
    when (status) {
        TestStatus.Idle -> Unit
        TestStatus.Running -> Unit
        TestStatus.Success -> Text(
            text = "✓ Связь с Gemini работает.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(ConnectionSuccessArgb),
        )
        is TestStatus.Failure -> Text(
            text = "✗ ${status.message}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun HowToGetKeyCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Как получить ключ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "1. Откройте aistudio.google.com/apikey\n" +
                    "2. Войдите в аккаунт Google\n" +
                    "3. Нажмите «Create API key»\n" +
                    "4. Скопируйте ключ сюда\n\n" +
                    "Бесплатный лимит у Gemini Flash Lite более чем достаточен для личного использования.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
