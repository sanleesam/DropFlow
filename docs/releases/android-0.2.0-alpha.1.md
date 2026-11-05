# Android 0.2.0-alpha.1 — experimental preview

- Native Titanium/Icy Blue UI unified with desktop across Home, Devices,
  Transfers, Settings, About and Updates. Adaptive device grid and navigation.
- Correct Alpha version labels and independent Semantic Versioning.
- Safer receiving/finalization and a full recipient-approval wait before the
  normal streaming timeout applies.
- Verified Android update checks/downloads with separate Alpha/Beta/Stable
  channels and Android's user-confirmed installation flow.

## One-time reinstall from legacy Beta 1

Legacy `0.1.0-beta.1` was signed with a temporary CI key. This release establishes
DropFlow's permanent Android signing identity, so it **cannot install over Beta
1**. Back up received files outside private app storage, uninstall the old app,
and install this APK manually. Uninstalling removes private settings/data.
Subsequent releases will keep this new identity for normal updates.

Public certificate SHA-256:
`3628d15f4bf1ddac50438bad0ec33c2dc8e37b427f38575f0d63c4c40a08d627`.

## Known limitations

This remains early Alpha software. Keep the app open while sharing. History and
trust are session-only, SAF resume/background receiving are not implemented,
and physical interoperability plus a published same-key update/install still
require acceptance. Android 8/9 storage and installer behavior are not yet
device-verified. Emulator transfer tests are not physical Wi-Fi discovery proof.
See [the current verification and publication gates](../ANDROID_ALPHA1.md).

DropFlow's original code is Apache-2.0; third-party components retain their own
terms. See LICENSE, NOTICE and THIRD_PARTY_NOTICES.md in the repository.
