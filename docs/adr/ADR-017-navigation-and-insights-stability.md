# ADR-017: Stable navigation and Insights entry state
- Status: Accepted
- Date: 2026-10-08
- Council: Android Architect, Security & Data Engineer, Product / UX Architect, Devil's Advocate, Simplicity Champion, Performance Optimizer

## Context
The user reported that page changes felt as though something was appearing, and that an Insights card briefly appeared and then disappeared. `NavDisplay` already disables destination enter/exit transitions. Inspection found separate motion in the tab bar and scroll-edge header, while Insights independently collected account, period, range and report data. Its account list also started as an empty list, which could render the no-accounts state before Room emitted the real list. A 176dp loading row was then replaced by report content. The report stamp checks correctly prevent mismatched account or period data from remaining on screen.

## Options considered
1. Keep the existing presentation and add a short destination fade.
2. Keep no destination transition and make Insights loading/layout stable.
3. Consolidate Insights account, period, range and report into one keyed state; remove route-level chrome animations that can look like page transitions.

## Council positions
| Seat | Recommendation | Confidence |
|---|---|---|
| Android Architect | 3; keep Nav3 transitions off, publish screen chrome promptly, key report state | Medium-high |
| Security & Data Engineer | 3; preserve account/range stamps and never reuse prior-account output | High |
| Product / UX Architect | 3; coherent loading/content and no page fade until state is stable | High |
| Devil's Advocate | 3; distinguish actual Nav3 motion from empty/loading and shared-header timing | High on Insights, medium on header |
| Simplicity Champion | 2/3; simplest fix is no page transition plus a stable keyed Insights state | High on Insights |
| Performance Optimizer | 3; avoid duplicate observers and keep `flatMapLatest` cancellation | Medium-high |

## Decision
Keep `NavDisplay` destination transitions disabled. Publish the active `LargeTitleScreen` state to the shared glass top bar synchronously and render that bar without a second enter/exit fade. Show the bottom navigation immediately when its route is active; retain only the tab selection's local liquid movement. Model Insights controls and report as one immutable state keyed by the selected account, period and date range. Do not render the empty state until the accounts flow has emitted, and do not show report data until all report stamps match the current key.

## Why (and why not the alternatives)
Adding a fade would add motion without fixing the report state race and could make translucent screens seem to overlap. The bottom bar's visibility animation is separate from `NavDisplay` but is route-level motion, so it is removed while its selected-tab animation remains a local interaction. A single keyed Insights state prevents mixed snapshots and avoids a false no-accounts screen while Room initializes. Stale data is not retained as a visual shortcut because that could show figures for a previous account or range.

## Consequences / implementation rules
- Keep NavDisplay enter, exit and pop transitions disabled unless device evidence identifies a specific need.
- Do not animate route-level tab-bar appearance or the shared scroll-edge overlay; retain local control feedback.
- Insights must publish its account, range, period, loading status and matching report together.
- Keep `flatMapLatest` and validate account ID, range endpoints and period before showing report data.
- Distinguish “accounts have not loaded” from “there are no accounts.”
- Verify tab switching, opening Transactions, account changes and Insights refresh on Samsung Android 16 / One UI 8.5.

## Revisit if
- Device testing after these changes still shows destination motion or a report replacing itself.
- A future navigation design adds a deliberate route transition with clear product approval and tested glass/backdrop behavior.

## Loading presentation refinement (2026-10-08)
The user asked why the Insights loading state appears and requested a council alternative. Room asynchronously emits the account list and the report for the selected account and calendar range. The report's account, range and period stamps must match before any figures are shown. The earlier 176dp centered spinner left a large gap, then vanished as report cards appeared.

| Seat | Recommendation | Confidence |
|---|---|---|
| Android Architect | Keep route transitions off; render page context immediately with a stable, compact report wait state | High |
| Security & Data Engineer | Change presentation only; don't retain figures across a changed report key; use one compact status surface | High |
| Product / UX Architect | Keep page controls visible and make the wait state smaller and report-scoped | High |
| Simplicity Champion | Use a quiet placeholder; avoid adding cache/timer machinery | High |
| Devil's Advocate | Prefer a fixed 56dp neutral status row over skeletons or a blank gap; preserve exact report-key validation | High |

Decision: replace the spinner block with one 56dp neutral glass status row, “Preparing this account’s insights”. Keep account/period controls present, use a small neutral account-context placeholder only before the account flow emits, and show figures only after the selected account, range and period match. The status is a polite accessibility live region. No stale values, shimmer, report-card skeletons, loading delay timer, or navigation transition are added.

Why: a compact fixed-height status communicates a real local Room read without impersonating financial content. Skeleton cards suggest particular values/charts will appear and add visual weight; retaining old report data can be misleading; an empty gap provides no explanation on a slower query. If device testing shows a distracting flash, first re-evaluate whether the status surface is needed; do not add a delay or cache policy without evidence.
