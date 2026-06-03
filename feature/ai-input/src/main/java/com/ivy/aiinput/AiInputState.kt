package com.ivy.aiinput

data class AiInputState(
    val inputText: String,
    val phase: Phase,
    /** True while we initially load the list of categories/accounts for the prompt. */
    val loadingContext: Boolean,
    /** Last failure to show the user; null when there's nothing to report. */
    val error: AiError?,
)

/** A user-facing failure of the AI flow, mapped to an actionable message in the UI. */
sealed interface AiError {
    /** No accounts exist yet — there's nothing to attach the transaction to. */
    data object NoAccounts : AiError

    /** Gemini API key isn't configured; the user must set it in Settings → AI. */
    data object MissingApiKey : AiError

    /** Couldn't reach Gemini (offline, timeout, transport error). */
    data object Network : AiError

    /** Anything else — parsing failed or the model returned something unusable. */
    data class Failed(val message: String) : AiError
}

enum class Phase {
    /** Empty input or after a reset — waiting for the user to type and press Parse. */
    Input,
    /** Round-trip to Gemini in flight; on success we navigate to the editor. */
    Parsing,
}

sealed interface AiInputEvent {
    data class InputChanged(val value: String) : AiInputEvent
    data object Parse : AiInputEvent
}
