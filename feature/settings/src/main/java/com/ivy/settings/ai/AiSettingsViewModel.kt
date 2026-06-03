package com.ivy.settings.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.ai.data.GeminiApiKeyStore
import com.ivy.ai.data.GeminiClient
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val keyStore: GeminiApiKeyStore,
    private val geminiClient: GeminiClient,
) : ComposeViewModel<AiSettingsState, AiSettingsEvent>() {

    private var apiKeyInput by mutableStateOf("")
    private var hasSavedKey by mutableStateOf(false)
    private var testStatus by mutableStateOf<TestStatus>(TestStatus.Idle)

    @Composable
    override fun uiState(): AiSettingsState {
        LaunchedEffect(Unit) {
            // Pre-fill the field with the existing key so the user can see / edit it.
            // For a more secure flavor we could leave it blank and only show "•••• set".
            apiKeyInput = keyStore.get().orEmpty()
            hasSavedKey = keyStore.isConfigured()
        }
        return AiSettingsState(
            apiKeyInput = apiKeyInput,
            hasSavedKey = hasSavedKey,
            testStatus = testStatus,
        )
    }

    override fun onEvent(event: AiSettingsEvent) {
        when (event) {
            is AiSettingsEvent.ApiKeyChanged -> {
                apiKeyInput = event.value
                // Mutation invalidates any previous test result.
                testStatus = TestStatus.Idle
            }
            AiSettingsEvent.SaveKey -> {
                val trimmed = apiKeyInput.trim()
                if (trimmed.isEmpty()) {
                    keyStore.clear()
                    hasSavedKey = false
                } else {
                    keyStore.set(trimmed)
                    hasSavedKey = true
                }
            }
            AiSettingsEvent.ClearKey -> {
                keyStore.clear()
                apiKeyInput = ""
                hasSavedKey = false
                testStatus = TestStatus.Idle
            }
            AiSettingsEvent.TestConnection -> testConnection()
        }
    }

    /**
     * Pings Gemini with a trivial structured prompt to verify the key works.
     * Saves the key first if the input differs from what's stored.
     */
    private fun testConnection() {
        val trimmed = apiKeyInput.trim()
        if (trimmed.isEmpty()) {
            testStatus = TestStatus.Failure("Введите ключ перед тестом")
            return
        }
        // Ensure GeminiClient sees the latest input — persist before pinging.
        keyStore.set(trimmed)
        hasSavedKey = true

        testStatus = TestStatus.Running
        viewModelScope.launch {
            val result = runCatching { geminiClient.ping() }
            testStatus = result.fold(
                onSuccess = { TestStatus.Success },
                onFailure = { TestStatus.Failure(it.message ?: "Неизвестная ошибка") },
            )
        }
    }
}
