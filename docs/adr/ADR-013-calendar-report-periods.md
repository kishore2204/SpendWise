# ADR-013: Shared calendar periods for Home and Insights
- Status: Accepted
- Date: 2026-10-08
- Council: Android Architect, Security & Data Engineer, Product / UX Architect, Devil's Advocate, Simplicity Champion

## Context
Home currently shows this-month income and spending. Insights is month-oriented, while the user wants daily and weekly views as well as a Home duration selector. Every figure must remain scoped to the selected account.

## Options considered
1. Add separate ad hoc date logic to each screen.
2. Share a small calendar-period model for day, week, month and year.
3. Add arbitrary custom date ranges and stored rollup tables immediately.

## Council positions
| Seat | Recommendation | Confidence |
|---|---|---|
| Android Architect | Shared day/week/month/year periods with explicit IST boundaries | High |
| Security & Data Engineer | Epoch-day range queries with one `accountId`; Monday–Sunday weeks | High |
| Product / UX Architect | Clear period labels, range navigation in Insights, concise Home dropdown | High |
| Devil's Advocate | Use half-open date semantics; test midnight and calendar transitions | High |
| Simplicity Champion | Reuse current aggregate queries; avoid custom-range/schema complexity for this release | High |

## Decision
Introduce one `ReportPeriod` range helper used by both screens. Day, week, month and year are calendar periods in `Asia/Kolkata`; weeks run Monday through Sunday. Insights can navigate backward/forward through those periods and defaults to the current month. Home offers Today, This week, This month and This year, defaulting to This month. Budgets remain visible only for a selected month.

## Why (and why not the alternatives)
Shared definitions prevent Home and Insights from disagreeing at boundaries and keep tests focused. Arbitrary custom ranges and precomputed rollups are unnecessary for day/week support and thousands of local transactions.

## Consequences / implementation rules
- DAO reads continue to take one explicit account ID and include only confirmed analytics rows.
- Date ranges are inclusive `LocalDate`/epoch-day ranges in IST; week start is Monday.
- The current day, week, month and year end at today (to date); weeks begin Monday. Historical navigation selects full calendar periods.
- Insights comparison uses the immediately preceding range of the same calendar type; current week/year comparisons use the same elapsed span to date.
- Home's balance remains the current balance; only its income/spending summary follows the chosen period.
- Tests cover Sunday/Monday, month/year transitions, leap days and account isolation.

## Revisit if
The user asks for arbitrary custom ranges or performance measurements show the current account-scoped aggregate queries need rollups.
