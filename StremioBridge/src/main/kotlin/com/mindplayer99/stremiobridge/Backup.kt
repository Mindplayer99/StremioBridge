package com.mindplayer99.stremiobridge

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

internal object Backup {
    private const val prefix = "StremioBridge1."
    private val random = SecureRandom()

    private fun cipher(mode: Int, pass: CharArray, salt: ByteArray, nonce: ByteArray): Cipher {
        val spec = PBEKeySpec(pass, salt, 600_000, 256)
        try {
            val key =
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return try {
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                    updateAAD(prefix.toByteArray(Charsets.UTF_8))
                }
            } finally {
                key.fill(0)
            }
        } finally {
            spec.clearPassword()
            pass.fill('\u0000')
        }
    }

    fun seal(p: Profile, pass: CharArray): String {
        require(pass.size >= 12)
        val raw =
            profileJson(p.copy(addons = p.addons.map { it.copy(manifest = null, checked = 0) }))
                .toString()
                .toByteArray(Charsets.UTF_8)
        require(raw.size <= 250_000)
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val encrypted =
            try {
                cipher(Cipher.ENCRYPT_MODE, pass, salt, nonce).doFinal(raw)
            } finally {
                raw.fill(0)
            }
        return prefix + (salt + nonce + encrypted).toByteString().base64()
    }

    fun open(raw: String, pass: CharArray, name: String): Profile {
        require(raw.length <= 350_000 && raw.startsWith(prefix))
        val bytes =
            raw.removePrefix(prefix).decodeBase64()?.toByteArray() ?: error("Invalid backup")
        require(bytes.size >= 44)
        val plain =
            cipher(Cipher.DECRYPT_MODE, pass, bytes.copyOfRange(0, 16), bytes.copyOfRange(16, 28))
                .doFinal(bytes, 28, bytes.size - 28)
        val p =
            try {
                readProfile(JSONObject(String(plain, Charsets.UTF_8)))
            } finally {
                plain.fill(0)
            }
        require(p.addons.map { it.key }.distinct().size == p.addons.size)
        p.addons.forEach { if (it.url.isNotBlank()) Endpoint(it.url) }
        return p.copy(
            key = freshId(),
            name = name,
            addons = p.addons.map { it.copy(manifest = null, checked = 0) },
        )
    }
}
