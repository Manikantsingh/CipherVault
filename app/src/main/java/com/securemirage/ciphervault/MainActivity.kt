package com.securemirage.ciphervault

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.securemirage.ciphervault.core.CredentialEntry
import com.securemirage.ciphervault.core.IntegrityException
import com.securemirage.ciphervault.data.VaultRepository
import com.securemirage.ciphervault.data.VaultUpdate
import com.securemirage.ciphervault.data.BackgroundSyncScheduler
import com.securemirage.ciphervault.data.accountProfileId
import com.securemirage.ciphervault.sync.DriveClient
import com.securemirage.ciphervault.update.ReleaseMetadata
import com.securemirage.ciphervault.update.ReleaseUpdateChecker
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : FragmentActivity() {
    private val repository by lazy { VaultRepository(applicationContext) }
    private val credentialManager by lazy { CredentialManager.create(this) }
    private val authorizationClient by lazy { Identity.getAuthorizationClient(this) }
    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }
    private var state by mutableStateOf<AppState>(AppState.Loading)
    private var availableUpdate by mutableStateOf<ReleaseMetadata?>(null)
    private var accessToken: String? = null
    private var driveRequestInProgress = false
    private var networkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                runOnUiThread(::syncUnlockedVault)
            }
        }
    }

    private val authorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            val unlockedVault = state as? AppState.Vault
            state = unlockedVault?.copy(syncStatus = "Saved offline")
                ?: AppState.Error("Google Drive access was not granted.")
            return@registerForActivityResult
        }
        runCatching { authorizationClient.getAuthorizationResultFromIntent(result.data!!).accessToken }
            .onSuccess { token ->
                if (token == null) {
                    val unlockedVault = state as? AppState.Vault
                    state = unlockedVault?.copy(syncStatus = "Saved offline")
                        ?: AppState.Error("Google Drive did not return an access token.")
                }
                else onDriveAuthorized(token)
            }
            .onFailure { error ->
                val unlockedVault = state as? AppState.Vault
                if (unlockedVault == null) {
                    showError(error)
                } else {
                    Log.w("CipherVault", "Drive authorization unavailable; keeping local vault open", error)
                    state = unlockedVault.copy(syncStatus = "Saved offline")
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = if (repository.hasLocalVault()) AppState.Locked else AppState.SignedOut
        repository.activeAccountId()?.let { BackgroundSyncScheduler.enqueue(applicationContext, it) }
        checkForUpdate()
        setContent {
            CipherVaultTheme {
                CipherVaultApp(
                    state = state,
                    onGoogleSignIn = ::signInWithGoogle,
                    onUnlock = ::unlockWithBiometrics,
                    onCreateVault = ::createVault,
                    onRestoreVault = ::restoreVault,
                    onReplaceVault = ::replaceVault,
                    onBeginVaultReplacement = {
                        state = AppState.Passphrase(remoteVaultExists = false, replacingExisting = true)
                    },
                    onSave = ::saveCredential,
                    onDelete = ::deleteCredential,
                    onSync = ::authorizeDrive,
                    onSwitchAccount = ::switchGoogleAccount,
                    onRetry = { state = if (repository.hasLocalVault()) AppState.Locked else AppState.SignedOut },
                    availableUpdate = availableUpdate,
                    onOpenUpdate = ::openUpdate,
                    onDismissUpdate = { availableUpdate = null },
                )
            }
        }
    }

    private fun checkForUpdate() {
        if (BuildConfig.ENVIRONMENT != "production") return
        lifecycleScope.launch {
            runCatching {
                ReleaseUpdateChecker(applicationContext).latestIfAvailable(BuildConfig.VERSION_CODE)
            }.onSuccess { availableUpdate = it }
                .onFailure { Log.w("CipherVault", "Signed release update check failed", it) }
        }
    }

    private fun openUpdate(update: ReleaseMetadata) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.apkUrl)))
        availableUpdate = null
    }

    override fun onStart() {
        super.onStart()
        if (!networkCallbackRegistered) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }
        syncUnlockedVault()
    }

    override fun onStop() {
        if (networkCallbackRegistered) {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        }
        super.onStop()
    }

    private fun signInWithGoogle() {
        signInWithGoogle(forceAccountChoice = false)
    }

    private fun switchGoogleAccount() {
        repository.lock()
        accessToken = null
        signInWithGoogle(forceAccountChoice = true)
    }

    private fun signInWithGoogle(forceAccountChoice: Boolean) {
        state = AppState.Loading
        lifecycleScope.launch {
            try {
                val credential = if (forceAccountChoice) {
                    requestGoogleCredential(filterAuthorized = false)
                } else {
                    requestGoogleCredential(filterAuthorized = true)
                        ?: requestGoogleCredential(filterAuthorized = false)
                }
                    ?: error("No Google account was selected")
                if (credential.id.isBlank()) error("Google account identity was empty")
                authorizeDrive()
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    @Suppress("CredentialManagerSignInWithGoogle")
    private suspend fun requestGoogleCredential(filterAuthorized: Boolean): GoogleIdTokenCredential? {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterAuthorized)
            .setServerClientId(BuildConfig.WEB_CLIENT_ID)
            .setAutoSelectEnabled(filterAuthorized)
            .setNonce(randomNonce())
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val result = credentialManager.getCredential(this, request)
            val custom = result.credential as? CustomCredential ?: return null
            if (custom.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) return null
            GoogleIdTokenCredential.createFrom(custom.data)
        } catch (_: NoCredentialException) {
            null
        }
    }

    private fun authorizeDrive() {
        if (driveRequestInProgress) return
        val currentVault = state as? AppState.Vault
        if (currentVault != null && !hasValidatedInternet()) {
            state = currentVault.copy(syncStatus = "Saved offline")
            return
        }
        driveRequestInProgress = true
        val unlockedVault = currentVault
        if (state !is AppState.Vault) state = AppState.Loading
        lifecycleScope.launch {
            var handedOffToSync = false
            try {
                val request = AuthorizationRequest.builder()
                    .setRequestedScopes(listOf(Scope(DriveClient.DRIVE_APPDATA_SCOPE)))
                    .build()
                val result = authorizationClient.authorize(request).await()
                if (result.hasResolution()) {
                    val intentSender = checkNotNull(result.pendingIntent).intentSender
                    authorizationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                } else {
                    handedOffToSync = true
                    onDriveAuthorized(checkNotNull(result.accessToken))
                }
            } catch (error: Exception) {
                if (unlockedVault == null) {
                    showError(error)
                } else {
                    Log.w("CipherVault", "Drive unavailable; keeping local vault open", error)
                    state = unlockedVault.copy(syncStatus = "Saved offline")
                }
            } finally {
                if (!handedOffToSync) driveRequestInProgress = false
            }
        }
    }

    private fun onDriveAuthorized(token: String) {
        driveRequestInProgress = true
        lifecycleScope.launch {
            try {
                val accountId = accountProfileId(repository.driveAccountId(token))
                when (val current = state) {
                    is AppState.Vault -> {
                        val activeAccount = repository.activeAccountId()
                        if (activeAccount != null && activeAccount != accountId) {
                            accessToken = null
                            state = current.copy(syncStatus = "Different account selected")
                        } else {
                            val synced = repository.sync(token)
                            if (repository.hasLegacyVault()) repository.bindLegacyVault(accountId)
                            accessToken = token
                            state = AppState.Vault(synced.credentials, "Synced")
                        }
                    }
                    else -> {
                        repository.selectAccount(accountId)
                        accessToken = token
                        state = if (repository.hasLocalVault()) {
                            BackgroundSyncScheduler.enqueue(applicationContext, accountId)
                            AppState.Locked
                        } else {
                            AppState.Passphrase(repository.hasRemoteVault(token))
                        }
                    }
                }
            } catch (error: Exception) {
                val unlockedVault = state as? AppState.Vault
                if (unlockedVault == null) {
                    showError(error)
                } else {
                    Log.w("CipherVault", "Drive sync unavailable; keeping local vault open", error)
                    state = unlockedVault.copy(syncStatus = "Saved offline")
                }
            } finally {
                driveRequestInProgress = false
            }
        }
    }

    private fun syncUnlockedVault() {
        if (state !is AppState.Vault || !hasValidatedInternet() || driveRequestInProgress) return
        accessToken?.let(::onDriveAuthorized) ?: authorizeDrive()
    }

    private fun hasValidatedInternet(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        return connectivityManager.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    private fun unlockWithBiometrics() {
        try {
            val authenticators = unlockAuthenticators()
            val cipher = repository.biometricKeyStore.createDecryptCipher()
            val prompt = BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authorizedCipher = result.cryptoObject?.cipher ?: return showError(
                            IllegalStateException("Biometric authorization did not unlock the device key"),
                        )
                        lifecycleScope.launch {
                            try {
                                val localKey = repository.biometricKeyStore.unwrapWithAuthorizedCipher(authorizedCipher)
                                repository.biometricKeyStore.migrateIfNeeded(localKey)
                                state = AppState.Vault(repository.openLocal(localKey).credentials, "Stored securely")
                                syncUnlockedVault()
                            } catch (error: Exception) {
                                showError(error)
                            }
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        ) {
                            showError(IllegalStateException(errString.toString()))
                        }
                    }
                },
            )
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock CipherVault")
                .setSubtitle(
                    if (authenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL != 0) {
                        "Use your fingerprint or screen lock"
                    } else {
                        "Confirm your fingerprint to update device security"
                    },
                )
                .setAllowedAuthenticators(authenticators)
                .apply {
                    if (authenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL == 0) {
                        setNegativeButtonText("Cancel")
                    }
                }
                .build()
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        } catch (error: Exception) {
            showError(error)
        }
    }

    private fun createVault(passphrase: String) {
        if (!deviceAuthenticationAvailable()) return
        runVaultAction {
            repository.createVault(passphrase.toCharArray(), checkNotNull(accessToken)).credentials
        }
    }

    private fun restoreVault(passphrase: String) {
        if (!deviceAuthenticationAvailable()) return
        runVaultAction {
            repository.restoreVault(passphrase.toCharArray(), checkNotNull(accessToken)).credentials
        }
    }

    private fun replaceVault(passphrase: String) {
        if (!deviceAuthenticationAvailable()) return
        runVaultAction {
            repository.replaceVault(passphrase.toCharArray(), checkNotNull(accessToken)).credentials
        }
    }

    private fun saveCredential(entry: CredentialEntry) = runVaultUpdate { repository.upsert(entry, accessToken) }

    private fun deleteCredential(id: String) = runVaultUpdate { repository.delete(id, accessToken) }

    private fun runVaultUpdate(action: suspend () -> VaultUpdate) {
        state = AppState.Loading
        lifecycleScope.launch {
            try {
                val update = action()
                state = AppState.Vault(update.vault.credentials, if (update.synced) "Synced" else "Saved offline")
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    private fun runVaultAction(action: suspend () -> List<CredentialEntry>) {
        state = AppState.Loading
        lifecycleScope.launch {
            try {
                state = AppState.Vault(action(), if (accessToken == null) "Saved offline" else "Synced")
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    private fun unlockAuthenticators(): Int =
        if (repository.biometricKeyStore.requiresBiometricMigration() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }

    private fun deviceAuthenticationAvailable(): Boolean {
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }
        if (BiometricManager.from(this).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS) {
            return true
        }
        state = AppState.Error(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "Set up either a secure screen lock or a strong biometric in Android Settings before creating or restoring a vault."
            } else {
                "Enroll a strong fingerprint in Android Settings before creating or restoring a vault on this Android version."
            },
        )
        return false
    }

    private fun showError(error: Throwable) {
        Log.e("CipherVault", "Vault operation failed", error)
        state = if (error is IntegrityException) {
            AppState.Error("Integrity is broken. The encrypted vault was modified or damaged. No affected data was loaded.")
        } else {
            AppState.Error(error.message ?: "Something went wrong")
        }
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING,
        )
    }

}

private sealed interface AppState {
    data object Loading : AppState
    data object Locked : AppState
    data object SignedOut : AppState
    data class Passphrase(
        val remoteVaultExists: Boolean,
        val replacingExisting: Boolean = false,
    ) : AppState
    data class Vault(val credentials: List<CredentialEntry>, val syncStatus: String) : AppState
    data class Error(val message: String) : AppState
}

@Composable
private fun CipherVaultApp(
    state: AppState,
    onGoogleSignIn: () -> Unit,
    onUnlock: () -> Unit,
    onCreateVault: (String) -> Unit,
    onRestoreVault: (String) -> Unit,
    onReplaceVault: (String) -> Unit,
    onBeginVaultReplacement: () -> Unit,
    onSave: (CredentialEntry) -> Unit,
    onDelete: (String) -> Unit,
    onSync: () -> Unit,
    onSwitchAccount: () -> Unit,
    onRetry: () -> Unit,
    availableUpdate: ReleaseMetadata?,
    onOpenUpdate: (ReleaseMetadata) -> Unit,
    onDismissUpdate: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            AppState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            AppState.Locked -> AccessScreen(
                title = "CipherVault is locked",
                action = "Unlock",
                onAction = onUnlock,
                locked = true,
                secondaryAction = "Switch Google account",
                onSecondaryAction = onSwitchAccount,
            )
            AppState.SignedOut -> AccessScreen(
                title = "Your private vault",
                action = "Continue with Google",
                onAction = onGoogleSignIn,
                locked = false,
            )
            is AppState.Passphrase -> PassphraseScreen(
                remoteVaultExists = state.remoteVaultExists,
                replacingExisting = state.replacingExisting,
                onCreate = onCreateVault,
                onRestore = onRestoreVault,
                onReplace = onReplaceVault,
                onBeginReplacement = onBeginVaultReplacement,
            )
            is AppState.Vault -> VaultScreen(state, onSave, onDelete, onSync, onSwitchAccount)
            is AppState.Error -> ErrorScreen(state.message, onRetry)
        }
    }
    availableUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = onDismissUpdate,
            title = { Text("CipherVault ${update.versionName} is available") },
            text = { Text("This release was verified with CipherVault's signing certificate.") },
            confirmButton = {
                TextButton(onClick = { onOpenUpdate(update) }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = onDismissUpdate) { Text("Later") } },
        )
    }
}

@Composable
private fun AccessScreen(
    title: String,
    action: String,
    onAction: () -> Unit,
    locked: Boolean,
    secondaryAction: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(Icons.Default.Lock, null, Modifier.padding(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            if (locked) "Use your fingerprint or device screen lock."
            else "Sign in to open or create your encrypted credential vault.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(action) }
        if (secondaryAction != null && onSecondaryAction != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSecondaryAction) { Text(secondaryAction) }
        }
    }
}

@Composable
private fun PassphraseScreen(
    remoteVaultExists: Boolean,
    replacingExisting: Boolean,
    onCreate: (String) -> Unit,
    onRestore: (String) -> Unit,
    onReplace: (String) -> Unit,
    onBeginReplacement: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var showResetWarning by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            when {
                replacingExisting -> "Create a new vault"
                remoteVaultExists -> "Restore your vault"
                else -> "Protect your vault"
            },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                replacingExisting -> "Choose a new passphrase. Creating this vault permanently deletes the previous vault and all of its credentials."
                remoteVaultExists -> "Enter the vault passphrase used on your other device."
                else -> "Create a memorable passphrase of at least 12 characters. It cannot be recovered."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (replacingExisting) {
            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    "This cannot be undone. The existing encrypted vault will be deleted from Google Drive and this device.",
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Vault passphrase") },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show passphrase")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                when {
                    replacingExisting -> onReplace(passphrase)
                    remoteVaultExists -> onRestore(passphrase)
                    else -> onCreate(passphrase)
                }
            },
            enabled = passphrase.length >= 12,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    replacingExisting -> "Delete old vault and create new"
                    remoteVaultExists -> "Restore vault"
                    else -> "Create vault"
                },
            )
        }
        if (remoteVaultExists) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { showResetWarning = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Forgot passphrase? Set up a new vault", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (showResetWarning) {
        AlertDialog(
            onDismissRequest = { showResetWarning = false },
            title = { Text("Delete the existing vault?") },
            text = {
                Text(
                    "All credentials in the current vault will be permanently deleted from Google Drive and this device. " +
                        "They cannot be recovered without the existing passphrase.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetWarning = false
                        onBeginReplacement()
                    },
                ) {
                    Text("Continue to new vault", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showResetWarning = false }) { Text("Keep existing vault") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultScreen(
    state: AppState.Vault,
    onSave: (CredentialEntry) -> Unit,
    onDelete: (String) -> Unit,
    onSync: () -> Unit,
    onSwitchAccount: () -> Unit,
) {
    var editing by remember { mutableStateOf<CredentialEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CredentialEntry?>(null) }
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    fun copyCredential(label: String, value: String) {
        copySensitiveText(context, label, value)
        coroutineScope.launch { snackbarHost.showSnackbar("$label copied") }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("CipherVault", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${state.credentials.size} ${if (state.credentials.size == 1) "credential" else "credentials"} · ${state.syncStatus}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSync) { Icon(Icons.Default.Refresh, "Sync") }
                    IconButton(onClick = onSwitchAccount) { Icon(Icons.Default.AccountCircle, "Switch account") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            if (state.credentials.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { creating = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Add credential") },
                )
            } else {
                FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "Add credential") }
            }
        },
    ) { padding ->
        if (state.credentials.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.Lock, null, Modifier.padding(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(18.dp))
                Text("No credentials yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Add your first account to the encrypted vault.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 18.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.credentials, key = { it.id }) { entry ->
                    CredentialCard(
                        entry = entry,
                        onCopyUsername = { copyCredential("Username", entry.username) },
                        onCopyPassword = { copyCredential("Password", entry.password) },
                        onEdit = { editing = entry },
                        onDelete = { pendingDelete = entry },
                    )
                }
            }
        }
    }
    if (creating || editing != null) {
        CredentialDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { entry -> creating = false; editing = null; onSave(entry) },
        )
    }
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete credential?") },
            text = { Text("Delete ${entry.title}? This credential will be removed from every synced device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(entry.id)
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CredentialCard(
    entry: CredentialEntry,
    onCopyUsername: () -> Unit,
    onCopyPassword: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var revealPassword by remember(entry.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            entry.title.trim().firstOrNull()?.uppercase() ?: "?",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (entry.website.isNotEmpty()) {
                        Text(entry.website, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            CredentialValueRow(
                label = "Username",
                value = entry.username.ifEmpty { "Not set" },
                onCopy = onCopyUsername,
                copyEnabled = entry.username.isNotEmpty(),
            )
            Spacer(Modifier.height(8.dp))
            CredentialValueRow(
                label = "Password",
                value = if (revealPassword) entry.password else "••••••••",
                onCopy = onCopyPassword,
                trailingAction = {
                    IconButton(onClick = { revealPassword = !revealPassword }) {
                        Icon(
                            if (revealPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            if (revealPassword) "Hide password" else "Show password",
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun CredentialValueRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
    copyEnabled: Boolean = true,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            trailingAction?.invoke()
            IconButton(onClick = onCopy, enabled = copyEnabled) {
                Icon(Icons.Default.ContentCopy, "Copy ${label.lowercase()}")
            }
        }
    }
}

private fun copySensitiveText(context: Context, label: String, value: String) {
    val clip = ClipData.newPlainText(label, value)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(clip)
}

@Composable
private fun CredentialDialog(
    initial: CredentialEntry?,
    onDismiss: () -> Unit,
    onSave: (CredentialEntry) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var website by remember { mutableStateOf(initial?.website.orEmpty()) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }
    var passwordVisible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add credential" else "Edit credential") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true)
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show password")
                        }
                    },
                )
                OutlinedTextField(website, { website = it }, label = { Text("Website") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 2)
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && password.isNotBlank(),
                onClick = {
                    onSave(
                        CredentialEntry(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            title = title.trim(),
                            username = username.trim(),
                            password = password,
                            website = website.trim(),
                            notes = notes.trim(),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Vault unavailable", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun CipherVaultTheme(content: @Composable () -> Unit) {
    val colors = androidx.compose.material3.lightColorScheme(
        primary = Color(0xFF006B5F),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFA7F2E2),
        secondary = Color(0xFF9A4522),
        secondaryContainer = Color(0xFFFFDBCC),
        onSecondaryContainer = Color(0xFF381000),
        background = Color(0xFFF8F7F2),
        surface = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFF0F1EE),
        outlineVariant = Color(0xFFD9DDD8),
        error = Color(0xFFBA1A1A),
    )
    MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography, content = content)
}