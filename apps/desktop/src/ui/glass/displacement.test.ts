import { buildLensMap, rimHeight, rimProfile } from "./displacement";

function assert(condition: boolean, message: string) {
  if (!condition) throw new Error(message);
}

const at = (map: ReturnType<typeof buildLensMap>, x: number, y: number) => {
  const i = (y * map.width + x) * 4;
  return [map.data[i], map.data[i + 1]] as const;
};

assert(rimHeight(0) === 0 && rimHeight(1) === 1, "Rim profile must run from edge (0) to face (1)");
const profile = rimProfile();
assert(Math.max(...profile) <= 1 && Math.min(...profile) >= 0, "Refraction profile must be normalized");
assert(profile[profile.length - 1] < 0.05, "The flat face must not bend light");

const map = buildLensMap({ width: 120, height: 60, radius: 20, bezel: 16 });
assert(map.data.length === 120 * 60 * 4, "Map must cover every pixel");
const [cx, cy] = at(map, 60, 30);
assert(cx === 128 && cy === 128, "The centre of the lens must be neutral");
const [lx] = at(map, 3, 30);
const [rx] = at(map, 116, 30);
assert(lx > 128, "Left rim must sample toward the centre");
assert(rx < 128, "Right rim must sample toward the centre");
const [, ty] = at(map, 60, 3);
assert(ty > 128, "Top rim must sample toward the centre");
const [ox, oy] = at(map, 0, 0);
assert(ox === 128 && oy === 128, "Pixels outside the rounded corner must stay neutral");
const tiny = buildLensMap({ width: 2, height: 2, radius: 50, bezel: 50 });
assert(tiny.width === 2 && tiny.data.every((v) => Number.isFinite(v)), "Degenerate sizes must be safe");
console.log("Liquid Glass lens checks passed (10 assertions).");
