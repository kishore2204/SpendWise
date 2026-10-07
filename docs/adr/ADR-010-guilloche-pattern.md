# ADR-010: Guilloché pattern so glass is visible
- Status: Accepted (user decision, 2026-10-07)
- Builds on: ADR-009 (calm palette). Revises its decision to make content cards solid.

## Context
After ADR-009 the user found the glass "not visible in both themes, too minimal" and asked to **use patterns so it is visible**. The round-1 Graphics seat had already shown why: refraction is only visible where the backdrop has fine detail, and a flat neutral background has none.

## Decision
- **A monochrome guilloché line field sits behind everything.** These are concentric rings with a 28-petal sine "rosette", 12dp apart, 1dp lines, centred off-screen at the top right, like banknote security printing.
  - It adds no hue: `pattern` is white at about 9% in dark, ink at about 9% in light.
  - The wave fades in with radius, so the centre never zig-zags.
- **Glass can now refract the pattern, so it reads as glass even on empty screens:**
  - Floating controls (tab bar, droplet, "+") refract background + content: tint dark 35% / light 45%, blur 1.5dp, lens 26/48dp with depth, 1.5dp rim.
  - **Content cards are glass again** (`GlassKind.Card`, sampling the background-only backdrop): tint dark 50% / light 55%, blur 2dp, lens 20/32dp, 1dp rim. Under that tint the pattern drops to about 2% contrast, so text stays crisp.
- **Performance:** the pattern (about 60k path segments) is rasterised **once per size** into an `Alpha8` mask and drawn tinted each frame. On the Galaxy A56 (debug build) this took janky frames from 100% to 5.5% and p50 from 97ms to 13ms. The release build must be re-measured in Phase 3; the target is p90 ≤ 8.3ms.
- Fallback (reduce transparency or battery saver) is unchanged: same shapes and edges, cards use the solid `surface1`.

## Why guilloché
It is monochrome, it belongs to the money domain (banknotes, cheques), it reads as print rather than as an AI gradient, and its fine regular lines are exactly what a lens bends visibly.

## Revisit if
- The pattern feels busy behind dense lists in Phase 3/4. If so, lower `pattern` alpha or hide it under list regions.
- The release build misses the frame target. Then cut card lens first.
