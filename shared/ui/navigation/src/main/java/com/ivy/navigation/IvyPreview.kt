package com.ivy.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.ivy.design.system.IvyMaterial3Theme

@Composable
fun IvyPreview(
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    PreviewNavigationRoot {
        IvyMaterial3Theme(dark = dark, isTrueBlack = false, content = content)
    }
}

@Composable
fun PreviewNavigationRoot(content: @Composable () -> Unit) {
    val owner = remember { PreviewStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        NavigationRoot(navigation = remember { Navigation() }) {
            content()
        }
    }
}

private class PreviewStoreOwner : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val viewModelStore = ViewModelStore()
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
        ViewModelProvider.NewInstanceFactory()
    override val defaultViewModelCreationExtras: CreationExtras = CreationExtras.Empty
}
