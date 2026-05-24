package com.ivy.settings.ai

data class AiSettingsState(
    /** Current key as the user is editing it. */
    val apiKeyInput: String,
    /** True if a non-empty key is persisted. Independent of the current input. */
    val hasSavedKey: Boolean,
    /** Latest result of the "Test connection" button. */
    val testStatus: TestStatus,
)

sealed interface TestStatus {
    data object Idle : TestStatus
    data object Running : TestStatus
    data object Success : TestStatus
    data class Failure(val message: String) : TestStatus
}

sealed interface AiSettingsEvent {
    data class ApiKeyChanged(val value: String) : AiSettingsEvent
    data object SaveKey : AiSettingsEvent
    data object ClearKey : AiSettingsEvent
    data object TestConnection : AiSettingsEvent
}
