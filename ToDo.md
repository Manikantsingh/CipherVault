# CipherVault Version 2 To-Do

These items are candidates for CipherVault version 2. They are not required for the current version 1 feature set.

## Vault Security

- [ ] Automatically lock the vault after a configurable period of inactivity.
- [ ] Automatically lock the vault after extended backgrounding or device lock.
- [ ] Add a manual **Lock vault** action.
- [ ] Add `FLAG_SECURE` protection against screenshots and recent-app previews.
- [ ] Clear sensitive clipboard entries after a short interval where Android permits it.
- [ ] Add passphrase rotation without deleting or recreating the vault.
- [ ] Complete an independent review of encryption, Android Keystore use, account binding, and synchronization conflict handling.

## Synchronization and Accounts

- [ ] Show the number of pending changes, last successful sync time, and background-sync failures.
- [ ] Notify the user when background Drive authorization requires interaction.
- [ ] Handle removed Google accounts, revoked Drive access, and expired authorization explicitly.
- [ ] Add an account-profile management screen for viewing and safely removing local profiles.
- [ ] Preserve the security boundary that inbound decryption and conflict merging require device authentication.
- [ ] Add encrypted operation-log compaction or checkpointing to prevent indefinite storage growth.

## Credential Experience

- [ ] Add credential search and filtering for larger vaults.
- [ ] Add optional Undo after confirmed credential deletion.
- [ ] Evaluate encrypted user-managed backup and export workflows.

## Automated Testing

- [ ] Add automated tests for offline credential persistence.
- [ ] Add automated tests for background synchronization and retry behavior.
- [ ] Add automated tests for account isolation and account switching.
- [ ] Add automated tests for app process termination and device restart recovery.
- [ ] Add automated tests for interrupted atomic operation-file writes.
- [ ] Add automated tests for offline deletion tombstones.
- [ ] Add performance tests with hundreds or thousands of credential operations.

## Device and Accessibility Testing

- [ ] Test physical devices across all supported Android versions.
- [ ] Test PIN, password, pattern, and supported biometric implementations.
- [ ] Test TalkBack, touch targets, contrast, and larger font settings.
- [ ] Test small screens, tablets, rotation, and process recreation.

## Engineering and Release

- [ ] Add CI for unit tests, Android lint, dependency scanning, and debug builds.
- [x] Add a signed release-build pipeline.
- [ ] Configure production OAuth clients and release signing.
- [ ] Define a crash-reporting and telemetry strategy that never records credentials, passphrases, tokens, or decrypted vault data.
- [ ] Run Android lint and dependency/security scans as release gates.
- [ ] Create production launcher icons, screenshots, store listing, and release assets.
- [ ] Publish a privacy policy and complete Google Play Data Safety declarations.
