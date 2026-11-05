import { readFileSync, statSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { createHash } from "node:crypto";
import { androidChannel, checkAndroidVersion, compareVersions } from "./android-version.mjs";

export function createAndroidManifest(metadata, { sha256, size, notes = "", publishedAt = new Date().toISOString() }) {
  if (!Number.isSafeInteger(metadata.versionCode) || metadata.versionCode <= 0 || metadata.versionCode > 2_100_000_000) throw new Error("Invalid Android install number");
  if (!/^[a-f0-9]{64}$/.test(sha256) || !Number.isSafeInteger(size) || size <= 0 || size > 256 * 1024 * 1024) throw new Error("Invalid Android updater artifact");
  const version = metadata.versionName;
  androidChannel(version);
  return { schemaVersion: 1, version, versionCode: metadata.versionCode,
    packageId: "com.dropflow.android",
    url: `https://github.com/sanleesam/DropFlow/releases/download/android-v${version}/DropFlow-android-${version}.apk`,
    sha256, size, notes: notes.slice(0, 8000), publishedAt };
}

export function shouldPublishAndroidChannel(current, candidate) {
  if (!current) return true;
  if (current.schemaVersion !== 1 || !Number.isSafeInteger(current.versionCode) || current.versionCode <= 0) throw new Error("Invalid existing Android channel manifest");
  if (androidChannel(current.version) !== androidChannel(candidate.version)) throw new Error("Existing Android manifest belongs to a different channel");
  return candidate.versionCode > current.versionCode && compareVersions(candidate.version, current.version) > 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const [command, value, tag] = process.argv.slice(2);
    if (command === "create") {
      const metadata = checkAndroidVersion(tag);
      const size = statSync(value).size;
      if (size > 256 * 1024 * 1024) throw new Error("APK is too large");
      const sha256 = createHash("sha256").update(readFileSync(value)).digest("hex");
      let notes = "";
      try { notes = readFileSync(`docs/releases/android-${metadata.versionName}.md`, "utf8"); } catch (error) { if (error.code !== "ENOENT") throw error; }
      console.log(JSON.stringify(createAndroidManifest(metadata, { sha256, size, notes }), null, 2));
    } else if (command === "should-publish") {
      const candidate = JSON.parse(readFileSync(value, "utf8"));
      const channel = androidChannel(candidate.version);
      const response = await fetch(`https://github.com/sanleesam/DropFlow/releases/download/android-${channel}/latest.json?_=${Date.now()}`);
      let current = null;
      if (response.status !== 404) {
        if (!response.ok) throw new Error(`Could not inspect Android channel: HTTP ${response.status}`);
        const text = await response.text();
        if (text.length > 65536) throw new Error("Existing Android channel manifest is too large");
        current = JSON.parse(text);
      }
      console.log(shouldPublishAndroidChannel(current, candidate) ? "publish" : "skip");
    } else throw new Error("Usage: android-updater-manifest.mjs create APK TAG | should-publish latest.json");
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
