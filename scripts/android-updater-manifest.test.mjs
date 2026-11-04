import test from "node:test";
import assert from "node:assert/strict";
import { createAndroidManifest, shouldPublishAndroidChannel } from "./android-updater-manifest.mjs";
const candidate = createAndroidManifest({ versionName: "0.2.0-alpha.1", versionCode: 18 }, { sha256: "a".repeat(64), size: 1234, publishedAt: "2026-11-04T00:00:00Z" });
test("Android manifest points only to its immutable versioned APK", () => {
  assert.equal(candidate.url, "https://github.com/sanleesam/DropFlow/releases/download/android-v0.2.0-alpha.1/DropFlow-android-0.2.0-alpha.1.apk");
  assert.equal(candidate.packageId, "com.dropflow.android");
  assert.throws(() => createAndroidManifest({ versionName: "0.2.0-alpha.1", versionCode: 18 }, { sha256: "bad", size: 1 }));
});
test("late old runs cannot roll the Android channel backwards", () => {
  assert.equal(shouldPublishAndroidChannel(null, candidate), true);
  assert.equal(shouldPublishAndroidChannel(candidate, candidate), false);
  assert.equal(shouldPublishAndroidChannel({ ...candidate, version: "0.2.0-alpha.2", versionCode: 19 }, candidate), false);
  assert.equal(shouldPublishAndroidChannel({ ...candidate, version: "0.1.0-alpha.1", versionCode: 17 }, candidate), true);
  assert.throws(() => shouldPublishAndroidChannel({ versionCode: "wrong" }, candidate));
});
