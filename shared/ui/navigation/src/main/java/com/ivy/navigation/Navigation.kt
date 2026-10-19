package com.ivy.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Stack
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Stable
@Singleton
class Navigation @Inject constructor() {
    internal var entries: List<NavigationEntry> by mutableStateOf(emptyList())
        private set

    val currentScreen: Screen?
        get() = entries.lastOrNull()?.screen

    internal val currentEntry: NavigationEntry?
        get() = entries.lastOrNull()

    @Deprecated("Legacy code. Don't use it, please.")
    val modalBackHandling: Stack<ModalBackHandler> = Stack()

    @Deprecated("Legacy code. Use Compose `BackHandler {}` instead.")
    val onBackPressed: MutableMap<Screen, () -> Boolean> = mutableMapOf()

    private var replaceOnNextNavigation = false
    val lastScreen: Screen?
        get() = currentScreen.takeUnless { replaceOnNextNavigation }

    @Deprecated("Legacy code. Don't use it, please.")
    data class ModalBackHandler(
        val id: UUID,
        val onBackPressed: () -> Boolean
    )

    @Deprecated("Legacy code. Don't use it, please.")
    fun lastModalBackHandlerId(): UUID? {
        return if (modalBackHandling.isEmpty()) {
            null
        } else {
            modalBackHandling.peek().id
        }
    }

    fun navigateTo(screen: Screen) {
        saveCurrentBackHandler()
        val retained = if (replaceOnNextNavigation) emptyList() else entries
        entries = retained + NavigationEntry(screen = screen)
        replaceOnNextNavigation = false
        restoreCurrentBackHandler()
    }

    fun backStackEmpty() = entries.size <= 1

    @Deprecated("Legacy code. Don't use it, please.")
    fun onBackPressed(): Boolean {
        if (modalBackHandling.isNotEmpty()) {
            return modalBackHandling.peek().onBackPressed()
        }
        val specialHandling = onBackPressed.getOrDefault(currentScreen) { false }.invoke()
        return specialHandling || back()
    }

    fun back(): Boolean {
        if (!backStackEmpty()) {
            entries = entries.dropLast(1)
            replaceOnNextNavigation = false
            restoreCurrentBackHandler()
            return true
        }
        return false
    }

    private fun saveCurrentBackHandler() {
        currentEntry?.let { entry ->
            entry.backHandler = onBackPressed[entry.screen]
        }
    }

    private fun restoreCurrentBackHandler() {
        // The public map is a compatibility bridge for legacy screens. Keep the
        // actual callbacks on entries so two equal routes do not share handlers.
        onBackPressed.clear()
        currentEntry?.let { entry ->
            entry.backHandler?.let { onBackPressed[entry.screen] = it }
        }
    }

    fun resetBackStack() {
        saveCurrentBackHandler()
        entries = entries.takeLast(1)
        replaceOnNextNavigation = true
        restoreCurrentBackHandler()
    }

    /** Release callbacks and routes when the host Activity is permanently finished. */
    fun clear() {
        entries = emptyList()
        onBackPressed.clear()
        modalBackHandling.clear()
        replaceOnNextNavigation = false
    }
}

internal class NavigationEntry(
    val screen: Screen,
    val id: String = UUID.randomUUID().toString(),
) {
    var backHandler: (() -> Boolean)? = null
}
