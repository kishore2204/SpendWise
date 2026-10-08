# ADR-016: Comfortaa typography across the application

**Status:** Accepted  
**Date:** 2026-10-08

## Context

SpendWise needs a distinctive, calm display face without compromising financial readability, script support, scaling, or offline operation. The existing type scale was centralized in `ui/theme/Type.kt`, but its comment referred to an Inter font that was not present. No custom font resources existed before this decision.

## Council review

Five independent lenses reviewed the font role and fallback strategy:

- **Android Architect:** bundle the variable font locally, use its actual weight axis, and avoid downloadable font providers.
- **Security & Data:** use a trusted upstream artifact and ship its OFL license; keep financial data and fonts fully offline.
- **Product / UX:** use Comfortaa selectively for headings and concise controls; keep dense body copy and financial strings familiar and easy to scan.
- **Devil's Advocate:** verify glyph coverage and numeral behavior before applying it to currency; do not assume the rupee sign or Indian scripts are supported.
- **Simplicity Champion:** extend the existing Material typography scale instead of adding a second styling system; use platform fallback for missing scripts.

## Decision

**Updated at the user's direction on 2026-10-08:** use Comfortaa across every Material 3 typography role and all financial amount styles, including small copy, transaction amounts, and the rupee sign. Use the font's supported variable weights, with page display titles at Bold (700). Android's normal font fallback handles scripts absent from Comfortaa; no script-specific font is bundled.

The source font is the official Google Fonts Comfortaa variable TTF (`ofl/comfortaa/Comfortaa[wght].ttf`). The checked-in artifact has SHA-256 `0fc3f45dc48b614db9c39181502544b37217ecbf8bee2fb35886992bc96c5bd3`; the adjacent `docs/licenses/Comfortaa-OFL.txt` contains its SIL Open Font License 1.1. Network DNS was unavailable during a pinned-revision re-fetch, so the downloaded artifact could not be compared against a specific repository commit in this environment.

Inspection found the font's `wght` axis spans 300–700. It covers Latin UI text and U+20B9 Indian rupee, but lacks Tamil and Devanagari glyphs. Its numeral advances are proportional and it has no `tnum` feature. The user's preference for one typeface takes priority over tabular digits; Tamil and Hindi continue to use Android's normal script fallback.

## Alternatives considered

1. **Comfortaa everywhere:** selected after the user's explicit follow-up; it gives a consistent typeface while Android supplies missing-script glyphs.
2. **Keep financial values on the system family:** would preserve tabular numerals, but conflicts with the user's request for Comfortaa throughout.
3. **Bundle separate Tamil/Hindi fonts:** rejected for V1; Android already provides platform fallback and extra assets add maintenance without improving Comfortaa coverage.

## Consequences

- The current Material 3 scale remains the source of typography tokens; all 15 Material roles and all financial styles use Comfortaa.
- Accessibility scaling remains enabled through `sp` units.
- The custom font is bundled locally; no runtime network or font service is used.
- Period controls widen with the system font scale. At scales where four bottom-navigation labels would collide, labels hide visually while each tab retains an explicit TalkBack label.
- No screen disables font scaling. Large-text visual checks focus on preserving access to the selected period and all financial values.
- Comfortaa's proportional figures can vary slightly in width as values change. The rupee sign matches the amount size and is followed by a thin space for clearer separation.

## Validation notes

Static font inspection verified Latin and rupee glyph coverage and identified missing Tamil/Devanagari coverage, which use Android fallback. Large-text layouts retain system scaling. This typography change affects rendering only; monetary values and calculations remain integer paise.
