package com.jacekgajek.kapelusz.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry

/**
 * The [VM] of a screen, scoped to its back-stack entry: made once with [initializer] (which may take a
 * `createSavedStateHandle()` from the extras), then kept while the entry lives, across recompositions and rotations.
 *
 * Not `viewModel()` from lifecycle-viewmodel-compose: Compose 1.12 pulls lifecycle 2.11, whose viewmodel-compose is
 * not published for the web, so the shared source set cannot see it (the per-platform compilations can).
 */
inline fun <reified VM : ViewModel> NavBackStackEntry.screenViewModel(noinline initializer: CreationExtras.() -> VM): VM {
    val factory = viewModelFactory { initializer(initializer) }
    return ViewModelProvider.create(this, factory, defaultViewModelCreationExtras)[VM::class]
}
