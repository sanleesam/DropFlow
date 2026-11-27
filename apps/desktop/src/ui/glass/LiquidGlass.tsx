import {
  createElement,
  forwardRef,
  useEffect,
  useImperativeHandle,
  useRef,
  useSyncExternalStore,
  type CSSProperties,
  type HTMLAttributes,
  type ReactNode,
} from "react";
import { buildLensMap } from "./displacement";

/**
 * refract — real lensing through an SVG displacement backdrop (Chromium / WebView2).
 * frost    — blur + saturation with optical rim lighting (WebKit, which drops
 *            url() in backdrop-filter; see WebKit bug 245510).
 * solid    — Reduce Transparency: opaque surfaces, no backdrop sampling.
 */
export type GlassMode = "refract" | "frost" | "solid";
export type GlassPreference = "auto" | "frost" | "solid";

const PREF_KEY = "df.glass";
const listeners = new Set<() => void>();

function readPreference(): GlassPreference {
  try {
    const value = localStorage.getItem(PREF_KEY);
    if (value === "frost" || value === "solid") return value;
  } catch {
    /* storage can be unavailable; fall back to auto */
  }
  return "auto";
}

function engineRefracts(): boolean {
  if (typeof navigator === "undefined" || typeof CSS === "undefined") return false;
  const brands = (
    navigator as Navigator & { userAgentData?: { brands?: { brand: string }[] } }
  ).userAgentData?.brands;
  const chromium = brands
    ? brands.some((b) => /Chromium|Google Chrome|Microsoft Edge/.test(b.brand))
    : /Chrome\/\d+/.test(navigator.userAgent) && !/Version\/\d+.*Safari/.test(navigator.userAgent);
  return chromium && CSS.supports("backdrop-filter", "url(#a)");
}

function systemWantsSolid(): boolean {
  if (typeof window === "undefined") return false;
  const html = document.documentElement;
  return (
    // macOS publishes "solid" when Reduce Transparency is on (WebKit lacks the query).
    (html.dataset.nativePlatform === "macos" && html.dataset.nativeMaterial === "solid") ||
    window.matchMedia?.("(prefers-reduced-transparency: reduce)").matches ||
    window.matchMedia?.("(prefers-contrast: more)").matches
  );
}

function computeMode(): GlassMode {
  const pref = readPreference();
  if (pref === "solid" || systemWantsSolid()) return "solid";
  if (pref === "frost") return "frost";
  return engineRefracts() ? "refract" : "frost";
}

let mode: GlassMode = typeof window === "undefined" ? "frost" : computeMode();

function publish() {
  mode = computeMode();
  document.documentElement.dataset.glass = mode;
  listeners.forEach((fn) => fn());
}

if (typeof window !== "undefined") {
  document.documentElement.dataset.glass = mode;
  for (const query of ["(prefers-reduced-transparency: reduce)", "(prefers-contrast: more)"]) {
    window.matchMedia?.(query).addEventListener?.("change", publish);
  }
  new MutationObserver(publish).observe(document.documentElement, {
    attributes: true,
    attributeFilter: ["data-native-material", "data-native-platform"],
  });
}

export function getGlassPreference(): GlassPreference {
  return readPreference();
}

export function setGlassPreference(next: GlassPreference) {
  try {
    if (next === "auto") localStorage.removeItem(PREF_KEY);
    else localStorage.setItem(PREF_KEY, next);
  } catch {
    /* preference simply won't persist */
  }
  publish();
}

export function useGlassMode(): GlassMode {
  return useSyncExternalStore(
    (fn) => {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
    () => mode,
    () => "frost",
  );
}

/* ---------- shared SVG filter defs ---------- */

const SVG_NS = "http://www.w3.org/2000/svg";
let defs: SVGDefsElement | null = null;
let counter = 0;
const mapCache = new Map<string, string>();

function ensureDefs(): SVGDefsElement {
  if (defs?.isConnected) return defs;
  const svg = document.createElementNS(SVG_NS, "svg");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("width", "0");
  svg.setAttribute("height", "0");
  svg.style.cssText = "position:absolute;width:0;height:0;overflow:hidden;pointer-events:none";
  defs = document.createElementNS(SVG_NS, "defs");
  svg.appendChild(defs);
  document.body.appendChild(svg);
  return defs;
}

function lensUrl(w: number, h: number, radius: number, bezel: number): string {
  const key = `${w}x${h}r${radius}b${bezel}`;
  const hit = mapCache.get(key);
  if (hit) return hit;
  const map = buildLensMap({ width: w, height: h, radius, bezel });
  const canvas = document.createElement("canvas");
  canvas.width = map.width;
  canvas.height = map.height;
  const ctx = canvas.getContext("2d");
  if (!ctx) return "";
  ctx.putImageData(new ImageData(map.data, map.width, map.height), 0, 0);
  const url = canvas.toDataURL("image/png");
  if (mapCache.size > 48) mapCache.delete(mapCache.keys().next().value as string);
  mapCache.set(key, url);
  return url;
}

function el(name: string, attrs: Record<string, string | number>) {
  const node = document.createElementNS(SVG_NS, name);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, String(v));
  return node;
}

interface LensTuning {
  /** Rim width as a share of the shorter side (capped by `maxBezel`). */
  bezel?: number;
  /** Peak displacement in px at the steepest point of the rim. */
  strength?: number;
  blur?: number;
  saturation?: number;
}

const SIZE_TUNING = {
  sm: { bezel: 0.42, strength: 14, blur: 1.2, saturation: 1.7 },
  md: { bezel: 0.36, strength: 22, blur: 2, saturation: 1.6 },
  lg: { bezel: 0.12, strength: 34, blur: 6, saturation: 1.5 },
} as const;

/** Text-bearing surfaces: diffuse the backdrop well past legibility. */
const THICK_TUNING: LensTuning = { blur: 18, saturation: 1.35 };

/**
 * Attaches a live lens to `ref` while refraction is available. The CSS frost
 * stack stays underneath as the fallback, so nothing is lost when this is off.
 */
export function useLiquidGlass(
  ref: React.RefObject<HTMLElement | null>,
  size: keyof typeof SIZE_TUNING = "md",
  tuning: LensTuning = {},
) {
  const glassMode = useGlassMode();
  const { bezel, strength, blur, saturation } = { ...SIZE_TUNING[size], ...tuning };

  useEffect(() => {
    const node = ref.current;
    if (!node || glassMode !== "refract") return;
    const id = `lg-lens-${++counter}`;
    const filter = el("filter", {
      id,
      x: 0,
      y: 0,
      filterUnits: "userSpaceOnUse",
      primitiveUnits: "userSpaceOnUse",
      "color-interpolation-filters": "sRGB",
    });
    const blurNode = el("feGaussianBlur", { in: "SourceGraphic", stdDeviation: blur, result: "soft" });
    const image = el("feImage", { x: 0, y: 0, preserveAspectRatio: "none", result: "lens" });
    const displace = el("feDisplacementMap", {
      in: "soft",
      in2: "lens",
      xChannelSelector: "R",
      yChannelSelector: "G",
      result: "bent",
    });
    const color = el("feColorMatrix", { in: "bent", type: "saturate", values: saturation });
    filter.append(blurNode, image, displace, color);
    ensureDefs().appendChild(filter);

    let frame = 0;
    const update = () => {
      frame = 0;
      const rect = node.getBoundingClientRect();
      const w = Math.round(rect.width);
      const h = Math.round(rect.height);
      if (w < 4 || h < 4) return;
      const css = parseFloat(getComputedStyle(node).borderTopLeftRadius) || 0;
      const radius = Math.min(css, w / 2, h / 2);
      const rim = Math.round(Math.min(Math.min(w, h) * bezel, 48, Math.max(radius, 10)));
      const href = lensUrl(w, h, Math.round(radius), rim);
      if (!href) return;
      filter.setAttribute("width", String(w));
      filter.setAttribute("height", String(h));
      image.setAttribute("width", String(w));
      image.setAttribute("height", String(h));
      image.setAttribute("href", href);
      displace.setAttribute("scale", String(strength * 2));
      node.style.setProperty("backdrop-filter", `url(#${id})`);
      node.dataset.lens = "on";
    };
    const schedule = () => {
      if (!frame) frame = requestAnimationFrame(update);
    };
    const observer = new ResizeObserver(schedule);
    observer.observe(node);
    schedule();
    return () => {
      observer.disconnect();
      if (frame) cancelAnimationFrame(frame);
      filter.remove();
      node.style.removeProperty("backdrop-filter");
      delete node.dataset.lens;
    };
  }, [ref, glassMode, bezel, strength, blur, saturation]);
}

/* ---------- pointer-lit specular highlight ---------- */

let lightInstalled = false;

/** One delegated listener: the highlight follows the pointer across any glass. */
export function installGlassLight() {
  if (lightInstalled || typeof window === "undefined") return;
  lightInstalled = true;
  let frame = 0;
  let last: PointerEvent | null = null;
  let lit: HTMLElement | null = null;
  const apply = () => {
    frame = 0;
    if (!last) return;
    const target = (last.target as Element | null)?.closest?.<HTMLElement>(".glass");
    if (lit && lit !== target) lit.removeAttribute("data-lit");
    lit = target ?? null;
    if (!target) return;
    const rect = target.getBoundingClientRect();
    target.style.setProperty("--lx", `${((last.clientX - rect.left) / rect.width) * 100}%`);
    target.style.setProperty("--ly", `${((last.clientY - rect.top) / rect.height) * 100}%`);
    target.setAttribute("data-lit", "");
  };
  window.addEventListener(
    "pointermove",
    (event) => {
      last = event;
      if (!frame) frame = requestAnimationFrame(apply);
    },
    { passive: true },
  );
  document.addEventListener("pointerleave", () => {
    lit?.removeAttribute("data-lit");
    lit = null;
  });
}

/* ---------- component ---------- */

type GlassProps = HTMLAttributes<HTMLElement> & {
  as?: keyof HTMLElementTagNameMap;
  size?: keyof typeof SIZE_TUNING;
  /** Accent-tinted glass for the one primary action in a group. */
  tint?: boolean;
  /**
   * "clear" only over rich media; "regular" for controls. "thick" for
   * surfaces that carry text over arbitrary content (sheets, menus, toasts):
   * heavier diffusion and tint so what is behind never competes with it.
   */
  variant?: "regular" | "clear" | "thick";
  lens?: LensTuning;
  children?: ReactNode;
  style?: CSSProperties;
};

export const Glass = forwardRef<HTMLElement, GlassProps>(function Glass(
  { as = "div", size = "md", tint, variant = "regular", lens, className, children, ...rest },
  forwarded,
) {
  const ref = useRef<HTMLElement>(null);
  useImperativeHandle(forwarded, () => ref.current as HTMLElement);
  useLiquidGlass(ref, size, variant === "thick" ? { ...THICK_TUNING, ...lens } : lens);
  const classes = [
    "glass",
    `glass-${size}`,
    variant === "clear" ? "glass-clear" : "",
    variant === "thick" ? "glass-thick" : "",
    tint ? "glass-tint" : "",
    className || "",
  ]
    .filter(Boolean)
    .join(" ");
  return createElement(as, { ref, className: classes, ...rest }, children);
});
