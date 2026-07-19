package com.example.ciphervault.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.example.ciphervault.sync.DriveClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import java.io.IOException
import kotlinx.coroutines.tasks.await

class DriveSyncWorker(
    applicationContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(applicationContext, workerParameters) {
    override suspend fun doWork(): Result {
        val expectedAccountId = inputData.getString(ACCOUNT_ID) ?: return Result.failure()
        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DriveClient.DRIVE_APPDATA_SCOPE)))
                .build()
            val authorization = Identity.getAuthorizationClient(applicationContext).authorize(request).await()
            if (authorization.hasResolution() || authorization.accessToken == null) {
                Log.w(TAG, "Background Drive authorization requires user interaction")
                Result.failure()
            } else {
                val uploaded = VaultRepository(applicationContext).uploadPendingOperations(
                    checkNotNull(authorization.accessToken),
                    expectedAccountId,
                )
                Log.i(TAG, "Background sync uploaded $uploaded encrypted operations")
                Result.success(Data.Builder().putInt(UPLOADED_COUNT, uploaded).build())
            }
        } catch (_: IOException) {
            Log.w(TAG, "Background Drive sync failed; retrying")
            Result.retry()
        } catch (error: Exception) {
            Log.w(TAG, "Background Drive sync cannot continue", error)
            Result.failure()
        }
    }

    companion object {
        const val ACCOUNT_ID = "account_id"
        const val UPLOADED_COUNT = "uploaded_count"
        const val TAG = "CipherVaultSync"
    }
}