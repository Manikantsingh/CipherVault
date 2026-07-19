# CipherVault

CipherVault is a native Kotlin credential manager for Android. It uses Jetpack Compose, Credential Manager Sign in with Google, device-authentication-protected Android Keystore keys, authenticated encryption, and Google Drive's hidden `appDataFolder`.

## Features

- Google account sign-up and sign-in through Android Credential Manager.
- Screen-lock or strong-fingerprint unlock backed by a non-exportable Android Keystore RSA private key on Android 11 and newer.
- AES-256-GCM encryption for every credential operation.
- Cross-device recovery using a user-owned vault passphrase.
- Forgotten-passphrase replacement with an explicit irreversible-deletion warning.
- Per-credential deletion confirmation to prevent accidental removal.
- Automatic Drive sync when the vault opens and after add, edit, or delete operations.
- Durable offline edits that survive app or device restarts and upload automatically when validated internet returns.
- WorkManager background upload of pending encrypted operations while the app UI is closed.
- Account-scoped local vault profiles that let users switch Google accounts without mixing wrapped keys or encrypted operation histories.
- Deterministic multi-device conflict resolution with durable deletion tombstones.
- An explicit integrity error when authenticated decryption fails.
- Signed update notifications backed by the installed APK's own release certificate.

## Run the app

### Prerequisites

- Android Studio with JDK 17.
- Android SDK 35.
- An Android 8.0 or newer device or emulator with Google Play services.
- Android 11 or newer with either a secure screen lock or a Class 3 biometric, such as a strong fingerprint sensor. Android 10 and older require a strong biometric.

### Google Cloud setup

1. Create a project in [Google Cloud Console](https://console.cloud.google.com/).
2. Enable the Google Drive API.
3. Configure the Google Auth Platform consent screen and add the non-sensitive `https://www.googleapis.com/auth/drive.appdata` scope.
4. Create an Android OAuth client for package `com.securemirage.ciphervault` and add the SHA-1 of the signing certificate.
5. Create a Web OAuth client. Credential Manager requires this Web client ID as its server client ID.
6. Add the development Web client ID to the user-level Gradle properties file (`~/.gradle/gradle.properties` on macOS/Linux or `%USERPROFILE%\.gradle\gradle.properties` on Windows):

```properties
DEV_WEB_CLIENT_ID=000000000000-development.apps.googleusercontent.com
```

CipherVault has separate development and production build configurations:

| Build type | Application ID | App label | OAuth property |
| --- | --- | --- | --- |
| `debug` | `com.securemirage.ciphervault.debug` | CipherVault Dev | `DEV_WEB_CLIENT_ID` |
| `release` | `com.securemirage.ciphervault` | CipherVault | `PROD_WEB_CLIENT_ID` |

Register each application ID and its signing-certificate SHA-1 as a separate Android OAuth client in the corresponding Google Cloud project. The debug build temporarily accepts the legacy `WEB_CLIENT_ID` property as a fallback. Release builds require an explicit production Web OAuth client ID:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat bundleRelease '-PPROD_WEB_CLIENT_ID=000000000000-production.apps.googleusercontent.com'
```

The production Web client ID is an identifier, not a secret. Release signing keys and passwords are secrets and must never be committed.

### Production distribution

Production APKs are distributed through [GitHub Releases](https://github.com/Manikantsingh/CipherVault/releases). Google Play enrollment is not required. The permanent release key must be generated outside the repository and kept in encrypted offline backups; see [docs/RELEASING.md](docs/RELEASING.md) before configuring a release.

The `Signed release` workflow runs manually or when a tag beginning with `v` is pushed. The GitHub `production` environment requires approval and contains this variable:

- `PROD_WEB_CLIENT_ID`: production Web OAuth client ID.

Add these encrypted environment secrets:

- `ANDROID_KEYSTORE_BASE64`: Base64-encoded permanent release keystore.
- `ANDROID_KEYSTORE_PASSWORD`: release keystore password.
- `ANDROID_KEY_ALIAS`: release key alias.
- `ANDROID_KEY_PASSWORD`: release key password.

On Windows, encode the release keystore without line wrapping and send it directly to GitHub CLI without printing it:

```powershell
$keystoreBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\secure\ciphervault-release.p12"))
$keystoreBase64 | gh secret set ANDROID_KEYSTORE_BASE64 --env production
gh secret set ANDROID_KEYSTORE_PASSWORD --env production
gh secret set ANDROID_KEY_ALIAS --env production
gh secret set ANDROID_KEY_PASSWORD --env production
gh variable set PROD_WEB_CLIENT_ID --env production --body "000000000000-production.apps.googleusercontent.com"
```

The last three secret commands prompt securely for their values. The workflow restores the keystore only in the runner's temporary directory, runs release tests and lint, signs and verifies an APK and Android App Bundle, generates an APK SHA-256 checksum, and deletes the temporary keystore even after failure.

For a version tag, the workflow also signs `release-metadata.json` with the APK key and publishes the APK, checksum, metadata, and detached signature as a GitHub Release. Production builds use GitHub's API only to locate those two metadata assets; the app parses and displays an update only after the raw metadata signature verifies against its own installed signing certificate. Manual workflow runs upload private validation artifacts but do not publish a release.

Trigger a release manually from **Actions → Signed release → Run workflow**, or create a version tag after updating `versionCode` and `versionName`:

```powershell
git tag v1.0.0
git push origin v1.0.0
```

Open the project in Android Studio, let Gradle sync, then run the `app` configuration. From a terminal with JDK 17 and the Android SDK configured:

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

Never commit OAuth secrets or signing keys. A Web client ID is an identifier rather than a secret, but keeping environment-specific configuration out of source avoids accidental production misconfiguration.

Read the public [privacy policy](PRIVACY.md), [security policy](SECURITY.md), and [F-Droid evaluation](docs/FDROID.md) before publishing.

## How encryption works

On first setup, the app generates a random 256-bit vault key. Credentials are represented as immutable operations and each operation is independently encrypted with AES-256-GCM using a fresh 96-bit nonce. The vault ID and format version are authenticated as additional data.

The vault key is wrapped in two ways:

- **Cross-device envelope:** PBKDF2-HMAC-SHA256 derives a wrapping key from the user's passphrase and a random salt. The derived key wraps the vault key with AES-GCM. Only this encrypted envelope is uploaded to Drive.
- **Device envelope:** an Android Keystore RSA public key wraps the vault key locally. Its private key is non-exportable and requires either the device screen lock or a strong biometric for each decrypt operation on Android 11 and newer.

Google Drive receives ciphertext, nonces, salts, version numbers, and opaque identifiers. It does not receive the vault key, passphrase, or credential plaintext. A Google account cannot safely replace the vault passphrase: deriving a key solely from Google identity would either be guessable or require Google/a backend to hold key-recovery material.

## Multi-device synchronization

Drive stores each mutation as a uniquely named immutable file in `appDataFolder`. Devices never overwrite a shared credential file, so simultaneous uploads cannot cause last-writer data loss.

Each mutation is encrypted and flushed to an atomic local operation file before Drive upload is attempted. If Drive is unavailable, the local vault remains usable and displays `Saved offline`. Pending files are loaded after process or device restart and retried when validated connectivity returns or the vault is next opened online.

Pending uploads are also scheduled as unique, network-constrained WorkManager jobs. WorkManager persists these jobs across process death and device restart. The worker silently requests a short-lived Drive token, verifies its opaque account identity against the local profile, and uploads only already-encrypted operation files. It never stores an access token or unwraps the vault key. If Google requires renewed user consent, background work stops safely and foreground authorization is required.

Background work intentionally does not decrypt or merge newly downloaded operations because Android Keystore access requires fingerprint or screen-lock authentication. Full inbound synchronization and conflict resolution therefore complete at the next authenticated vault unlock.

Each authorized Drive account is identified by its stable opaque Drive permission ID, hashed before local storage. Its wrapped vault key is stored in a separate account-scoped preferences file, while encrypted operations remain isolated under that profile's vault ID. Switching accounts locks and clears the current in-memory session before selecting the other profile. A Drive token whose account does not match the unlocked profile is never allowed to upload or download operations.

Each operation contains a Lamport clock, device ID, operation ID, credential ID, and either an upsert value or deletion tombstone. Devices download all unseen operations and choose a winner per credential by this total order:

1. Highest logical clock.
2. Lexicographically highest device ID.
3. Lexicographically highest operation ID.

This produces the same result regardless of download order. Tombstones remain in the log, preventing an older device from resurrecting deleted credentials.

## Security boundaries

- AES-GCM authentication detects changed ciphertext, changed metadata, use of the wrong key, and most accidental corruption. The app refuses to load the affected vault and displays an integrity warning.
- A Drive-only client cannot prove that Google Drive returned the newest complete history. An attacker capable of deleting all newer files or rolling the entire folder back can remove data without forging an AES-GCM tag. Strong rollback detection requires a trusted append-only server, transparency log, or separately stored signed checkpoint.
- The current app uses the Drive OAuth grant as the data-access boundary. Before treating the Google ID token as an application account session in a production service, send it to a backend and verify its signature, audience, issuer, expiry, and nonce as required by Google's Sign in with Google guidance.
- Plaintext exists in app memory while the vault is unlocked. The app disables Android backup, stores only encrypted operation files on disk, and zeroes the in-memory vault key when explicitly locked by the repository.
- Losing both the passphrase and every enrolled device makes the vault unrecoverable by design.
- A user who cannot recover the existing vault can replace it. CipherVault uploads the new encrypted key envelope, deletes the old CipherVault key and operation files from Drive, clears local encrypted operations and device keys, and starts an empty vault. The old credentials cannot be recovered afterward.

## Project layout

- `core/`: credential model, authenticated encryption, and deterministic merge engine.
- `security/`: biometric Android Keystore key wrapper.
- `sync/`: minimal Google Drive v3 `appDataFolder` REST client.
- `data/`: encrypted local cache and sync orchestration.
- `MainActivity.kt`: Google sign-in, Drive authorization, biometric prompt, and Compose UI.

The unit tests cover authenticated round trips, tamper detection, cross-device key recovery, deterministic concurrent updates, and tombstone behavior.