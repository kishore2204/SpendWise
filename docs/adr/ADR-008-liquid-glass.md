# ADR-008: Liquid glass. Supersedes ADR-007's glass approach
- Status: Accepted (user decision, 2026-10-07)
- Date: 2026-10-07
- Supersedes: ADR-007 §"Glass placement" and its "Haze" implementation. Colours, type, spacing and motion from ADR-007 still apply.

## Context
The user saw the Phase 2 build on the device (Galaxy A56, One UI 8.5) and found that the "light-glass" approach from ADR-007 did not feel like iOS Liquid Glass. ADR-007 had chosen restraint: blur only on bars, near-opaque cards, no refraction. Four specific gaps against iOS:
1. No **refraction/lensing**: content didn't bend at the glass edges.
2. Glass too **frosted/opaque** (70% tint), so it read as solid.
3. **Nothing behind the glass**: blur over a near-black background is invisible.
4. No **liquid motion**: a dark disc behind the selected tab instead of a glass droplet.

## Decision
- **Engine:** Kyant `backdrop` 2.0.1 (+ `shapes` 1.2.1), Apache-2.0, replaces Haze. It provides lens refraction with optional chromatic aberration, vibrancy, blur, specular rim highlights, shadows, and continuous-curvature (squircle) shapes, all via RuntimeShader. That requires API 33+, which matches our minSdk 33.
- **Aurora:** a static four-colour light field sits behind everything (per-theme tokens `aurora1..4`), so glass always has colour to refract. It isn't animated, so it costs no battery.
- **Two backdrops** (`GlassScaffold`):
  - *background* = aurora only, used by glass **inside** scrolling content (cards), so a card never samples itself;
  - *content* = aurora + screen, used by **floating controls** (tab bar, + button).
- **`Modifier.liquidGlass(shape, kind)`:**
  - *Control*: clear (~12% tint), strong lens.
  - *Card*: more tint (~28% dark / ~45% light) so text and money stay crisp, plus a softer lens and a 12dp blur.
- **Liquid tab bar:**
  - A clear capsule of four tabs, with a **liquid droplet** that slides with a spring, swells and refracts with chromatic aberration when touched, stretches with velocity while dragged, and reveals an accent-tinted copy of the icon beneath it.
  - The "+ Expense" button is a separate floating glass circle beside the bar, in the iOS 26 accessory-button layout.
- **Top bar:** an iOS-style scroll-edge fade with an inline title, owned by the scaffold.
- **Fallback (unchanged triggers):** battery saver, thermal ≥ MODERATE, animator scale 0, or "Reduce transparency" keeps the same shapes and highlights but turns off blur and lens and uses a near-opaque tint.

## Readability safeguards kept from the council
- Money and body text use `text1`/`text2` on card-kind glass. The tint, the blurred and smooth aurora, and the vibrancy keep contrast high.
- Dense lists keep card-kind glass, never control-kind glass.
- Light and dark aurora palettes are designed separately.

## Consequences
- A single-maintainer library becomes a dependency. Its API is wrapped in `ui/glass/Glass.kt` and `LiquidTabBar.kt`, so it could be swapped for `haze-glass` without touching screens.
- GPU cost is higher than ADR-007. It must be checked with frame timing on the A56 once real lists exist (Phase 3/4). If frames drop, reduce card blur first, then card lens.
- Not copied from Apple: the layouts, components, icons (Material Symbols), aurora and droplet implementation are our own. Only the visual qualities are shared.
