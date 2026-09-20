package com.linguareader.app.sync

import android.content.Context
import com.linguareader.app.tts.CloudKeyStore
import com.linguareader.shared.sync.SecretStore

/**
 * Android 凭据存储：复用既有 [CloudKeyStore]（AES/GCM，密钥在 Android Keystore），
 * 密文落独立的 `sync_secrets` SharedPreferences。**明文不落盘**。
 */
class AndroidSecretStore(context: Context) : SecretStore {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(key: String): String? =
        CloudKeyStore.decrypt(appContext, prefs.getString(key, null))

    override fun put(key: String, value: String) {
        val encrypted = CloudKeyStore.encrypt(appContext, value) ?: return
        prefs.edit().putString(key, encrypted).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private companion object {
        const val PREFS = "sync_secrets"
    }
}
