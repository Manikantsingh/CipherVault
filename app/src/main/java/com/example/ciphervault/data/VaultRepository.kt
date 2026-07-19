package com.example.ciphervault.data

import android.content.Context
import com.example.ciphervault.core.CredentialEntry
import com.example.ciphervault.core.IntegrityException
import com.example.ciphervault.core.KeyEnvelope
import com.example.ciphervault.core.MergeEngine
import com.example.ciphervault.core.MergedVault
import com.example.ciphervault.core.OperationKind
import com.example.ciphervault.core.VaultCrypto
import com.example.ciphervault.core.VaultOperation
import com.example.ciphervault.security.BiometricKeyStore
import com.example.ciphervault.security.LocalVaultKey
import com.example.ciphervault.sync.DriveClient
import com.example.ciphervault.sync.DriveFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VaultRepository(
    context: Context,
    private val crypto: VaultCrypto = VaultCrypto(),
    private val drive: DriveClient = DriveClient(),
    val biometricKeyStore: BiometricKeyStore = BiometricKeyStore(context),
) {
    private val applicationContext = context.applicationContext
    private val operationRoot = File(context.filesDir, "encrypted_operations").apply { mkdirs() }
    private val deviceId = context.getSharedPreferences("device_identity", Context.MODE_PRIVATE).let { preferences ->
        preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("device_id", it).apply()
        }
    }
    private var session: VaultSession? = null

    fun hasLocalVault(): Boolean = biometricKeyStore.hasWrappedVaultKey()

    fun hasAnyLocalVault(): Boolean = biometricKeyStore.hasAnyWrappedVaultKey()

    fun activeAccountId(): String? = biometricKeyStore.activeAccountId()

    fun hasLegacyVault(): Boolean = biometricKeyStore.hasLegacyVault()

    fun selectAccount(accountId: String) {
        lock()
        biometricKeyStore.selectAccount(accountId)
    }

    fun bindLegacyVault(accountId: String) = biometricKeyStore.bindLegacyVault(accountId)

    suspend fun openLocal(localVaultKey: LocalVaultKey): MergedVault = withContext(Dispatchers.IO) {
        session?.key?.fill(0)
        session = VaultSession(localVaultKey.vaultId, localVaultKey.key)
        mergeLocal()
    }

    suspend fun hasRemoteVault(accessToken: String): Boolean =
        drive.listAppData(accessToken).any { it.name.startsWith(DriveClient.KEY_PREFIX) }

    suspend fun driveAccountId(accessToken: String): String = drive.accountId(accessToken)

    suspend fun uploadPendingOperations(accessToken: String, expectedAccountId: String): Int =
        withContext(Dispatchers.IO) {
            check(biometricKeyStore.activeAccountId() == expectedAccountId) {
                "The active local vault belongs to a different Google account"
            }
            val actualAccountId = accountProfileId(drive.accountId(accessToken))
            check(actualAccountId == expectedAccountId) {
                "Google Drive authorization belongs to a different account"
            }
            val vaultId = checkNotNull(biometricKeyStore.activeVaultId()) { "No local vault is selected" }
            val operationDirectory = operationDirectory(vaultId)
            val remoteNames = drive.listAppData(accessToken)
                .asSequence()
                .map { it.name }
                .filter { it.startsWith(DriveClient.OPERATION_PREFIX) && it.endsWith(DriveClient.OPERATION_SUFFIX) }
                .toHashSet()
            var uploaded = 0
            localOperationFiles(operationDirectory).forEach { localFile ->
                if (localFile.name !in remoteNames) {
                    drive.create(localFile.name, localFile.readText(), accessToken)
                    uploaded++
                }
            }
            uploaded
        }

    suspend fun createVault(passphrase: CharArray, accessToken: String): MergedVault = withContext(Dispatchers.IO) {
        require(passphrase.size >= 12) { "Use a passphrase with at least 12 characters" }
        val vaultId = UUID.randomUUID().toString()
        val key = crypto.newVaultKey()
        val envelope = crypto.createKeyEnvelope(key, passphrase, vaultId)
        val envelopeName = "${DriveClient.KEY_PREFIX}${UUID.randomUUID()}${DriveClient.KEY_SUFFIX}"
        drive.create(envelopeName, crypto.encodeEnvelope(envelope), accessToken)
        biometricKeyStore.wrapAndStore(key, vaultId)
        session = VaultSession(vaultId, key)
        mergeLocal()
    }

    suspend fun restoreVault(passphrase: CharArray, accessToken: String): MergedVault = withContext(Dispatchers.IO) {
        val keyFiles = drive.listAppData(accessToken)
            .filter { it.name.startsWith(DriveClient.KEY_PREFIX) && it.name.endsWith(DriveClient.KEY_SUFFIX) }
            .sortedBy { it.createdTime }
        require(keyFiles.isNotEmpty()) { "No CipherVault data exists in this Google Drive account" }
        var lastFailure: Throwable? = null
        for (file in keyFiles) {
            try {
                val envelope: KeyEnvelope = crypto.decodeEnvelope(drive.download(file.id, accessToken))
                val key = crypto.openKeyEnvelope(envelope, passphrase)
                biometricKeyStore.wrapAndStore(key, envelope.vaultId)
                session = VaultSession(envelope.vaultId, key)
                return@withContext sync(accessToken)
            } catch (error: IntegrityException) {
                lastFailure = error
            }
        }
        throw IntegrityException(lastFailure)
    }

    suspend fun replaceVault(passphrase: CharArray, accessToken: String): MergedVault = withContext(Dispatchers.IO) {
        require(passphrase.size >= 12) { "Use a passphrase with at least 12 characters" }
        val oldRemoteFiles = drive.listAppData(accessToken).filter { it.isCipherVaultFile() }
        val oldVaultId = session?.vaultId
        val vaultId = UUID.randomUUID().toString()
        val key = crypto.newVaultKey()
        try {
            val envelope = crypto.createKeyEnvelope(key, passphrase, vaultId)
            val envelopeName = "${DriveClient.KEY_PREFIX}${UUID.randomUUID()}${DriveClient.KEY_SUFFIX}"
            drive.create(envelopeName, crypto.encodeEnvelope(envelope), accessToken)
            oldRemoteFiles.forEach { drive.delete(it.id, accessToken) }

            session?.key?.fill(0)
            session = null
            biometricKeyStore.clear()
            oldVaultId?.let { operationDirectory(it).deleteRecursively() }
            biometricKeyStore.wrapAndStore(key, vaultId)
            session = VaultSession(vaultId, key)
            mergeLocal()
        } catch (error: Exception) {
            if (session?.key !== key) key.fill(0)
            throw error
        }
    }

    suspend fun sync(accessToken: String): MergedVault = withContext(Dispatchers.IO) {
        val activeSession = requireSession()
        val operationDirectory = operationDirectory(activeSession.vaultId)
        val remoteFiles = drive.listAppData(accessToken)
            .filter { it.name.startsWith(DriveClient.OPERATION_PREFIX) && it.name.endsWith(DriveClient.OPERATION_SUFFIX) }
        val remoteNames = remoteFiles.mapTo(mutableSetOf()) { it.name }

        localOperationFiles(operationDirectory).forEach { localFile ->
            if (localFile.name !in remoteNames) {
                drive.create(localFile.name, localFile.readText(), accessToken)
            }
        }
        val localNames = localOperationFiles(operationDirectory).mapTo(mutableSetOf()) { it.name }
        remoteFiles.forEach { remoteFile ->
            if (remoteFile.name !in localNames) {
                val encrypted = drive.download(remoteFile.id, accessToken)
                crypto.decryptOperation(encrypted, activeSession.key, activeSession.vaultId)
                File(operationDirectory, remoteFile.name).writeText(encrypted)
            }
        }
        mergeLocal()
    }

    suspend fun upsert(entry: CredentialEntry, accessToken: String?): VaultUpdate =
        append(OperationKind.UPSERT, entry.id, entry, accessToken)

    suspend fun delete(credentialId: String, accessToken: String?): VaultUpdate =
        append(OperationKind.DELETE, credentialId, null, accessToken)

    fun lock() {
        session?.key?.fill(0)
        session = null
    }

    private suspend fun append(
        kind: OperationKind,
        credentialId: String,
        entry: CredentialEntry?,
        accessToken: String?,
    ): VaultUpdate = withContext(Dispatchers.IO) {
        val activeSession = requireSession()
        val operation = VaultOperation(
            operationId = UUID.randomUUID().toString(),
            credentialId = credentialId,
            kind = kind,
            entry = entry,
            logicalClock = mergeLocal().maxLogicalClock + 1,
            deviceId = deviceId,
        )
        val fileName = "${DriveClient.OPERATION_PREFIX}${operation.operationId}${DriveClient.OPERATION_SUFFIX}"
        val encrypted = crypto.encryptOperation(operation, activeSession.key, activeSession.vaultId)
        val operationFile = File(operationDirectory(activeSession.vaultId), fileName)
        val temporaryFile = File(operationFile.parentFile, ".$fileName.tmp")
        FileOutputStream(temporaryFile).use { output ->
            output.write(encrypted.toByteArray())
            output.fd.sync()
        }
        try {
            Files.move(
                temporaryFile.toPath(),
                operationFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporaryFile.toPath(), operationFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val synced = accessToken != null && try {
            drive.create(fileName, encrypted, accessToken)
            true
        } catch (_: IOException) {
            false
        }
        if (!synced) {
            biometricKeyStore.activeAccountId()?.let {
                BackgroundSyncScheduler.enqueue(applicationContext, it)
            }
        }
        VaultUpdate(mergeLocal(), synced)
    }

    private fun mergeLocal(): MergedVault {
        val activeSession = requireSession()
        val operations = localOperationFiles(operationDirectory(activeSession.vaultId)).map { file ->
            crypto.decryptOperation(file.readText(), activeSession.key, activeSession.vaultId)
        }
        return MergeEngine.merge(operations)
    }

    private fun localOperationFiles(directory: File): List<File> =
        directory.listFiles().orEmpty().filter {
            it.name.startsWith(DriveClient.OPERATION_PREFIX) && it.name.endsWith(DriveClient.OPERATION_SUFFIX)
        }

    private fun operationDirectory(vaultId: String): File {
        val accountDirectory = biometricKeyStore.activeAccountId() ?: LEGACY_ACCOUNT_DIRECTORY
        val destination = File(File(operationRoot, accountDirectory), vaultId)
        if (!destination.exists()) {
            destination.parentFile?.mkdirs()
            val previousDirectory = listOf(
                File(operationRoot, vaultId),
                File(File(operationRoot, LEGACY_ACCOUNT_DIRECTORY), vaultId),
            ).firstOrNull { it.exists() && it != destination }
            if (previousDirectory != null) {
                Files.move(previousDirectory.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        destination.mkdirs()
        return destination
    }

    private fun requireSession(): VaultSession = checkNotNull(session) { "Vault is locked" }

    private fun DriveFile.isCipherVaultFile(): Boolean =
        (name.startsWith(DriveClient.KEY_PREFIX) && name.endsWith(DriveClient.KEY_SUFFIX)) ||
            (name.startsWith(DriveClient.OPERATION_PREFIX) && name.endsWith(DriveClient.OPERATION_SUFFIX))

    private data class VaultSession(val vaultId: String, val key: ByteArray)

    private companion object {
        const val LEGACY_ACCOUNT_DIRECTORY = "legacy"
    }
}

data class VaultUpdate(val vault: MergedVault, val synced: Boolean)