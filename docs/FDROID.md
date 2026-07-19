# F-Droid Evaluation

F-Droid distribution is deferred rather than assumed compatible.

The current CipherVault build depends on Google Credential Manager, Google Play services authorization, and Google Drive `appDataFolder`. Those dependencies limit operation on Google-free devices and may require F-Droid anti-feature declarations or prevent acceptance into the official repository.

A future evaluation should determine whether to:

- Add a Google-free product flavor.
- Replace Google identity and Drive synchronization with optional open protocols.
- Make release builds reproducible from source without private build inputs.
- Supply Fastlane metadata and F-Droid build recipes.
- Document any non-free network-service or dependency anti-features accurately.

Until that work is complete, GitHub Releases is the supported production distribution channel.
