package com.securemirage.ciphervault.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
private data class GitHubRelease(val assets: List<GitHubReleaseAsset> = emptyList())

@Serializable
private data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
)

class ReleaseUpdateChecker(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun latestIfAvailable(currentVersionCode: Int): ReleaseMetadata? = withContext(Dispatchers.IO) {
        val release = json.decodeFromString<GitHubRelease>(get(LATEST_RELEASE_URL, MAX_API_BYTES).decodeToString())
        val metadataUrl = release.assets.singleOrNull { it.name == METADATA_NAME }?.downloadUrl
            ?.let { validatedAssetUrl(it, METADATA_NAME) }
            ?: return@withContext null
        val signatureUrl = release.assets.singleOrNull { it.name == SIGNATURE_NAME }?.downloadUrl
            ?.let { validatedAssetUrl(it, SIGNATURE_NAME) }
            ?: return@withContext null
        val metadataBytes = get(metadataUrl, MAX_METADATA_BYTES)
        val signature = get(signatureUrl, MAX_SIGNATURE_BYTES).decodeToString()
        val metadata = ReleaseMetadataVerifier.verifyAndDecode(metadataBytes, signature, signingPublicKey())
        metadata.takeIf { it.versionCode > currentVersionCode }
    }

    private fun validatedAssetUrl(url: String, expectedName: String): String {
        val parsed = java.net.URI(url)
        require(parsed.scheme == "https" && parsed.host == "github.com") {
            "Release metadata assets must be hosted by GitHub."
        }
        require(parsed.path.startsWith("/Manikantsingh/CipherVault/releases/download/")) {
            "Release metadata assets must belong to the CipherVault repository."
        }
        require(parsed.path.endsWith("/$expectedName")) { "Unexpected release metadata asset name." }
        return url
    }

    private fun signingPublicKey(): PublicKey {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val certificateBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = requireNotNull(packageInfo.signingInfo) { "Application signing information is unavailable." }
            val signers = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            require(signers.size == 1) { "CipherVault requires one release signing identity." }
            signers.single().toByteArray()
        } else {
            @Suppress("DEPRECATION")
            requireNotNull(packageInfo.signatures).single().toByteArray()
        }
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(certificateBytes.inputStream()) as X509Certificate
        return certificate.publicKey
    }

    private fun get(url: String, maximumBytes: Long): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "CipherVault-Android")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Update request failed (${response.code}).")
            val body = response.body ?: throw IOException("Update response was empty.")
            if (body.contentLength() > maximumBytes) throw IOException("Update response was too large.")
            body.bytes().also {
                if (it.size > maximumBytes) throw IOException("Update response was too large.")
            }
        }
    }

    companion object {
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/Manikantsingh/CipherVault/releases/latest"
        private const val METADATA_NAME = "release-metadata.json"
        private const val SIGNATURE_NAME = "release-metadata.json.sig"
        private const val MAX_API_BYTES = 512L * 1024L
        private const val MAX_METADATA_BYTES = 16L * 1024L
        private const val MAX_SIGNATURE_BYTES = 4L * 1024L
    }
}
