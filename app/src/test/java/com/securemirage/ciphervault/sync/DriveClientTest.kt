package com.securemirage.ciphervault.sync

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveClientTest {
    @Test
    fun accountIdUsesAuthenticatedDrivePermissionId() = runBlocking {
        var capturedRequest: Request? = null
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                capturedRequest = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"user":{"permissionId":"opaque-account-id"}}"""
                            .toResponseBody("application/json".toMediaType()),
                    )
                    .build()
            }
            .build()

        val accountId = DriveClient(httpClient).accountId("access-token")

        val request = checkNotNull(capturedRequest)
        assertEquals("opaque-account-id", accountId)
        assertEquals("Bearer access-token", request.header("Authorization"))
        assertEquals("/drive/v3/about", request.url.encodedPath)
        assertEquals("user(permissionId)", request.url.queryParameter("fields"))
    }

    @Test
    fun createBuildsMultipartRelatedUpload() = runBlocking {
        var capturedRequest: Request? = null
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                capturedRequest = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"id":"file-id","name":"key_test.cvkey"}"""
                            .toResponseBody("application/json".toMediaType()),
                    )
                    .build()
            }
            .build()

        val created = DriveClient(httpClient).create("key_test.cvkey", "encrypted", "token")

        val request = checkNotNull(capturedRequest)
        val body = checkNotNull(request.body)
        val payload = Buffer().also(body::writeTo).readUtf8()
        assertEquals("file-id", created.id)
        assertTrue(body.contentType().toString().startsWith("multipart/related; boundary="))
        assertTrue(payload.contains("Content-Type: application/json; charset=UTF-8"))
        assertTrue(payload.contains("Content-Type: application/vnd.ciphervault.encrypted"))
    }

    @Test
    fun deleteUsesAuthenticatedDriveRequest() = runBlocking {
        var capturedRequest: Request? = null
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                capturedRequest = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(204)
                    .message("No Content")
                    .body("".toResponseBody(null))
                    .build()
            }
            .build()

        DriveClient(httpClient).delete("file-id", "access-token")

        val request = checkNotNull(capturedRequest)
        assertEquals("DELETE", request.method)
        assertEquals("Bearer access-token", request.header("Authorization"))
        assertTrue(request.url.toString().endsWith("/drive/v3/files/file-id"))
    }
}