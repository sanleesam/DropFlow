import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parseVersion, developmentVersion, syncVersion, checkVersions } from "./desktop-version.mjs";

test("release grammar rejects noncanonical and malformed versions", () => {
  for (const v of ["0.1.1-beta.18", "0.2.0-alpha.1", "1.0.0-rc.1", "1.0.0"]) assert.ok(parseVersion(v));
  for (const v of ["01.2.0", "0.1.1-beta.01", "v0.1.1", "0.1", "1.0.0-beta", "1.0.0+sha"]) assert.throws(() => parseVersion(v));
});
test("Development derives its core version from the release target", () => {
  assert.equal(developmentVersion("0.1.1-beta.18", 8), "0.1.1-dev.8");
  assert.equal(developmentVersion("2.4.0", 100), "2.4.0-dev.100");
  assert.throws(() => developmentVersion("1.0.0", "08"));
});
test("sync updates all four desktop manifests and detects drift", () => {
  const root = mkdtempSync(join(tmpdir(), "dropflow-version-"));
  try {
    const desktop = join(root, "apps/desktop");
    mkdirSync(join(desktop, "src-tauri"), { recursive: true });
    writeFileSync(join(desktop, "package.json"), JSON.stringify({version: "0.1.0-beta.17"}));
    writeFileSync(join(desktop, "src-tauri/tauri.conf.json"), JSON.stringify({version: "0.1.0-beta.17"}));
    writeFileSync(join(desktop, "src-tauri/Cargo.toml"), '[package]\nname = "Dropflow"\nversion = "0.1.0-beta.17"\n');
    writeFileSync(join(desktop, "src-tauri/Cargo.lock"), 'version = 4\n[[package]]\nname = "Dropflow"\nversion = "0.1.0-beta.17"\n');
    assert.equal(syncVersion("0.1.1-beta.18", root), "0.1.1-beta.18");
    assert.equal(checkVersions(root), "0.1.1-beta.18");
    assert.ok(readFileSync(join(desktop, "src-tauri/Cargo.lock"), "utf8").startsWith("version = 4"));
    writeFileSync(join(desktop, "package.json"), JSON.stringify({version:"0.9.0"}));
    assert.throws(() => checkVersions(root), /drift/);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
