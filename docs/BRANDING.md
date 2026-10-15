# Desktop branding

The desktop mark is the maintainer's silver/icy-blue interlocking file-transfer
artwork, prepared as a transparent PNG using the built-in image-editing tool.
The supplied upload had an opaque black background; the maintainer authorized
generating the transparent rendition. This is a generated rendition, not a
pixel-identical alpha-only conversion of the upload.

## Assets and consumers

- `apps/desktop/src/assets/logo.png`: transparent 1254×1254 master, with padding
  suitable for the Dock. Also used by the shared `BrandMark` React component.
- `apps/desktop/public/logo.png`: matching browser/favicon asset.
- `apps/desktop/src-tauri/icons/`: desktop PNG sizes, Windows ICO and macOS ICNS.
- Sidebar and Settings About use the same `BrandMark`; neither adds a solid
  square background behind the artwork.
- Android/iOS assets and release channels are unchanged by this desktop update.

## Regeneration

From `apps/desktop`, run `pnpm tauri icon src/assets/logo.png --output <temporary-directory>`.
Copy only the generated top-level desktop files into `src-tauri/icons/`; do not
incidentally replace the nested mobile assets. Preserve RGBA throughout.

Verify transparent corners in all PNGs, every ICO size and every representation
extracted from ICNS with `iconutil`. The source master must not be flattened
onto black. Native bundle validation should compare its embedded `icon.icns`
with the checked-in icon and inspect the in-app mark.

## Generation brief

Built-in image tool, transparent output. Extract the supplied silver and blue
folded-paper bidirectional transfer logo; preserve the interlocking file shapes,
arrow cutouts, folded corners, proportions and silver/icy-blue shading. Make
the outer background, arrow holes and space between shapes transparent. Center
the mark at approximately 80% of a square canvas with transparent padding.
No background tile, extra elements, text, outline or external shadow.

The sidebar and About rendition was visually checked on both light and dark
surfaces in Brave. Frontend type/build/UI checks and desktop icon alpha checks
passed. Native artifact checks are recorded with the Development publication.
