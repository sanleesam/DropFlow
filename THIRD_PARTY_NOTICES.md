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

The unused legacy device/file PNG pack was removed from the current source tree
and desktop build in the safety patch after Beta 19. The active UI uses original
vector device artwork and Lucide icons; its appearance is unchanged by removal.
Older immutable tags may still contain that pack. Its provenance remains
unverified and it is not relicensed by Apache-2.0; no reuse permission for those
historical assets should be inferred from their presence in Git history.

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
