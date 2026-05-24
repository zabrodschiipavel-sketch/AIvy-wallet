package com.ivy.aiinput

import com.ivy.ai.domain.ParsedTransaction

data class AiInputState(
    val inputText: String,
    val phase: Phase,
    /** True while we initially load the list of categories/accounts for the prompt. */
    val loadingContext: Boolean,
    /** Latest parse result — only populated when phase is Preview. */
    val parsed: ParsedTransaction?,
    /** Defaults to base currency from settings; used when the model doesn't pick one up. */
    val defaultCurrency: String,
)

enum class Phase {
    /** Empty input or after a reset — waiting for the user to type and press Parse. */
    Input,
    /** Round-trip to Gemini in flight. */
    Parsing,
    /** A parsed draft is shown for the user to confirm. */
    Preview,
    /** Save in flight — UI disables Save button. */
    Saving,
    /** Saved & navigated away (terminal state, briefly visible). */
    Saved,
}

sealed interface AiInputEvent {
    data class InputChanged(val value: String) : AiInputEvent
    data object Parse : AiInputEvent
    data object Save : AiInputEvent
    data object Reset : AiInputEvent
    data class Error(val message: String) : AiInputEvent
}
