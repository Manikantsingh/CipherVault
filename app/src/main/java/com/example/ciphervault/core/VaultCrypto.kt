package com.example.ciphervault.core

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class IntegrityException(cause: Throwable? = null) : GeneralSecurityException(
    "Vault integrity verification failed. The encrypted data may be damaged or modified.",
    cause,
)

@Serializable
data class EncryptedBlob(
    val version: Int = 1,
    val nonce: String,
    val ciphertext: String,
)

@Serializable
data class KeyEnvelope(
    val version: Int = 1,
    val vaultId: String,
    val salt: String,
    val iterations: Int,
    val wrappedKey: EncryptedBlob,
)

class VaultCrypto(
    private val json: Json = Json { ignoreUnknownKeys = false },
    private val random: SecureRandom = SecureRandom(),
) {
    fun newVaultKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    fun encryptOperation(operation: VaultOperation, vaultKey: ByteArray, vaultId: String): String =
        json.encodeToString(encrypt(json.encodeToString(operation).encodeToByteArray(), vaultKey, operationAad(vaultId)))

    fun decryptOperation(payload: String, vaultKey: ByteArray, vaultId: String): VaultOperation = try {
        val blob = json.decodeFromString<EncryptedBlob>(payload)
        json.decodeFromString(decrypt(blob, vaultKey, operationAad(vaultId)).decodeToString())
    } catch (error: Exception) {
        if (error is IntegrityException) throw error
        throw IntegrityException(error)
    }

    fun createKeyEnvelope(vaultKey: ByteArray, passphrase: CharArray, vaultId: String): KeyEnvelope {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val wrappingKey = deriveKey(passphrase, salt, PBKDF2_ITERATIONS)
        return try {
            KeyEnvelope(
                vaultId = vaultId,
                salt = salt.toBase64(),
                iterations = PBKDF2_ITERATIONS,
                wrappedKey = encrypt(vaultKey, wrappingKey, envelopeAad(vaultId)),
            )
        } finally {
            wrappingKey.fill(0)
        }
    }

    fun openKeyEnvelope(envelope: KeyEnvelope, passphrase: CharArray): ByteArray {
        val wrappingKey = deriveKey(passphrase, envelope.salt.fromBase64(), envelope.iterations)
        return try {
            decrypt(envelope.wrappedKey, wrappingKey, envelopeAad(envelope.vaultId))
        } catch (error: Exception) {
            throw IntegrityException(error)
        } finally {
            wrappingKey.fill(0)
        }
    }

    fun encodeEnvelope(envelope: KeyEnvelope): String = json.encodeToString(envelope)

    fun decodeEnvelope(payload: String): KeyEnvelope = json.decodeFromString(payload)

    private fun encrypt(plaintext: ByteArray, key: ByteArray, aad: ByteArray): EncryptedBlob {
        require(key.size == KEY_BYTES)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return EncryptedBlob(nonce = nonce.toBase64(), ciphertext = cipher.doFinal(plaintext).toBase64())
    }

    private fun decrypt(blob: EncryptedBlob, key: ByteArray, aad: ByteArray): ByteArray = try {
        require(blob.version == 1)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, blob.nonce.fromBase64()),
        )
        cipher.updateAAD(aad)
        cipher.doFinal(blob.ciphertext.fromBase64())
    } catch (error: AEADBadTagException) {
        throw IntegrityException(error)
    } catch (error: IllegalArgumentException) {
        throw IntegrityException(error)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations >= MIN_PBKDF2_ITERATIONS)
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun operationAad(vaultId: String) = "ciphervault:operation:v1:$vaultId".encodeToByteArray()
    private fun envelopeAad(vaultId: String) = "ciphervault:key-envelope:v1:$vaultId".encodeToByteArray()

    private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)
    private fun String.fromBase64(): ByteArray = Base64.getDecoder().decode(this)

    private companion object {
        const val KEY_BYTES = 32
        const val SALT_BYTES = 16
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val PBKDF2_ITERATIONS = 600_000
        const val MIN_PBKDF2_ITERATIONS = 210_000
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}