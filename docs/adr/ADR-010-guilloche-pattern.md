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

## Tuning after device review (same day)
The user said *"the dispersion is too much"* and *"light cards are too white, which reduces the glass"*. Changes:
- **Lens roughly halved.** Controls go from 26/48dp with depth to 16/22dp. Cards go from 20/32dp to 12/14dp. The droplet goes to 6–10/8–14dp. The "+" goes to 10/14dp.
- **Chromatic aberration removed everywhere**, including the pressed droplet.
- **Light cards:**
  - The white tint drops from 55% to 20%.
  - The glass no longer brightens its backdrop (`colorControls` brightness −0.01, previously +0.06), which had been turning the grey background white.
  - Blur is 2dp.
  - The pattern now shows through, and text stays crisp.
- **Light control tint** goes from 45% to 35%.

## iOS-style edges + lavender "+" (user request)
- **Directional specular rim:** `HighlightStyle.Default`, light from the top-left at 45°, falloff 0.7. Width is 2dp on controls and 1.5dp on cards. The edge glints where it faces the light and fades round the far side.
- **Inner glow** (`InnerShadow` in white): 10%/10dp in dark, 42% in light. Light refracted just inside the rim gives the glass visible thickness.
- **Controls regain `depthEffect`.** The lens is 18/26dp, so refraction thickens toward the rim without the old heavy distortion.
- **Cards use Compose `RoundedCornerShape`** because Kyant 2.0.1's highlight shader only reads corner radii from `CornerBasedShape`. With other shapes it fell back to capsule radii.
- **"+" is light-lavender glass:** `accentGlass` is dark #C4B9FF at 75% and light #C9BEFF at 70%, with a deep-violet glyph (`onAccentGlass`).

## Lavender gradient + clearer light controls (user request)
- **The pattern is a linear gradient:** light lavender at the top-right (`patternAccent`, deepened on user request: dark #7B6CF0 at 40%, light #4A35C2 at 30%) fading to neutral at the bottom-left. It is composed on the GPU as `ComposeShader(LinearGradient, BitmapShader(mask), DST_IN)`, so there is still no per-frame rasterising.
- **Light tab bar:**
  - tint 35% → 16%
  - no backdrop brightening
  - inner glow 22%
  - rim 1.25dp
  - droplet 90% → 50% white, so the pattern shows through the bar and the selector
- **"+" is more transparent:** 50% (dark) / 45% (light) lavender, with a white glyph in dark.
- **"Add account"** uses `LiquidPrimaryButton`, the same lavender glass as "+". It sits inside content, so it refracts the background backdrop.

## Why guilloché
It is monochrome, it belongs to the money domain (banknotes, cheques), it reads as print rather than as an AI gradient, and its fine regular lines are exactly what a lens bends visibly.

## Revisit if
- The pattern feels busy behind dense lists in Phase 3/4. If so, lower `pattern` alpha or hide it under list regions.
- The release build misses the frame target. Then cut card lens first.
