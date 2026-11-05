import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { parseVersion } from "./desktop-version.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");

export function androidChannel(version) {
  const { stage } = parseVersion(version);
  return stage === "stable" ? "stable" : ["beta", "rc"].includes(stage) ? "beta" : "alpha";
}

export function parseAndroidVersion(gradle) {
  const names = [...gradle.matchAll(/^\s*versionName = "([^"]+)"\s*$/gm)];
  const codes = [...gradle.matchAll(/^\s*versionCode = ([0-9]+)\s*$/gm)];
  if (names.length !== 1 || codes.length !== 1) throw new Error("Android needs one literal versionName and versionCode");
  const versionName = names[0][1], versionCode = Number(codes[0][1]);
  parseVersion(versionName);
  if (!/^[1-9]\d*$/.test(codes[0][1]) || versionCode > 2_100_000_000) throw new Error("Invalid Android versionCode");
  return { versionName, versionCode };
}

export function compareVersions(left, right) {
  const a = parseVersion(left), b = parseVersion(right);
  const ac = a.base.split(".").map(BigInt), bc = b.base.split(".").map(BigInt);
  for (let i = 0; i < 3; i++) if (ac[i] !== bc[i]) return ac[i] > bc[i] ? 1 : -1;
  if (a.stage === "stable" || b.stage === "stable") return a.stage === b.stage ? 0 : a.stage === "stable" ? 1 : -1;
  if (a.stage !== b.stage) return a.stage > b.stage ? 1 : -1;
  const an = BigInt(a.counter), bn = BigInt(b.counter);
  return an === bn ? 0 : an > bn ? 1 : -1;
}

export function validateAndroidRelease(metadata, baseline, tag) {
  parseVersion(metadata.versionName);
  if (metadata.versionCode <= baseline.versionCode) throw new Error("Android versionCode must exceed the last published APK");
  if (compareVersions(metadata.versionName, baseline.versionName) <= 0) throw new Error("Android Semantic Version must exceed the last published version");
  if (tag !== undefined && tag !== `android-v${metadata.versionName}`) throw new Error("Android tag does not match versionName");
  if (!/^[a-f0-9]{64}$/.test(baseline.signingCertificateSha256)) throw new Error("Missing published Android signing certificate");
  return metadata;
}

export function checkAndroidVersion(tag, base = root) {
  const metadata = parseAndroidVersion(readFileSync(resolve(base, "apps/android/app/build.gradle.kts"), "utf8"));
  const baseline = JSON.parse(readFileSync(resolve(base, "apps/android/release-baseline.json"), "utf8"));
  const signing = JSON.parse(readFileSync(resolve(base, "apps/android/signing-policy.json"), "utf8"));
  validateSigningPolicy(signing, baseline, metadata);
  return validateAndroidRelease(metadata, baseline, tag);
}

export function validateSigningPolicy(signing, baseline, metadata) {
  if (!/^[a-f0-9]{64}$/.test(signing.signingCertificateSha256)) throw new Error("Missing permanent Android signing certificate");
  if (compareVersions(metadata.versionName, signing.firstVersion) < 0) throw new Error("Source version predates the signing identity");
  if (baseline.signingCertificateSha256 !== signing.signingCertificateSha256) {
    const migration = signing.migration;
    if (!migration || migration.fromVersion !== baseline.versionName ||
        migration.fromCertificateSha256 !== baseline.signingCertificateSha256 ||
        migration.requiresReinstall !== true || !migration.reason?.trim()) {
      throw new Error("Signing identity differs from the baseline without an explicit reinstall migration");
    }
  }
  return signing;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const [command = "check", tag] = process.argv.slice(2);
    const result = checkAndroidVersion(tag);
    if (command === "version") console.log(result.versionName);
    else if (command === "code") console.log(result.versionCode);
    else if (command === "channel") console.log(androidChannel(result.versionName));
    else if (command === "check") console.log(JSON.stringify(result));
    else throw new Error("Usage: android-version.mjs check|version|code|channel [android-vVERSION]");
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
