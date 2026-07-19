# Production Release Guide

CipherVault is distributed as a directly installable APK through GitHub Releases. Google Play enrollment is not required.

## Permanent release identity

The production application ID is `com.securemirage.ciphervault`. Every production APK must be signed with the same permanent release key. Losing that key prevents Android from installing future builds as updates; compromising it requires treating the release channel as compromised.

Generate the key on an offline or trusted workstation, outside the repository:

```powershell
keytool -genkeypair -v `
  -keystore C:\secure\ciphervault-release.p12 `
  -storetype PKCS12 `
  -alias ciphervault-release `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000
```

Record its fingerprints without exposing passwords:

```powershell
keytool -list -v -keystore C:\secure\ciphervault-release.p12 -alias ciphervault-release
```

Keep at least two encrypted offline backups in separate physical locations. Store the passwords separately from the keystore. Never commit the keystore, copy it into the workspace, attach it to an issue, or reuse it for debug builds.

## Production Google OAuth

Use a dedicated Google Cloud production project:

1. Enable Google Drive API.
2. Configure Google Auth Platform for an external audience and publish it to production.
3. Add only `https://www.googleapis.com/auth/drive.appdata`.
4. Publish links to `PRIVACY.md`, the project home page, and support information as required by Google.
5. Create an Android OAuth client for `com.securemirage.ciphervault` using the permanent release certificate's SHA-1.
6. Create a Web OAuth client and store its client ID as `PROD_WEB_CLIENT_ID` in GitHub's `production` environment.

The Web client ID is an identifier. OAuth client secrets are not used by the Android app and must not be placed in the APK or repository.

## GitHub production environment

The `production` environment requires approval and accepts deployments only from `main` or version tags. Configure:

- Variable: `PROD_WEB_CLIENT_ID`
- Secret: `ANDROID_KEYSTORE_BASE64`
- Secret: `ANDROID_KEYSTORE_PASSWORD`
- Secret: `ANDROID_KEY_ALIAS`
- Secret: `ANDROID_KEY_PASSWORD`

Encode the permanent keystore without printing it:

```powershell
$encoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\secure\ciphervault-release.p12"))
$encoded | gh secret set ANDROID_KEYSTORE_BASE64 --repo Manikantsingh/CipherVault --env production
```

Enter passwords directly into the secure prompts from `gh secret set`; never paste them into chat or command history.

## Publishing

1. Update `versionCode` and `versionName` in `app/build.gradle.kts` through a reviewed pull request.
2. Merge only after CI passes.
3. Create a tag exactly matching `v<versionName>`, for example `v1.1.0`.
4. Push the tag and approve the `production` environment deployment.
5. Confirm release tests, lint, APK/AAB signing, signature verification, metadata signing, and GitHub Release publication pass.
6. Install the APK over the previous production version and verify vault unlock, Google authorization, Drive sync, and update detection.

The workflow publishes the APK, its SHA-256 checksum, and metadata signed with the same permanent key. Manual workflow runs validate artifacts but do not create a public GitHub Release.
