package com.securemirage.ciphervault.sync

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val createdTime: String = "",
)

@Serializable
private data class DriveFileList(
    val files: List<DriveFile> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
private data class CreateMetadata(
    val name: String,
    val parents: List<String>,
    @SerialName("mimeType") val mimeType: String,
)

@Serializable
private data class DriveAbout(val user: DriveUser)

@Serializable
private data class DriveUser(val permissionId: String)

class DriveClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun accountId(accessToken: String): String = withContext(Dispatchers.IO) {
        val response = execute(
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/about?fields=user(permissionId)")
                .authorized(accessToken)
                .build(),
        )
        json.decodeFromString<DriveAbout>(response).user.permissionId
    }

    suspend fun listAppData(accessToken: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val allFiles = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("https://www.googleapis.com/drive/v3/files")
                append("?spaces=appDataFolder&pageSize=1000")
                append("&fields=nextPageToken%2Cfiles(id%2Cname%2CcreatedTime)")
                if (pageToken != null) append("&pageToken=").append(pageToken)
            }
            val response = execute(Request.Builder().url(url).authorized(accessToken).build())
            val page = json.decodeFromString<DriveFileList>(response)
            allFiles += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        allFiles
    }

    suspend fun download(fileId: String, accessToken: String): String = withContext(Dispatchers.IO) {
        execute(
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                .authorized(accessToken)
                .build(),
        )
    }

    suspend fun create(name: String, contents: String, accessToken: String): DriveFile = withContext(Dispatchers.IO) {
        val metadata = CreateMetadata(name, listOf("appDataFolder"), ENCRYPTED_MIME_TYPE)
        val multipart = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(
                json.encodeToString(CreateMetadata.serializer(), metadata)
                    .toRequestBody("application/json; charset=UTF-8".toMediaType()),
            )
            .addPart(
                contents.toRequestBody(ENCRYPTED_MIME_TYPE.toMediaType()),
            )
            .build()
        val response = execute(
            Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id%2Cname%2CcreatedTime")
                .authorized(accessToken)
                .post(multipart)
                .build(),
        )
        json.decodeFromString<DriveFile>(response)
    }

    suspend fun delete(fileId: String, accessToken: String) = withContext(Dispatchers.IO) {
        execute(
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId")
                .authorized(accessToken)
                .delete()
                .build(),
        )
    }

    private fun Request.Builder.authorized(accessToken: String): Request.Builder =
        header("Authorization", "Bearer $accessToken")

    private fun execute(request: Request): String = httpClient.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IOException("Google Drive request failed (${response.code}): ${body.take(300)}")
        }
        body
    }

    companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val KEY_PREFIX = "key_"
        const val KEY_SUFFIX = ".cvkey"
        const val OPERATION_PREFIX = "op_"
        const val OPERATION_SUFFIX = ".cvop"
        private const val ENCRYPTED_MIME_TYPE = "application/vnd.ciphervault.encrypted"
    }
}