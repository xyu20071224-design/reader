package com.linguareader.app.hotfix

/** A debug-only screen lets device verification observe the app class loader, not the test APK. */
class HotfixProbeActivity : android.app.Activity() {
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        if (intent.getBooleanExtra("restart", false)) {
            com.linguareader.app.hotfix.bootstrap.ReaderRestartActivity.restart(this)
            return
        }
        setContentView(android.widget.TextView(this).apply { text = HotfixProbe.value(); textSize = 28f })
    }
}
