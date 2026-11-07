# Android 0.2.0-alpha.2 — experimental preview

**Already on Alpha 1?** Update in place using Settings → Updates, or install
this APK over Alpha 1. The signing key is unchanged. If you're still on legacy
Beta 1, follow the [one-time reinstall instructions](https://github.com/sanleesam/DropFlow/releases/tag/android-v0.2.0-alpha.1).

## What's improved

- Received folders keep their structure. Files with matching names are saved
  separately, and unexpected paths or invalid file data are rejected.
- Transfers keeps the last 100 results on the device, even after restart.
  Clearing history doesn't delete received files.
- One transfer at a time keeps approval prompts, progress, and cancellation
  tied to the right transfer. A busy receiver no longer replaces the prompt.
- Incoming transfers ask for approval by default. The old trusted-device
  bypass is gone because DropFlow can't verify a sender's claimed identity.
- Sending shows when it's waiting for approval and when it's finishing.
  Completion still requires the recipient's final acknowledgement.
- Discovery ignores stale results after a rescan and can use alternate
  advertised addresses on newer Android versions. Cancelling one update
  download no longer interferes with a newer check.
- About and Updates show the version Android actually installed.

## Still Alpha

Keep DropFlow open while sharing. Background receiving, transfer notifications,
and resuming interrupted SAF transfers aren't supported. Physical Wi-Fi
interoperability and Android 8/9 device behavior still need testing. Transfers
are not encrypted and device identities are not authenticated.

See [Alpha 2 verification](https://github.com/sanleesam/DropFlow/blob/android-v0.2.0-alpha.2/docs/ANDROID_ALPHA2.md)
for checks and remaining limits. Desktop releases and channels are unchanged.
