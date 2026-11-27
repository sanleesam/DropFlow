# DropFlow 0.2.0-beta.22 — new interface and a faster transfer engine

This is a **Beta**, not a Stable release. It still exchanges files with
0.1.2 desktop builds and with Android 0.2.0-alpha builds.

> Draft. Publish only after the hardware checks in `docs/RELEASE_PLAN.md`
> (0.2 gate) are done, and replace this note with the measured results.

## New

- **Liquid Glass interface.** Floating sidebar, glass toolbars and sheets,
  light, dark and automatic themes, and a choice of Liquid, Frosted or Solid
  glass in Settings → Appearance.
- **Faster transfers of many files.** With another 0.2 device, files stream
  one after another without a pause between them.
- **Interrupted transfers continue.** If the connection drops, send the same
  files again and only the missing part is sent. Received parts are kept for
  three days.
- **No file-count limit.** A transfer used to fail above roughly 400 files.
- **Clearer status.** "Waiting for … to accept", "Saving", who cancelled, and
  why a transfer stopped (for example, the other device is out of storage).

## Fixed

- Sending two files with the same name from different folders no longer fails.
- A failed send was reported twice; cancelling your own transfer raised an
  error.
- The sender's folder paths were included in the transfer request. They are
  no longer sent.
- Sheets and menus on Windows lost their blur and let the window show through.
- The Files table was cut off at the smallest window size.
- Progress could read 100% before the last bytes had moved.

## Unchanged, and still true

Transfers are **not encrypted**, and a device's name is not verified, so
every transfer asks before it is accepted unless you turn that off. Encrypted
connections and pairing are planned for 0.3.

## If something goes wrong

Settings → Transfers → **Faster transfers** off makes this build use the
original exchange with every device.
