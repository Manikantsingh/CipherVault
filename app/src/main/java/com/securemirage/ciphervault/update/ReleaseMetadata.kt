package com.securemirage.ciphervault.update

import java.net.URI
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ReleaseMetadata(
    val schemaVersion: Int,
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
)

object ReleaseMetadataVerifier {
    private val sha256Pattern = Regex("^[a-f0-9]{64}$")

    fun verifyAndDecode(
        metadata: ByteArray,
        encodedSignature: String,
        publicKey: PublicKey,
        json: Json = Json { ignoreUnknownKeys = false },
    ): ReleaseMetadata {
        val signatureBytes = runCatching { Base64.getDecoder().decode(encodedSignature.trim()) }
            .getOrElse { throw SecurityException("Release metadata signature is not valid Base64.", it) }
        val verifier = Signature.getInstance(signatureAlgorithm(publicKey))
        verifier.initVerify(publicKey)
        verifier.update(metadata)
        check(verifier.verify(signatureBytes)) { "Release metadata signature verification failed." }

        val decoded = json.decodeFromString<ReleaseMetadata>(metadata.decodeToString())
        require(decoded.schemaVersion == 1) { "Unsupported release metadata schema." }
        require(decoded.versionCode > 0) { "Release version code must be positive." }
        require(decoded.versionName.isNotBlank()) { "Release version name is missing." }
        require(sha256Pattern.matches(decoded.sha256)) { "Release APK checksum is invalid." }

        val apkUri = URI(decoded.apkUrl)
        require(apkUri.scheme == "https" && apkUri.host == "github.com") {
            "Release APK must be hosted by GitHub."
        }
        require(apkUri.path.startsWith("/Manikantsingh/CipherVault/releases/download/")) {
            "Release APK must belong to the CipherVault repository."
        }
        require(apkUri.path.endsWith(".apk")) { "Release download must be an APK." }
        return decoded
    }

    private fun signatureAlgorithm(publicKey: PublicKey): String = when (publicKey.algorithm.uppercase()) {
        "RSA" -> "SHA256withRSA"
        "EC", "ECDSA" -> "SHA256withECDSA"
        else -> throw SecurityException("Unsupported release signing key algorithm: ${publicKey.algorithm}")
    }
}
