import { gsap } from "gsap";

/** Scoped, reversible motion. Never animate when the OS requests less motion. */
function scopedMotion(
  root: HTMLElement,
  create: () => gsap.core.Timeline,
): () => void {
  const media = gsap.matchMedia();
  media.add(
    "(prefers-reduced-motion: no-preference)",
    () => {
      const timeline = create();
      const visibility = () => timeline.paused(document.hidden);
      visibility();
      document.addEventListener("visibilitychange", visibility);
      return () => document.removeEventListener("visibilitychange", visibility);
    },
    root,
  );
  return () => media.revert();
}

export function mountDiscoveryMotion(root: HTMLElement): () => void {
  return scopedMotion(root, () => {
    const timeline = gsap.timeline({ repeat: -1 });
    timeline.fromTo(
      ".df-discovery-ring",
      { scale: 0.66, opacity: 0.44 },
      {
        scale: 1.55,
        opacity: 0,
        duration: 2.8,
        stagger: 1.2,
        ease: "sine.out",
      },
      0,
    );
    timeline.fromTo(
      ".df-discovery-edge",
      { opacity: 0.18 },
      { opacity: 0.7, duration: 2.2, ease: "sine.inOut", yoyo: true, repeat: 1 },
      0,
    );
    timeline.fromTo(
      ".df-discovery-beacon-core",
      { scale: 0.96 },
      { scale: 1.06, duration: 2.2, ease: "sine.inOut", yoyo: true, repeat: 1 },
      0,
    );
    return timeline;
  });
}

export function mountPageMotion(root: HTMLElement): () => void {
  return scopedMotion(root, () =>
    gsap.timeline().fromTo(
      root.querySelectorAll(
        ".df-v2-header, .df-v2-stack > .df-card, .df-v2-rail > .df-card, " +
          ".df-v2-workspace > .df-card, .df-tx-main > .df-card, " +
          ".df-tx-side, .df-files-layout > .df-card",
      ),
      { y: 5 },
      {
        y: 0,
        duration: 0.32,
        stagger: 0.035,
        ease: "power2.out",
        clearProps: "transform",
      },
    ),
  );
}
