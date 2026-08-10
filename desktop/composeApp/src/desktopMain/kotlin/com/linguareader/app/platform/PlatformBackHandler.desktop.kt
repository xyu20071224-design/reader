package com.linguareader.app.platform

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop has no system back button; the reader's in-app back arrow is used.
}
