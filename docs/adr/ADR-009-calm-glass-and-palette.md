# ADR-009: Calm glass and a minimal palette. Revises ADR-008
- Status: Accepted
- Date: 2026-10-07
- Council (Glass & Colour): Visual/Product Designer, Compose Graphics Engineer, Accessibility & Contrast, Devil's Advocate, Restraint Brand Designer
- Evidence: 10 device screenshots (light and dark × glass and fallback, Home, More, scrolled), plus a code audit of `Glass.kt`, `LiquidTabBar.kt`, `Components.kt` and `Screen.kt`.

## Context
User feedback on ADR-008's build: *"light is too white, the glass isn't visible; dark is too colourful; don't use many colours; don't make it look AI-made."*

## Council findings (5/5 unless noted)
- **Card glass was invisible by construction.** Cards sampled only the smooth aurora gradient. A lens shifts content by about 95px, and the gradient's pools are about 1400px wide, so the colour changes by less than 5%. Blur and tint then removed what little was left. The cards weren't broken; they simply had nothing to refract. (Graphics audit)
- **Dark "too colourful":** `vibrancy()` boosted saturation on aurora pools at 44–65% alpha, under only a 28% tint, so cards came out royal blue. The badges, accent and aurora added up to about 9 hues on one screen.
- **Light "invisible":** the card was only about 2% brighter than the background. The white rim (Plus blend) clipped to white, and the shadow was 10% over 24dp, so the card had no visible edge.
- **"AI-made" signals:** the violet→blue→teal→magenta mesh, lavender everywhere, a different pastel colour on every icon badge, the same glowing card for everything, and a slogan headline with a feature list on Home.
- **Audit bugs:**
  - The top bar wasn't glass at all.
  - The hidden accent row ran a full glass pass every frame and refracted the bar twice.
  - The "+" scale distorted its own sample.
- **Accessibility failures:**
  - text3 on glass cards.
  - The selected droplet and segmented thumb (about 1.05–1.4:1).
  - Paise at about 7.7sp.
  - Light income and expense colours with equal luminance.

## Decision
1. **Delete the aurora and `vibrancy()`.** The background is neutral in both themes: dark `#050507` and light `#EDEDF0`, each with one soft, hue-free light at the top.
2. **Glass only on the floating layer:**
   - tab bar, "+" button, top bar (now real glass: a blurred scroll edge masked to fade downward);
   - each refracts the real content scrolling beneath it;
   - neutral tint (dark `#17171B` at 55%, light `#FAFAFC` at 60%), blur 6dp, lens 24/40dp with depth effect, `colorControls` near-neutral, 1dp highlight, hairline border, theme-aware shadow.
3. **Content cards are solid** (`surfaceCard`): `#141417` / `#FFFFFF`, with a glass-like edge (specular top rim → hairline) and a soft shadow in light. The vote was 2 solid, 1 tinted-glass, 1 lens over pattern, 1 ≥55% glass. Solid wins because card glass has nothing to refract, and money must stay at least 7:1.
4. **Minimal palette:** a neutral ramp, one accent (dark `#A99BFF` / light `#5038C8`), and income/expense only where money appears (dark `#4CD4A0`/`#FF7A5C`, light `#00704F`/`#A8321A`, with a luminance gap). Transfers are neutral. Pending is amber, shown only for that state.
5. **Monochrome icon badges** (neutral fill, text2 glyph).
6. **Accent appears only** on the primary action, the current selection, and the "+" button (a solid accent with glass edges).
7. **Selection that is actually visible:**
   - the droplet's resting fill sits under the accent-tinted icon;
   - the segmented thumb is a raised white/grey pill with SemiBold text1;
   - tab labels are 12sp at full opacity.
8. **Money legibility:** ₹ and paise at 0.7em minimum. text3 is used for disabled or meta text only.
9. **Copy:** plain and direct. No slogans, no feature list on Home, no phase numbers in user-facing text.

## Rejected
- **A line or texture pattern under the glass** so the lens would show (Graphics seat). Devil's Advocate and the Restraint seat called it a gimmick, and it would sit behind money.
- **A "tasteful" replacement gradient.** It would still be a wash.
- **Darker or grey light-theme glass.** It reads as dirty plastic.
- **Chromatic aberration at rest.** It is kept only while the droplet is pressed.

## Consequences
- On empty screens there is little under the floating glass, so the effect is subtle **by design**. It becomes clearly visible once Phase 3/4 lists and amounts scroll beneath the bar.
- GPU load drops: `drawBackdrop` passes per frame go from about 6 to 4 (tab capsule, droplet, "+", top bar), and the hidden layer is now flat.
- Revisit the balance card in Phase 3: solid, with at most one accent glow inside it (the Visual Designer proposal).
