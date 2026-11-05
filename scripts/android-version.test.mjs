import assert from "node:assert/strict";
import test from "node:test";
import { androidChannel, compareVersions, parseAndroidVersion, validateAndroidRelease, validateSigningPolicy } from "./android-version.mjs";
const baseline = { versionName: "0.1.0-beta.1", versionCode: 17, signingCertificateSha256: "a".repeat(64) };
const current = { versionName: "0.2.0-alpha.1", versionCode: 18 };

test("new signing identity requires a documented reinstall migration, never a rewritten baseline", () => {
  const policy = { signingCertificateSha256: "b".repeat(64), firstVersion: current.versionName,
    migration: { fromVersion: baseline.versionName, fromCertificateSha256: baseline.signingCertificateSha256, requiresReinstall: true, reason: "Approved permanent key migration" } };
  assert.equal(validateSigningPolicy(policy, baseline, current), policy);
  assert.throws(() => validateSigningPolicy({ ...policy, migration: undefined }, baseline, current));
  assert.throws(() => validateSigningPolicy({ ...policy, migration: { ...policy.migration, requiresReinstall: false } }, baseline, current));
  assert.throws(() => validateSigningPolicy({ ...policy, migration: { ...policy.migration, fromCertificateSha256: "c".repeat(64) } }, baseline, current));
  assert.throws(() => validateSigningPolicy({ ...policy, signingCertificateSha256: "" }, baseline, current));
  assert.equal(validateSigningPolicy(policy, { ...baseline, signingCertificateSha256: policy.signingCertificateSha256 }, current), policy);
});

test("Android release maturity selects its own updater channel", () => {
  for (const [version, channel] of [["0.2.0-alpha.1", "alpha"], ["0.2.0-beta.1", "beta"], ["0.2.0-rc.1", "beta"], ["0.2.0", "stable"]]) assert.equal(androidChannel(version), channel);
});

test("Android metadata is unambiguous canonical SemVer with an install number", () => {
  assert.deepEqual(parseAndroidVersion('versionCode = 18\nversionName = "0.2.0-alpha.1"'), current);
  for (const text of ['versionCode = 0\nversionName = "0.2.0-alpha.1"', 'versionCode = 18\nversionName = "v0.2.0"', 'versionCode = 18\nversionName = "0.2.0"\nversionName = "0.3.0"']) assert.throws(() => parseAndroidVersion(text));
});
test("Alpha migration keeps public version and installation ordering", () => {
  assert.equal(validateAndroidRelease(current, baseline, "android-v0.2.0-alpha.1"), current);
  for (const bad of [{ ...current, versionCode: 17 }, { ...current, versionName: "0.1.0-alpha.1" }]) assert.throws(() => validateAndroidRelease(bad, baseline));
  assert.throws(() => validateAndroidRelease(current, baseline, "v0.2.0-alpha.1"));
});
test("version comparison uses core, numeric prerelease and final-release precedence", () => {
  for (const [a, b] of [["0.1.0-beta.9", "0.1.0-beta.10"], ["0.1.0-beta.99", "0.2.0-alpha.1"], ["0.2.0-alpha.1", "0.2.0-beta.1"], ["0.2.0-rc.1", "0.2.0"]]) assert.equal(compareVersions(a, b), -1);
  assert.equal(compareVersions("0.2.0-alpha.1", "0.2.0-alpha.1"), 0);
});
