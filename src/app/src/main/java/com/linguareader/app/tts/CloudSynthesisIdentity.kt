package com.linguareader.app.tts

import java.security.MessageDigest

/** Length-prefixing avoids descriptor collisions; only the final SHA reaches cache paths. */
internal object CloudSynthesisIdentity {
    fun hash(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(0.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
