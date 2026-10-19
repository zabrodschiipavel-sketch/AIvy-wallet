package com.ivy.navigation

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.paparazzi.Paparazzi
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationRootTest {
    @get:Rule
    val paparazzi = Paparazzi()

    @get:Rule
    val instantTaskExecutor = InstantTaskExecutorRule()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `navigating to non legacy screen does not clear Activity ViewModels`() = runTest {
        val host = TestHost()
        val activityViewModel = ViewModelProvider(host)[ActivityViewModel::class.java]
        val navigation = Navigation().apply { navigateTo(MainScreen) }
        val renders = Channel<RenderedScreen?>(Channel.UNLIMITED)
        val composition = backgroundScope.launch(Dispatchers.Unconfined) {
            moleculeFlow(RecompositionMode.Immediate) { Render(host, navigation, renders) }
                .collect {}
        }
        try {
            renders.receiveAsFlow().test {
                val main = checkNotNull(awaitItem())
                navigation.navigateTo(FeaturesScreen)
                val features = checkNotNull(awaitItem())

                assertFalse(activityViewModel.cleared)
                assertFalse(main.viewModel.cleared)
                assertNotSame(main.viewModel, features.viewModel)
                navigation.back()
                assertSame(main.viewModel, checkNotNull(awaitItem()).viewModel)
                assertTrue(features.viewModel.cleared)
                assertFalse(activityViewModel.cleared)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            composition.cancelAndJoin()
            host.viewModelStore.clear()
        }
    }

    @Test
    fun `same screen entries have separate SavedStateHandles and pop clears only the child`() = runTest {
        val host = TestHost()
        val navigation = Navigation().apply { navigateTo(SettingsScreen) }
        val renders = Channel<RenderedScreen?>(Channel.UNLIMITED)
        val composition = backgroundScope.launch(Dispatchers.Unconfined) {
            moleculeFlow(RecompositionMode.Immediate) { Render(host, navigation, renders) }
                .collect {}
        }
        try {
            renders.receiveAsFlow().test {
                val first = checkNotNull(awaitItem())
                first.viewModel.handle["draft"] = "first draft"
                navigation.navigateTo(SettingsScreen)
                val second = checkNotNull(awaitItem())
                second.viewModel.handle["draft"] = "second draft"

                assertNotSame(first.owner, second.owner)
                assertNotSame(first.viewModel, second.viewModel)
                assertSame(second.owner, second.savedStateOwner)
                assertSame(second.owner, second.lifecycleOwner)
                assertEquals("first draft", first.viewModel.handle.get<String>("draft"))
                navigation.back()
                val restored = checkNotNull(awaitItem())
                assertSame(first.viewModel, restored.viewModel)
                assertEquals("first draft", restored.viewModel.handle.get<String>("draft"))
                assertTrue(second.viewModel.cleared)
                assertFalse(first.viewModel.cleared)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            composition.cancelAndJoin()
            host.viewModelStore.clear()
        }
    }

    @Test
    fun `reset and replace clears removed entries without clearing the host`() = runTest {
        val host = TestHost()
        val activityViewModel = ViewModelProvider(host)[ActivityViewModel::class.java]
        val navigation = Navigation().apply { navigateTo(MainScreen) }
        val renders = Channel<RenderedScreen?>(Channel.UNLIMITED)
        val composition = backgroundScope.launch(Dispatchers.Unconfined) {
            moleculeFlow(RecompositionMode.Immediate) { Render(host, navigation, renders) }
                .collect {}
        }
        try {
            renders.receiveAsFlow().test {
                val main = checkNotNull(awaitItem())
                navigation.navigateTo(SettingsScreen)
                val settings = checkNotNull(awaitItem())
                Snapshot.withMutableSnapshot {
                    navigation.resetBackStack()
                    navigation.navigateTo(OnboardingScreen)
                }
                val onboarding = checkNotNull(awaitItem())

                assertTrue(main.viewModel.cleared)
                assertTrue(settings.viewModel.cleared)
                assertFalse(onboarding.viewModel.cleared)
                assertFalse(activityViewModel.cleared)
                navigation.clear()
                assertEquals(null, awaitItem())
                assertTrue(onboarding.viewModel.cleared)
                assertFalse(activityViewModel.cleared)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            composition.cancelAndJoin()
            host.viewModelStore.clear()
        }
    }

    @Composable
    private fun Render(
        host: TestHost,
        navigation: Navigation,
        renders: Channel<RenderedScreen?>,
    ) {
        CompositionLocalProvider(
            LocalViewModelStoreOwner provides host,
            LocalLifecycleOwner provides host,
            LocalSavedStateRegistryOwner provides host,
        ) {
            NavigationRoot(navigation) { screen ->
                val result = if (screen == null) {
                    null
                } else {
                    RenderedScreen(
                        screenScopedViewModel<ScreenViewModel>(ScreenFactory),
                        checkNotNull(LocalViewModelStoreOwner.current),
                        LocalSavedStateRegistryOwner.current,
                        LocalLifecycleOwner.current,
                    )
                }
                // A nested restart group can recompose without re-running Molecule's
                // outer producer. Observe committed renders here, not a local return.
                SideEffect { check(renders.trySend(result).isSuccess) }
            }
        }
    }

    private data class RenderedScreen(
        val viewModel: ScreenViewModel,
        val owner: ViewModelStoreOwner,
        val savedStateOwner: SavedStateRegistryOwner,
        val lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    )

    class ActivityViewModel : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    private class ScreenViewModel(val handle: SavedStateHandle) : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    private object ScreenFactory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            assertSame(extras[SAVED_STATE_REGISTRY_OWNER_KEY], extras[VIEW_MODEL_STORE_OWNER_KEY])
            return modelClass.cast(ScreenViewModel(extras.createSavedStateHandle()))
        }
    }

    private class TestHost :
        ViewModelStoreOwner,
        SavedStateRegistryOwner,
        HasDefaultViewModelProviderFactory {
        override val viewModelStore = ViewModelStore()
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry = controller.savedStateRegistry
        override val defaultViewModelProviderFactory = ViewModelProvider.NewInstanceFactory()

        init {
            controller.performAttach()
            controller.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
    }
}
