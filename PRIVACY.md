# CipherVault Privacy Policy

Effective: July 19, 2026

CipherVault is an open-source Android credential manager. The CipherVault project does not operate an application backend, advertising service, analytics service, or telemetry pipeline.

## Data handled by the app

CipherVault handles credentials that you enter, an encrypted vault key envelope, encrypted operation records, an opaque identifier for the selected Google Drive account, and short-lived Google authorization tokens. Credential plaintext and the vault passphrase are used in app memory while needed and are not sent to the CipherVault project.

Vault records are encrypted on your device before they are stored locally or uploaded to your Google Drive `appDataFolder`. Google Drive receives encrypted records and associated cryptographic metadata. Google receives authentication and authorization information as part of Google Sign-In and Drive API use.

## Update checks

Production builds contact GitHub's public API when the app starts to locate the latest CipherVault release metadata. GitHub may process network information such as your IP address and the app's `CipherVault-Android` user-agent under GitHub's own privacy terms. CipherVault does not attach vault data, Google account data, or a persistent device identifier to this request. Release metadata is accepted only after verification with CipherVault's application-signing certificate.

## Data collection and sharing

The CipherVault project does not collect, sell, rent, or share your vault contents. The app contains no advertising or third-party analytics SDK. Data sent to Google or GitHub is governed by those providers' terms and privacy policies.

## Retention and deletion

Encrypted local data remains on your device until you delete or replace the vault, remove the app's data, or uninstall the app. Encrypted synchronized data remains in your Google Drive app-data storage until CipherVault deletes it through a vault replacement or you revoke access and remove the application's stored data through Google account controls.

## Security

CipherVault uses authenticated encryption and Android Keystore protections, but no software can guarantee absolute security. Keep your device, vault passphrase, and installed application current. See [SECURITY.md](SECURITY.md) for vulnerability reporting and supported-version information.

## Changes and questions

Material changes to this policy will be published in this repository with an updated effective date. Privacy questions may be opened through the repository's public issue tracker, but never include credentials, passphrases, tokens, recovery material, or other sensitive information.
