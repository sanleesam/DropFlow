# Licensing scope and third-party materials

DropFlow's original source code and documentation are licensed under
[Apache-2.0](LICENSE). This includes the native Android implementation; Android
does not bundle the desktop illustration pack or GSAP.

Dependencies retain their own licenses. This repository's license does not
replace them, and Apache-2.0 does not grant rights to third-party trademarks.

## Desktop notices

- Lucide: ISC, with MIT notices for Feather-derived icons. The applicable texts
  are bundled in [third-party-licenses.txt](apps/desktop/public/third-party-licenses.txt).
- GSAP 3.15: [GSAP Standard License](https://gsap.com/community/standard-license/),
  not Apache-2.0. It has use restrictions and must not be represented as an
  OSI-approved open-source dependency. The existing animation implementation
  is retained; opening DropFlow's code does not relicense GSAP.
- React, Tauri, Rust crates and other packages retain their respective upstream
  terms. Exact dependency versions are recorded in package lockfiles and
  `Cargo.lock`; their license files/notices must be retained when redistributing.

## Artwork and branding

The existing desktop device/file illustrations in
`apps/desktop/src/assets/pack/` have no recorded upstream source or license.
They are **excluded from the Apache-2.0 grant**. Their redistribution permission
has not been verified; do not assume the public repository grants permission to
reuse them. Record the source and applicable license or replace these assets
with cleared artwork before claiming a fully cleared desktop distribution.

The current DropFlow logo was supplied by the maintainer and prepared for use
in the app. No independent audit of external artwork ownership has been made.
Code licensing does not grant a right to imply official DropFlow endorsement.
Forks should use a distinct product name/icon when distributed as a different
product. Truthful attribution is welcome.

## Android dependencies

AndroidX/Compose, Kotlin and kotlinx.coroutines use their upstream Apache-2.0
terms. Build/test-only tools are not automatically part of the shipped APK.
The dependency declarations and Gradle version catalog identify the exact
modules. This file is a scope/attribution record, not a claim that every
transitive dependency or artwork provenance has received a legal audit.
