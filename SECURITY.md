# CipherVault Security Policy

## Supported versions

Security fixes are provided for the latest production release published through GitHub Releases. Older versions may not receive fixes and should be upgraded.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting flow under **Security → Advisories → Report a vulnerability** for this repository. Do not open a public issue for a suspected vulnerability and do not include real credentials, passphrases, OAuth tokens, signing keys, or decrypted vault data in any report.

Include the affected version, Android version, reproduction steps using synthetic data, security impact, and any suggested mitigation. Reports will be acknowledged as capacity permits; no fixed response or remediation deadline is guaranteed.

## Release authenticity

Production APKs are signed with CipherVault's permanent release key. Each GitHub Release includes:

- A signed APK.
- A SHA-256 checksum for that APK.
- `release-metadata.json` containing the version, download URL, and checksum.
- `release-metadata.json.sig`, signed by the same key as the APK.

The app verifies update metadata against its own installed signing certificate before displaying an update notification. A checksum detects accidental corruption but is not, by itself, proof of authenticity.

## Scope

Relevant reports include authentication or authorization bypasses, cryptographic misuse, plaintext persistence, account-boundary failures, unsafe synchronization behavior, release-signing compromise, and update-verification bypasses. General feature requests belong in the public issue tracker.
