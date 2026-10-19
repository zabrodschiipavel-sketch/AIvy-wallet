package com.ivy.navigation

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

@SuppressLint("ComposeCompositionLocalUsage")
private val LocalNavigation = compositionLocalOf<Navigation> { error("No LocalNavigation") }

@Composable
fun NavigationRoot(
    navigation: Navigation,
    navGraph: @Composable (screen: Screen?) -> Unit
) {
    val entries = navigation.entries
    val entry = entries.lastOrNull()
    if (entry == null) {
        CompositionLocalProvider(LocalNavigation provides navigation) {
            navGraph(null)
        }
        return
    }
    val hostStoreOwner = checkNotNull(LocalViewModelStoreOwner.current)
    val hostFactory = checkNotNull(hostStoreOwner as? HasDefaultViewModelProviderFactory)
    val hostLifecycle = LocalLifecycleOwner.current.lifecycle
    val stores = ViewModelProvider(
        hostStoreOwner,
        ViewModelProvider.NewInstanceFactory(),
    )[NavigationEntryStores::class.java]
    val saveableStateHolder = rememberSaveableStateHolder()
    val savedEntryIds = remember { mutableSetOf<String>() }

    SideEffect {
        stores.retainEntries(entries)
        val retainedIds = entries.mapTo(mutableSetOf()) { it.id }
        (savedEntryIds - retainedIds).forEach(saveableStateHolder::removeState)
        savedEntryIds.retainAll(retainedIds)
        savedEntryIds.add(entry.id)
    }

    CompositionLocalProvider(
        LocalNavigation provides navigation,
    ) {
        key(entry.id) {
            val owner = stores.owner(entry)
            owner.attach(hostFactory, hostLifecycle.currentState)
            DisposableEffect(owner, hostLifecycle) {
                val observer = LifecycleEventObserver { _, _ ->
                    owner.updateHostState(hostLifecycle.currentState)
                }
                hostLifecycle.addObserver(observer)
                onDispose {
                    hostLifecycle.removeObserver(observer)
                    owner.detach()
                }
            }
            saveableStateHolder.SaveableStateProvider(entry.id) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalLifecycleOwner provides owner,
                    LocalSavedStateRegistryOwner provides owner,
                ) {
                    navGraph(entry.screen)
                }
            }
        }
    }
}

@Composable
fun navigation(): Navigation {
    return LocalNavigation.current
}

/**
 * Provides a [ViewModel] scoped to this back-stack entry. It survives navigating
 * forward and Activity recreation, and is cleared when the entry is removed.
 */
@Composable
inline fun <reified T : ViewModel> screenScopedViewModel(
    factory: ViewModelProvider.Factory? = null
): T {
    val viewModelStoreOwner = LocalViewModelStoreOwner.current
    requireNotNull(viewModelStoreOwner) { "No ViewModelStoreOwner provided" }
    val viewModelProvider = factory?.let {
        ViewModelProvider(viewModelStoreOwner, it)
    } ?: ViewModelProvider(viewModelStoreOwner)
    return viewModelProvider[T::class.java]
}
