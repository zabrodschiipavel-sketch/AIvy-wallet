package com.ivy.aiinput

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class AiInputState(
    val inputText: String,
    val phase: Phase,
    /** True while we initially load the list of categories/accounts for the prompt. */
    val loadingContext: Boolean,
    /** Last failure to show the user; null when there's nothing to report. */
    val error: AiError?,
    /** Parsed transactions awaiting review — populated only in [Phase.Review]. */
    val reviewItems: ImmutableList<AiReviewItem> = persistentListOf(),
)

/** A user-facing failure of the AI flow, mapped to an actionable message in the UI. */
sealed interface AiError {
    /** No accounts exist yet — there's nothing to attach the transaction to. */
    data object NoAccounts : AiError

    /** Gemini API key isn't configured; the user must set it in Settings → AI. */
    data object MissingApiKey : AiError

    /** Couldn't reach Gemini (offline, timeout, transport error). */
    data object Network : AiError

    /** The model didn't find any transaction in the text. */
    data object NothingFound : AiError

    /** Anything else — parsing failed or the model returned something unusable. */
    data class Failed(val message: String) : AiError
}

/** One parsed transaction shown as a card on the review screen. */
data class AiReviewItem(
    /** Human-readable summary fields. */
    val amount: String,
    val typeLabel: String,
    val account: String?,
    val category: String?,
    val date: String?,
    val description: String?,
)

enum class Phase {
    /** Empty input or after a reset — waiting for the user to type and press Parse. */
    Input,

    /** Round-trip to Gemini in flight. */
    Parsing,

    /** Several transactions were parsed; the user reviews/edits them before saving. */
    Review,

    /** Bulk "save all" in flight. */
    Saving,
}

sealed interface AiInputEvent {
    data class InputChanged(val value: String) : AiInputEvent
    data object Parse : AiInputEvent

    /** Persist every reviewed transaction at once. */
    data object SaveAll : AiInputEvent

    /** Drop a single reviewed transaction by index. */
    data class RemoveItem(val index: Int) : AiInputEvent

    /** Pull a single reviewed transaction out of the batch and open it in the full editor. */
    data class EditItem(val index: Int) : AiInputEvent

    /** Leave the review screen and go back to text input. */
    data object BackToInput : AiInputEvent
}
