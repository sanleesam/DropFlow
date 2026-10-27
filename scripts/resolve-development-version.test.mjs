import assert from "node:assert/strict";
import test from "node:test";
import { resolveDevelopmentVersion } from "./resolve-development-version.mjs";

test("an explicit dev.8 tag overrides the workflow run number", () => {
  assert.deepEqual(
    resolveDevelopmentVersion({ ref: "refs/tags/v0.1.0-dev.8", runNumber: "37" }),
    { version: "0.1.0-dev.8", tag: "v0.1.0-dev.8" },
  );
});

test("main pushes and manual branch runs retain automatic build numbering", () => {
  for (const ref of ["refs/heads/main", "refs/heads/codex/discovery-dev8"]) {
    assert.deepEqual(resolveDevelopmentVersion({ ref, runNumber: "38" }), {
      version: "0.1.0-dev.38",
      tag: "v0.1.0-dev.38",
    });
  }
});

test("development tags support other product versions", () => {
  assert.equal(
    resolveDevelopmentVersion({ ref: "refs/tags/v1.2.3-dev.12" }).version,
    "1.2.3-dev.12",
  );
});

test("beta, Android, malformed and noncanonical tags cannot publish Development", () => {
  for (const ref of [
    "refs/tags/v0.1.0-beta.17", "refs/tags/android-v0.1.0-beta.2",
    "refs/tags/v0.1.0-dev.08", "refs/tags/v00.1.0-dev.8",
    "refs/tags/v0.1.0-dev.8-extra", "refs/tags/development", "",
  ]) {
    assert.throws(() => resolveDevelopmentVersion({ ref, runNumber: "38" }));
  }
});

test("automatic builds reject invalid run numbers", () => {
  for (const runNumber of [undefined, "", "0", "01", "-1", "8.1", "8;false"]) {
    assert.throws(() => resolveDevelopmentVersion({ ref: "refs/heads/main", runNumber }));
  }
});
