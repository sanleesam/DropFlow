import { readFileSync, writeFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const packagePath = "apps/desktop/package.json";
const configPath = "apps/desktop/src-tauri/tauri.conf.json";
const cargoPath = "apps/desktop/src-tauri/Cargo.toml";
const lockPath = "apps/desktop/src-tauri/Cargo.lock";
const cargoPattern = /^(version = ")[^"]+("\r?$)/m;
const lockPattern = /(\[\[package\]\]\r?\nname = "Dropflow"\r?\nversion = ")[^"]+("\r?\n)/;

export function parseVersion(version) {
  const match = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(alpha|beta|rc|dev)\.(0|[1-9]\d*))?$/.exec(version);
  if (!match) throw new Error(`Invalid release version: ${version}. Expected X.Y.Z[-alpha|beta|rc|dev.N], without leading zeros.`);
  return { base: `${match[1]}.${match[2]}.${match[3]}`, stage: match[4] || "stable", counter: match[5] };
}

export function developmentVersion(version, run) {
  if (!/^[1-9]\d*$/.test(String(run))) throw new Error("Development run must be a positive integer");
  return `${parseVersion(version).base}-dev.${run}`;
}

export function checkVersions(base = root) {
  const read = path => readFileSync(resolve(base, path), "utf8");
  const version = JSON.parse(read(packagePath)).version;
  parseVersion(version);
  const versions = [JSON.parse(read(configPath)).version,
    read(cargoPath).match(/^version = "([^"]+)"/m)?.[1],
    read(lockPath).match(/\[\[package\]\]\r?\nname = "Dropflow"\r?\nversion = "([^"]+)"/)?.[1]];
  if (versions.some(other => other !== version)) throw new Error(`Desktop version drift: package=${version}, config/cargo/lock=${versions.join(",")}`);
  return version;
}

export function syncVersion(version, base = root) {
  parseVersion(version);
  // Validate all replacement targets before writing any file.
  const read = path => readFileSync(resolve(base, path), "utf8");
  const pkg = JSON.parse(read(packagePath));
  const config = JSON.parse(read(configPath));
  const cargo = read(cargoPath), lock = read(lockPath);
  if (!cargoPattern.test(cargo) || !lockPattern.test(lock)) throw new Error("Missing Cargo package version");
  pkg.version = config.version = version;
  for (const [path, contents] of [[packagePath, JSON.stringify(pkg, null, 2) + "\n"],
    [configPath, JSON.stringify(config, null, 2) + "\n"],
    [cargoPath, cargo.replace(cargoPattern, `$1${version}$2`)],
    [lockPath, lock.replace(lockPattern, `$1${version}$2`)]]) {
    writeFileSync(resolve(base, path), contents);
  }
  return checkVersions(base);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const [command = "check", value] = process.argv.slice(2);
    if (command === "check") console.log(checkVersions());
    else if (command === "set") console.log(syncVersion(value));
    else if (command === "development") console.log(developmentVersion(checkVersions(), value));
    else throw new Error("Usage: desktop-version.mjs check | set VERSION | development RUN");
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
