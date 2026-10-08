# ADR-015: Period controls and report context
- Status: Accepted
- Date: 2026-10-08
- Council: Android Architect, Security & Data Engineer, Product / UX Architect, Devil's Advocate, Simplicity Champion

## Context
The Home period picker had been placed beside the account switcher, narrowing and truncating the selected account name. The Insights screen placed the selected account above the page title and used a large "View by · Month" glass pill that felt visually awkward. The user asked that the account switcher return to its previous full-width presentation, that Home's picker have its own place, and that both Insights elements be redesigned. Period menus must remain overlays and must not move the balance card. Every report stays scoped to one selected account.

## Options considered
- Put Home's period selector beside the account switcher, inside the balance card, or in its own row.
- On Insights, use a large pill, a compact period trigger beside date navigation, or a labeled compact period row followed by date navigation.
- Keep the account name as an eyebrow, put it below the title, or remove account context.

## Council positions
| Seat | Recommendation | Confidence |
|---|---|---|
| Android Architect | Home separate row; Insights compact selector near date navigation; account context below title | High |
| Security & Data Engineer | Home separate row; Insights compact period control; explicit account context below title | High |
| Product / UX Architect | Home separate row; labeled compact selector on Insights; account selector below title | High |
| Devil's Advocate | Home separate row; separate compact period/date controls; account context below title | Medium-high |
| Simplicity Champion | Home selector inside summary card; compact selector near date navigation; account context below title | High |

## Decision
- Home keeps the account switcher trigger content-sized to the selected account label and digits; its account list remains full width. The period selector gets its own compact row immediately below the account switcher and above the balance summary, with clear spacing before the balance card.
- Insights removes the account eyebrow above the title. Multi-account selection remains below the title; for a single account, show the selected account name and last four digits in a content-sized glass pill below the title, without a "For" prefix.
- Insights uses a modest glass trigger with a plain "Period" label, then a separate date-range navigation row. Its menu and trigger display Day, Week, Month or Year; Home retains Today, This week, This month and This year.
- Both period menus use the screen-level same-window overlay anchored to the trigger. Opening a menu never participates in list measurement or moves underlying cards. Account switching and period selection remain separate controls.

## Why (and why not the alternatives)
The separate Home row protects the account name and keeps period selection outside the balance figure, which does not change with the reporting period. On Insights, the plain label and small trigger make the control's purpose clear without a large glass capsule; keeping the date range separate preserves the distinction between choosing a granularity and moving through dates. Account context belongs under the page title so the title leads, while selected-account details remain visible. A trigger beside the Home switcher caused truncation; placing the period control inside the balance card could imply that the balance itself is period-filtered.

## Consequences / implementation rules
- Do not place the Home period trigger in the account-switcher row. Size the account-switcher trigger to its label while keeping its dropdown full width.
- Keep the Home period row immediately before the balance card; use restrained glass only on the interactive trigger.
- Do not put account identity above the Insights title. Keep account selector/context below the title and preserve account-scoped query state. A single-account context pill must wrap its label rather than fill the available width.
- Use one overlay implementation for both screens; dismiss on outside tap and Back, clamp to safe window bounds, and preserve accessibility selection semantics.
- Verify both themes, scrolling, edge placement, selected state, account switching and unchanged card bounds on the Galaxy A56.

## Revisit if
- Device review shows the separate Home row takes too much vertical space or the Insights control crowds date navigation.
- TalkBack or small-window testing exposes a focus, clipping, or touch-target problem.
