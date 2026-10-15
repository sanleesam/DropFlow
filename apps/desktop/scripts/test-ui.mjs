import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { execFileSync } from "node:child_process";
import { build } from "vite";

// Reuse Vite's existing TypeScript/JSX toolchain; no test framework required.
const output = await mkdtemp(join(tmpdir(), "dropflow-ui-tests-"));
try {
  await build({
    configFile: false,
    publicDir: false,
    build: {
      lib: {
        entry: {
          lifecycle: "src/utils/transferSessionManager.test.ts",
          primitives: "src/components/ui/Desktop.test.tsx",
        },
        formats: ["es"],
        fileName: (_format, entry) => `${entry}.mjs`,
      },
      outDir: output,
      emptyOutDir: false,
    },
  });
  for (const suite of ["lifecycle", "primitives"]) {
    const url = pathToFileURL(join(output, `${suite}.mjs`)).href;
    // Browser React's scheduler can keep Node alive after synchronous assertions.
    execFileSync(
      process.execPath,
      [
        "--input-type=module",
        "-e",
        `await import(${JSON.stringify(url)}); process.exit(0);`,
      ],
      { stdio: "inherit" },
    );
  }
} finally {
  await rm(output, { recursive: true, force: true });
}
