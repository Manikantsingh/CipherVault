package com.securemirage.ciphervault.update

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleaseMetadataTest {
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val metadata = """{"schemaVersion":1,"versionCode":2,"versionName":"1.1.0","apkUrl":"https://github.com/Manikantsingh/CipherVault/releases/download/v1.1.0/CipherVault-v1.1.0.apk","sha256":"${"a".repeat(64)}"}"""
        .encodeToByteArray()

    @Test
    fun verifiesMetadataSignedByReleaseKey() {
        val decoded = ReleaseMetadataVerifier.verifyAndDecode(metadata, sign(metadata), keyPair.public)

        assertEquals(2, decoded.versionCode)
        assertEquals("1.1.0", decoded.versionName)
    }

    @Test
    fun rejectsModifiedMetadata() {
        val signature = sign(metadata)
        val modified = metadata.decodeToString().replace("1.1.0", "9.9.9").encodeToByteArray()

        assertThrows(IllegalStateException::class.java) {
            ReleaseMetadataVerifier.verifyAndDecode(modified, signature, keyPair.public)
        }
    }

    @Test
    fun rejectsApkOutsideCipherVaultReleases() {
        val invalid = metadata.decodeToString()
            .replace("github.com/Manikantsingh/CipherVault", "github.com/attacker/project")
            .encodeToByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            ReleaseMetadataVerifier.verifyAndDecode(invalid, sign(invalid), keyPair.public)
        }
    }

    private fun sign(bytes: ByteArray): String {
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(bytes)
        return Base64.getEncoder().encodeToString(signer.sign())
    }
}
