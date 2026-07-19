package com.securemirage.ciphervault.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.KeyStore
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

class BiometricKeyStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val profilePreferences = applicationContext.getSharedPreferences(PROFILE_PREFERENCES, Context.MODE_PRIVATE)
    private val legacyPreferences = applicationContext.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    private var accountId: String? = profilePreferences.getString(ACTIVE_ACCOUNT, null)

    fun hasWrappedVaultKey(): Boolean = activePreferences()?.contains(WRAPPED_KEY) == true &&
        (keyStore.containsAlias(KEY_ALIAS) || keyStore.containsAlias(LEGACY_KEY_ALIAS))

    fun hasAnyWrappedVaultKey(): Boolean = hasWrappedVaultKey() ||
        legacyPreferences.contains(WRAPPED_KEY) ||
        profilePreferences.getStringSet(ACCOUNTS, emptySet()).orEmpty().any { hasWrappedVaultKey(it) }

    fun hasWrappedVaultKey(accountId: String): Boolean = preferences(accountId).contains(WRAPPED_KEY) &&
        (keyStore.containsAlias(KEY_ALIAS) || keyStore.containsAlias(LEGACY_KEY_ALIAS))

    fun activeAccountId(): String? = accountId

    fun activeVaultId(): String? = activePreferences()?.getString(VAULT_ID, null)

    fun selectAccount(accountId: String) {
        this.accountId = accountId
        profilePreferences.edit().putString(ACTIVE_ACCOUNT, accountId).apply()
    }

    fun bindLegacyVault(accountId: String) {
        if (!legacyPreferences.contains(WRAPPED_KEY)) return
        val destination = preferences(accountId)
        check(!destination.contains(WRAPPED_KEY)) { "A local vault already exists for this Google account" }
        destination.edit()
            .putString(WRAPPED_KEY, legacyPreferences.getString(WRAPPED_KEY, null))
            .putString(VAULT_ID, legacyPreferences.getString(VAULT_ID, null))
            .commit()
        legacyPreferences.edit().clear().commit()
        registerAccount(accountId)
        selectAccount(accountId)
    }

    fun selectLegacyVault() {
        accountId = null
        profilePreferences.edit().remove(ACTIVE_ACCOUNT).apply()
    }

    fun hasLegacyVault(): Boolean = legacyPreferences.contains(WRAPPED_KEY)

    fun requiresBiometricMigration(): Boolean =
        !keyStore.containsAlias(KEY_ALIAS) && keyStore.containsAlias(LEGACY_KEY_ALIAS)

    fun wrapAndStore(vaultKey: ByteArray, vaultId: String) {
        val selectedAccount = checkNotNull(accountId) { "Select a Google account before storing a vault" }
        val publicKey = ensureKeyPair().certificate.publicKey
        val unrestrictedPublicKey = KeyFactory.getInstance(publicKey.algorithm)
            .generatePublic(X509EncodedKeySpec(publicKey.encoded))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, unrestrictedPublicKey, OAEP_PARAMETERS)
        val wrapped = cipher.doFinal(vaultKey)
        preferences(selectedAccount).edit()
            .putString(WRAPPED_KEY, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .putString(VAULT_ID, vaultId)
            .commit()
        registerAccount(selectedAccount)
    }

    fun createDecryptCipher(): Cipher {
        val alias = if (keyStore.containsAlias(KEY_ALIAS)) KEY_ALIAS else LEGACY_KEY_ALIAS
        val privateKey = keyStore.getKey(alias, null)
            ?: error("No device key exists for this vault")
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, privateKey, OAEP_PARAMETERS)
        }
    }

    fun unwrapWithAuthorizedCipher(cipher: Cipher): LocalVaultKey {
        val preferences = activePreferences() ?: error("No vault profile is selected")
        val wrapped = preferences.getString(WRAPPED_KEY, null)
            ?: error("No wrapped vault key is stored")
        val vaultId = preferences.getString(VAULT_ID, null)
            ?: error("No vault identifier is stored")
        return LocalVaultKey(
            vaultId = vaultId,
            key = cipher.doFinal(Base64.decode(wrapped, Base64.NO_WRAP)),
        )
    }

    fun clear() {
        val selectedAccount = checkNotNull(accountId) { "No Google account is selected" }
        preferences(selectedAccount).edit().clear().commit()
        val accounts = profilePreferences.getStringSet(ACCOUNTS, emptySet()).orEmpty() - selectedAccount
        profilePreferences.edit()
            .putStringSet(ACCOUNTS, accounts)
            .commit()
    }

    fun migrateIfNeeded(localVaultKey: LocalVaultKey) {
        if (!requiresBiometricMigration()) return
        wrapAndStore(localVaultKey.key, localVaultKey.vaultId)
        keyStore.deleteEntry(LEGACY_KEY_ALIAS)
    }

    private fun ensureKeyPair(): KeyStore.PrivateKeyEntry {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return it }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(2048)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            spec.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            spec.setInvalidatedByBiometricEnrollment(false)
        } else {
            spec.setInvalidatedByBiometricEnrollment(true)
        }
        generator.initialize(spec.build())
        generator.generateKeyPair()
        return keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
    }

    private fun activePreferences() = accountId?.let(::preferences) ?: legacyPreferences.takeIf {
        it.contains(WRAPPED_KEY)
    }

    private fun preferences(accountId: String) =
        applicationContext.getSharedPreferences("$ACCOUNT_PREFERENCES_PREFIX$accountId", Context.MODE_PRIVATE)

    private fun registerAccount(accountId: String) {
        val accounts = profilePreferences.getStringSet(ACCOUNTS, emptySet()).orEmpty() + accountId
        profilePreferences.edit().putStringSet(ACCOUNTS, accounts).apply()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ciphervault.device-auth.rsa.v3"
        const val LEGACY_KEY_ALIAS = "ciphervault.biometric.rsa.v2"
        const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        val OAEP_PARAMETERS = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA1,
            PSource.PSpecified.DEFAULT,
        )
        const val LEGACY_PREFERENCES = "device_vault"
        const val PROFILE_PREFERENCES = "vault_profiles"
        const val ACCOUNT_PREFERENCES_PREFIX = "device_vault_"
        const val ACTIVE_ACCOUNT = "active_account"
        const val ACCOUNTS = "accounts"
        const val WRAPPED_KEY = "wrapped_key"
        const val VAULT_ID = "vault_id"
    }
}

data class LocalVaultKey(val vaultId: String, val key: ByteArray)