# ADR-007: Design system: "Light-glass"
- Status: Accepted. Hex values must pass a contrast check in Phase 2; library versions must be verified when the project is created.
- Date: 2026-10-07
- Council (Design): Senior Product Designer, Compose UI + Performance Engineer, Devil's Advocate + Simplicity Champion

## Principle
**Premium comes from restraint, alignment, number typography and speed. Effects are seasoning.**

The glass look mostly comes from *light*: edge highlights, tint, and ambient colour behind surfaces. Blur appears only where content actually moves underneath. This was the Devil's Advocate's main point, and the other two seats agreed with it once it was raised.

## Glass placement
| Treatment | Where |
|---|---|
| **Live blur** (Haze), at most 2 layers at rest | Floating bottom-nav pill; the top bar once content scrolls under it |
| **In-window blurred overlay** | The account switcher. The pill expands into a stack of account cards, the signature moment. |
| **Light-glass** (tint + 1dp top highlight + ambient glow behind, no blur) | Balance hero card, "+ Expense" FAB, pending-review card (amber tint), snackbars |
| **Solid** | Transaction lists, forms, settings, chart plot areas, dialogs. Bottom sheets: opaque M3 `ModalBottomSheet` with a dimmed backdrop, for accessibility and simplicity. |

**Money figures always sit on surfaces that are at least 90% effectively opaque.** Hero contrast must be ≥ 7:1 and all other text ≥ 4.5:1, measured against the worst-case backdrop.

## Colour tokens (draft; contrast check required)
| Token | Dark (AMOLED) | Light |
|---|---|---|
| background | `#030305` | `#F6F5FA` |
| surface 1 / 2 / 3 | `#0E0E14` / `#16161F` / `#1E1E2A` | `#FFFFFF` / `#EFEDF6` / `#E6E3F0` |
| glass tint | `#1A1830` @ 70% | `#FFFFFF` @ 80% |
| glass edge highlight | `#FFFFFF` 14%→3% gradient, 1dp | `#FFFFFF` 100% + `#1B1530` 6% hairline |
| ambient light (static) | `#6E5BFF` @ 14% top-left, `#3B6BFF` @ 10% bottom-right | `#8B7BFF` @ 8% |
| primary / container | `#B7A6FF` / `#2E2560` | `#5B43D6` / `#E7E1FF` |
| income | `#4FD1B0` | `#0B8468` |
| expense | `#FF9A6B` | `#C0521A` |
| transfer | `#8FB4FF` | `#2F62D9` |
| pending | `#F5C451` | `#9A6400` |
| text 1 / 2 / 3 | `#F2F1F7` / `#A9A7B8` / `#6E6C7E` | `#15131F` / `#55526A` / `#85829A` |

- **Colour-blind safe.** Income and expense use teal vs coral, which differ on both hue *and* lightness. Every amount also carries a sign (+ or −) and a direction glyph.
  - In lists, expenses use text 1 with "−", and only income is tinted. This keeps dense lists calm.
- **Glow** is used for one element only: the selected nav item.
- **Material You dynamic colour is off.** The brand violet is the identity.
- **The Light theme is designed separately:** no live blur except the nav pill at 80% white; white cards on `#F6F5FA` with one soft shadow (y8, blur 24, `#1B1530` at 8%).

## Typography
- **Inter (variable, bundled)**, with `tnum` tabular figures for all money. In Phase 2, verify that the ₹ glyph (U+20B9) is present. If it isn't, fall back to the system font for ₹ only. Reject any font that lacks tabular figures.
- Scale: hero balance 44sp/52 weight 600 (₹ at 60% size, paise at 55% in text 2) · header 34/40 weight 600 (One UI large header) · title 22/28 · title-s 17/22 · body 15/22 · label 13/18 weight 500 · caption 12/16.
- Long values shrink to fit, down to 32sp. At font scale ≥ 1.5, values abbreviate (₹12.4L). Grouping is Indian (1,23,456), via `en-IN` ICU formatting.

## Shape, spacing, depth
- Corner radii 8/12/16/24/32/full. Cards 24, sheets 32, grouped lists 20, pills full.
- 4dp grid; screen margins 20dp.
- **One UI reachability:** top-level screens open with the large header about 28% of the way down, which collapses on scroll. Primary actions are bottom-weighted.
- Depth: L0 background + ambient → L1 solid content → L2 floating glass → L3 sheets, with a 55% scrim.

## Motion: calm and precise, not bouncy
- Durations come from tokens: 150 / 250 / 350 ms, using emphasized easing. Springs are used **only** for direct manipulation (sheets, the pill morph).
- **The balance never counts up on app open**; it shows the final value immediately. Digits roll only when the value *changes* (a new transaction, an account switch), and only the changed digits move, with a 350 ms cap.
- Account switch: the pill morphs (shared bounds) and the content cross-fades and slides 24dp over 250 ms, with a light haptic.
- Confirm a transaction: press scales to 0.97 → a check morph (180 ms) → the card collapses into the list, with the CONFIRM haptic. Ignore = swipe out, with the REJECT haptic.
- Theme switch: a 250 ms crossfade, with `configChanges="uiMode"` so the Activity isn't recreated.
- **Reduced motion** (animator scale 0, or the system's Remove animations setting): every animation becomes a ≤ 100 ms fade, and numbers jump straight to their value.

## Implementation
- **Haze** (`haze`, `haze-blur`) for live blur. `haze-glass` refraction stays **off** in V1, behind a flag (it's experimental).
- Components: `GlassScaffold` (one `HazeState` per screen, exposed through `LocalGlassState`), `GlassBar`, `GlassPill`, `LightGlassSurface`.
  - `GlassStyle` presets (Regular, Thin, Hero) are `@Immutable` and come from the tokens.
  - No glass inside list rows.
- **Theme:** `SpendWiseTheme` wraps `MaterialTheme` with a brand ColorScheme and adds a *thin* `LocalSpendWiseTokens` (income, expense, transfer, pending, glass tint/highlight, ambient, motion, money type). Brand meanings never live in M3 roles.
- **Charts:** Vico for axis charts (trend bars and lines). Custom Canvas (`drawWithCache`) for the category donut and the small sparklines. With fewer than 5 data points, show a sentence instead of a chart.
- **Automatic fallback to light-glass (no blur)** when any of these is true: power saver, thermal status ≥ MODERATE, animator scale 0, or the in-app "Reduce transparency" setting. The tint is chosen so that the fallback looks almost identical.
- **Performance gate:** FrameTimingMetric P95 ≤ 8.3 ms while scrolling 5,000 rows on the user's phone. Use baseline profiles and R8 full mode.
- **Edge cases:**
  - 0 accounts → the pill becomes "Add account".
  - 1 account → a static label with no switcher.
  - Many accounts → a scrolling card stack.
  - First run → one clear call to action, no glow.

## Versions (from the Compose seat, checked against Oct 2026 sources; re-verify in Phase 2)
Compose BOM 2026.09.00 (Material3 1.4.x), Kotlin 2.4.x, Navigation3 1.2.x, Haze 2.0.x, Vico 3.3.x. **minSdk 33** (AGSL guaranteed), target/compile SDK 36. Don't use alpha Material3.

## Rejected
- **Glass on lists, forms or the hero balance:** hurts readability, and blur over black is invisible anyway.
- **Kyant liquid-glass as the primary library:** single maintainer, a recent rewrite, and fewer performance controls.
- **A hand-rolled RenderEffect:** reinvents Haze.
- **Pure `#000` background:** OLED smear when scrolling.
- **Red/green for income and expense.**
- **A 20 s ambient drift and phone-tilt highlights in V1:** battery cost for little gain; candidates for Phase 8.
- **Count-up animation on every app open.**
