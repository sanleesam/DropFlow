import { appendFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

const component = "(?:0|[1-9][0-9]*)";
const taggedVersion = new RegExp(
  `^refs/tags/v(${component}\\.${component}\\.${component}-dev\\.[1-9][0-9]*)$`,
);

export function resolveDevelopmentVersion({ ref, runNumber }) {
  if (typeof ref !== "string") throw new Error("GITHUB_REF is required");
  const match = ref.match(taggedVersion);
  if (match) return { version: match[1], tag: `v${match[1]}` };
  if (!ref.startsWith("refs/heads/")) {
    throw new Error("Development tags must match v<major>.<minor>.<patch>-dev.<number>");
  }
  if (!/^[1-9][0-9]*$/.test(String(runNumber ?? ""))) {
    throw new Error("GITHUB_RUN_NUMBER must be a positive integer");
  }
  const version = `0.1.0-dev.${runNumber}`;
  return { version, tag: `v${version}` };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const result = resolveDevelopmentVersion({
    ref: process.env.GITHUB_REF,
    runNumber: process.env.GITHUB_RUN_NUMBER,
  });
  const output = `version=${result.version}\ntag=${result.tag}\n`;
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, output);
  process.stdout.write(output);
}
