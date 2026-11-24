#!/usr/bin/env python3
"""DropFlow transfer benchmark helper. Local files only; never contacts a network.

  make   <dir> [--large-gib 4]   build the three standard datasets + manifest.json
  verify <manifest> <received>   exact SHA-256 check of every received file
  perf   <log>...                summarise `[Perf]` lines from desktop logs or adb logcat

Datasets (random bytes, so compression can't flatter results):
  large/   one file of --large-gib GiB          -> streaming ceiling
  photos/  1,000 files of 200 KiB               -> per-file overhead
  medium/  100 files of 20 MiB                  -> mixed
"""
import argparse
import hashlib
import json
import os
import re
import statistics
import sys
from pathlib import Path

PROFILES = {
    "photos": (1000, 200 * 1024),
    "medium": (100, 20 * 1024 * 1024),
}
PERF = re.compile(
    r"\[Perf\] (?P<dir>send|receive) files=(?P<files>\d+) bytes=(?P<bytes>\d+) "
    r"secs=(?P<secs>[\d.]+) mib_s=(?P<mibs>[\d.]+) ms_per_file=(?P<ms>[\d.]+)"
)
BLOCK = 4 * 1024 * 1024


def write_random(path: Path, size: int) -> str:
    digest = hashlib.sha256()
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as out:
        left = size
        while left:
            chunk = os.urandom(min(BLOCK, left))
            out.write(chunk)
            digest.update(chunk)
            left -= len(chunk)
    return digest.hexdigest()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(BLOCK):
            digest.update(chunk)
    return digest.hexdigest()


def make(args) -> None:
    root = Path(args.dir).resolve()
    if root.exists() and any(root.iterdir()):
        sys.exit(f"{root} is not empty; choose a new folder")
    manifest = {}
    plan = [("large", 1, int(args.large_gib * 1024**3))] + [(n, c, s) for n, (c, s) in PROFILES.items()]
    if args.smoke:  # tiny copy of the same layout, for checking the tooling itself
        plan = [("large", 1, 3 * 1024**2), ("photos", 5, 200 * 1024), ("medium", 2, 1024**2)]
    for name, count, size in plan:
        for index in range(count):
            relative = f"{name}/{name}-{index:04d}.bin"
            manifest[relative] = {"size": size, "sha256": write_random(root / relative, size)}
        print(f"{name:<7} {count:>5} x {size / 1024**2:>9.1f} MiB")
    (root / "manifest.json").write_text(json.dumps(manifest, indent=1))
    print(f"wrote {root / 'manifest.json'}")


def verify(args) -> None:
    manifest = json.loads(Path(args.manifest).read_text())
    received = Path(args.received).resolve()
    bad = 0
    for relative, entry in manifest.items():
        candidates = [received / relative, received / Path(relative).name]
        found = next((p for p in candidates if p.is_file()), None)
        if args.only and not relative.startswith(args.only + "/"):
            continue
        if not found:
            print(f"MISSING  {relative}")
            bad += 1
        elif found.stat().st_size != entry["size"] or sha256(found) != entry["sha256"]:
            print(f"MISMATCH {relative}")
            bad += 1
    print("all files match" if not bad else f"{bad} problem(s)")
    sys.exit(1 if bad else 0)


def perf(args) -> None:
    rows = []
    for log in args.logs:
        for line in Path(log).read_text(errors="replace").splitlines():
            if match := PERF.search(line):
                rows.append(match.groupdict())
    if not rows:
        sys.exit("no [Perf] lines found")
    print(f"{'dir':<8}{'files':>7}{'GiB':>9}{'secs':>9}{'MiB/s':>9}{'ms/file':>9}")
    for row in rows:
        print(f"{row['dir']:<8}{row['files']:>7}{int(row['bytes']) / 1024**3:>9.2f}"
              f"{float(row['secs']):>9.2f}{float(row['mibs']):>9.1f}{float(row['ms']):>9.1f}")
    if len(rows) > 1:
        print(f"median MiB/s {statistics.median(float(r['mibs']) for r in rows):.1f}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("make"); p.add_argument("dir"); p.add_argument("--large-gib", type=float, default=4)
    p.add_argument("--smoke", action="store_true", help=argparse.SUPPRESS); p.set_defaults(run=make)
    p = sub.add_parser("verify"); p.add_argument("manifest"); p.add_argument("received")
    p.add_argument("--only", choices=["large", "photos", "medium"]); p.set_defaults(run=verify)
    p = sub.add_parser("perf"); p.add_argument("logs", nargs="+"); p.set_defaults(run=perf)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
