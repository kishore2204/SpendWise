package com.spendwise.app.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Why a backup file couldn't be opened — shown to the user in plain words. */
class BackupException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_A_BACKUP, NEWER_FORMAT, WRONG_PASSPHRASE_OR_DAMAGED }
}

/**
 * Backup file format (ADR-002 rule 7). Never depends on the Android Keystore — a backup restores on any phone with the
 * passphrase.
 *
 *   "SWBK" | format u8 | db schema version i32 | kdf u8 (1 = PBKDF2-HMAC-SHA256) | iterations i32 | salt[16] | nonce[12]
 *   | AES-256-GCM( gzip(database snapshot) ), 128-bit tag, the whole header as associated data.
 *
 * GCM's tag is the integrity check: a wrong passphrase, a flipped bit or an edited header all fail the same way.
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'W'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    const val FORMAT = 1
    private const val KDF_PBKDF2_SHA256 = 1
    /** OWASP 2023 guidance for PBKDF2-HMAC-SHA256. */
    const val ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val HEADER_BYTES = 4 + 1 + 4 + 1 + 4 + SALT_BYTES + NONCE_BYTES

    fun encrypt(plain: ByteArray, passphrase: CharArray, schemaVersion: Int, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC).put(FORMAT.toByte()).putInt(schemaVersion).put(KDF_PBKDF2_SHA256.toByte()).putInt(iterations).put(salt).put(nonce)
            .array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(gzip(plain))
    }

    /** Returns (schema version, plaintext). */
    fun decrypt(file: ByteArray, passphrase: CharArray): Pair<Int, ByteArray> {
        if (file.size < HEADER_BYTES + TAG_BITS / 8 || !file.copyOfRange(0, 4).contentEquals(MAGIC)) throw BackupException(BackupException.Reason.NOT_A_BACKUP)
        val b = ByteBuffer.wrap(file)
        b.position(4)
        val format = b.get().toInt()
        if (format > FORMAT) throw BackupException(BackupException.Reason.NEWER_FORMAT)
        if (format < 1) throw BackupException(BackupException.Reason.NOT_A_BACKUP)
        val schema = b.int
        val kdf = b.get().toInt()
        val iterations = b.int
        if (kdf != KDF_PBKDF2_SHA256 || iterations !in 10_000..10_000_000) throw BackupException(BackupException.Reason.NOT_A_BACKUP)
        val salt = ByteArray(SALT_BYTES).also { b.get(it) }
        val nonce = ByteArray(NONCE_BYTES).also { b.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(file, 0, HEADER_BYTES)
        val compressed = try {
            cipher.doFinal(file, HEADER_BYTES, file.size - HEADER_BYTES)
        } catch (e: AEADBadTagException) {
            throw BackupException(BackupException.Reason.WRONG_PASSPHRASE_OR_DAMAGED)
        }
        return schema to gunzip(compressed)
    }

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}

/** Passphrase rules shown before a backup is written. */
object Passphrase {
    const val MIN_LENGTH = 8

    fun problem(passphrase: CharArray, confirm: CharArray): String? = when {
        passphrase.size < MIN_LENGTH -> "Use at least $MIN_LENGTH characters"
        !passphrase.contentEquals(confirm) -> "The two passphrases don't match"
        passphrase.all { it == passphrase[0] } -> "Use more than one repeated character"
        else -> null
    }
}
