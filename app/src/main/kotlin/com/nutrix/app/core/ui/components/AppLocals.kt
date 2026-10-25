package com.nutrix.app.core.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Called before launching another app's activity (system photo picker, file picker, settings)
 * so that coming back does not trigger the app lock.
 */
val LocalExternalTransition = staticCompositionLocalOf<() -> Unit> { {} }
