import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const workflow = readFileSync(new URL("../.github/workflows/release-development.yml", import.meta.url), "utf8");
test("Development stages versioned installers before updating the channel", () => {
  assert.match(workflow, /tagName: \$\{\{ env\.DEV_TAG \}\}/);
  assert.doesNotMatch(workflow, /tagName: ['"]?development/);
  assert.match(workflow, /releaseCommitish: \$\{\{ github\.sha \}\}/);
  assert.match(workflow, /releaseDraft: true/);
  assert.match(workflow, /needs: \[resolve-version, publish-development\]/);
  for (const step of ['gh release upload "$DEV_TAG" latest.json', 'gh release edit "$DEV_TAG" --draft=false', 'gh release upload development latest.json']) assert.ok(workflow.includes(step));
  assert.ok(workflow.indexOf('gh release edit "$DEV_TAG" --draft=false') < workflow.indexOf('gh release upload development latest.json'));
  assert.match(workflow, /does not reference this versioned release/);
  assert.doesNotMatch(workflow, /Remove Stale Development Assets/);
});

test("Android pins certificate tooling and retries an existing immutable tag", () => {
  const android = readFileSync(new URL("../.github/workflows/release-android.yml", import.meta.url), "utf8");
  assert.ok(android.includes('SIGNER="$SDK_ROOT/build-tools/34.0.0/apksigner"'));
  assert.doesNotMatch(android, /build-tools\/\*\/apksigner/);
  assert.match(android, /workflow_dispatch:/);
  assert.ok(android.includes('ref: ${{ inputs.tag || github.ref }}'));
  assert.ok(android.includes('node scripts/android-version.mjs check "$TAG_NAME"'));
  assert.ok(android.includes('TAG_NAME: ${{ steps.verify-tag.outputs.TAG_NAME }}'));
  assert.doesNotMatch(android, /git (?:tag.*--force|push.*--force)/);
  assert.ok(android.includes('require("./apps/android/signing-policy.json").signingCertificateSha256'));
});
