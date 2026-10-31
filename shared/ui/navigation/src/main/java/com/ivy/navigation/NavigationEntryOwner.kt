package com.ivy.navigation

import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.enableSavedStateHandles
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/** Stores survive Activity recreation, but are cleared when their entry is popped. */
internal class NavigationEntryStores : ViewModel() {
    private val owners = mutableMapOf<String, NavigationEntryOwner>()

    fun owner(entry: NavigationEntry): NavigationEntryOwner =
        owners.getOrPut(entry.id) { NavigationEntryOwner() }

    fun retainEntries(entries: List<NavigationEntry>) {
        val retainedIds = entries.mapTo(mutableSetOf()) { it.id }
        owners.keys.filter { it !in retainedIds }.forEach { id ->
            owners.remove(id)?.clear()
        }
    }

    override fun onCleared() {
        owners.values.forEach(NavigationEntryOwner::clear)
        owners.clear()
    }
}

/**
 * A screen entry owns its ViewModels, lifecycle and SavedStateHandles. The host
 * factory is borrowed only while attached, so a retained entry cannot keep the
 * previous Activity through Hilt's factory after a configuration change.
 *
 * This does not serialize the legacy Screen payloads for process-death restore.
 */
internal class NavigationEntryOwner :
    ViewModelStoreOwner,
    LifecycleOwner,
    SavedStateRegistryOwner,
    HasDefaultViewModelProviderFactory {
    override val viewModelStore = ViewModelStore()
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private var hostFactory: HasDefaultViewModelProviderFactory? = null

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        enableSavedStateHandles()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = checkNotNull(hostFactory) { "Navigation entry is not attached" }
            .defaultViewModelProviderFactory

    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras(
            hostFactory?.defaultViewModelCreationExtras ?: CreationExtras.Empty
        ).apply {
            this[SAVED_STATE_REGISTRY_OWNER_KEY] = this@NavigationEntryOwner
            this[VIEW_MODEL_STORE_OWNER_KEY] = this@NavigationEntryOwner
        }

    fun attach(host: HasDefaultViewModelProviderFactory, state: Lifecycle.State) {
        hostFactory = host
        updateHostState(state)
    }

    fun updateHostState(state: Lifecycle.State) {
        // A temporary host destruction (rotation) must not destroy a retained
        // entry; its store is owned by NavigationEntryStores, not the Activity.
        lifecycleRegistry.currentState = if (state.isAtLeast(Lifecycle.State.CREATED)) {
            state
        } else {
            Lifecycle.State.CREATED
        }
    }

    fun detach() {
        hostFactory = null
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
    }

    fun clear() {
        hostFactory = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
