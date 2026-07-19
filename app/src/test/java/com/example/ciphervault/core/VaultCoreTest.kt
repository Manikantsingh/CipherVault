package com.example.ciphervault.core

import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultCoreTest {
    private val crypto = VaultCrypto()

    @Test
    fun operationRoundTripsThroughAuthenticatedEncryption() {
        val key = crypto.newVaultKey()
        val operation = upsert("op-1", "credential-1", 1, "device-a", "Example")

        val encrypted = crypto.encryptOperation(operation, key, "vault-1")

        assertEquals(operation, crypto.decryptOperation(encrypted, key, "vault-1"))
    }

    @Test
    fun modifiedCiphertextReportsIntegrityFailure() {
        val key = crypto.newVaultKey()
        val encrypted = crypto.encryptOperation(
            upsert("op-1", "credential-1", 1, "device-a", "Example"),
            key,
            "vault-1",
        )
        val parsed = Json.decodeFromString<EncryptedBlob>(encrypted)
        val bytes = Base64.getDecoder().decode(parsed.ciphertext)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val modified = Json.encodeToString(parsed.copy(ciphertext = Base64.getEncoder().encodeToString(bytes)))

        assertThrows(IntegrityException::class.java) {
            crypto.decryptOperation(modified, key, "vault-1")
        }
    }

    @Test
    fun keyEnvelopeCanBeOpenedOnAnotherDeviceWithPassphrase() {
        val key = crypto.newVaultKey()
        val envelope = crypto.createKeyEnvelope(key, "correct horse battery staple".toCharArray(), "vault-1")

        val restored = crypto.openKeyEnvelope(envelope, "correct horse battery staple".toCharArray())

        assertEquals(key.toList(), restored.toList())
    }

    @Test
    fun concurrentOperationsMergeDeterministicallyAndKeepTombstone() {
        val older = upsert("op-a", "credential-1", 4, "device-a", "Old")
        val concurrentWinner = upsert("op-b", "credential-1", 5, "device-b", "New")
        val concurrentLoser = upsert("op-c", "credential-1", 5, "device-a", "Other")
        val deleted = VaultOperation("op-d", "credential-2", OperationKind.DELETE, null, 7, "device-a")

        val first = MergeEngine.merge(listOf(older, concurrentWinner, concurrentLoser, deleted))
        val second = MergeEngine.merge(listOf(deleted, concurrentLoser, older, concurrentWinner))

        assertEquals(listOf("New"), first.credentials.map { it.title })
        assertEquals(first, second)
        assertEquals(7, first.maxLogicalClock)
    }

    private fun upsert(
        operationId: String,
        credentialId: String,
        clock: Long,
        deviceId: String,
        title: String,
    ) = VaultOperation(
        operationId = operationId,
        credentialId = credentialId,
        kind = OperationKind.UPSERT,
        entry = CredentialEntry(credentialId, title, "user", "secret"),
        logicalClock = clock,
        deviceId = deviceId,
    )
}