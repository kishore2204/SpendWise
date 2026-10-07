---
name: council
description: Convene the SpendWise architecture council on one major decision. Seats several independent expert lenses in parallel, compares their recommendations, decides, and records an ADR. Use only for major decisions (architecture, schema, transaction model, SMS pipeline, parser design, security, design system, final review), not small choices.
argument-hint: <decision to make>
---

# /council

Use this for decisions that are expensive to reverse. For a small choice, decide it directly and mention it in the commit message.

## 1. Frame the decision

Before convening, write a decision brief:
- The decision, in one sentence, plus 2–4 concrete options (always include the "simplest thing" option).
- Fixed context: read `CLAUDE.md` and any relevant ADRs in `docs/adr/`. Copy the facts members need into the brief, because members don't see this conversation.
- Constraints: Android 16 / One UI 8.5, sideloaded, single user, local-only, account isolation, money as paise.
- What each member must output: for each decision, a recommendation, the reasons from that member's lens, risks and what would change their mind, and confidence. Keep it to 400 words or fewer.

## 2. Convene: one parallel batch, every seat independent

Launch the seats in a **single message** as `Plan` subagents, so none can see the others' answers. Give every seat the same brief plus its own lens.

**Core seats (always):**
| Seat | Lens |
|---|---|
| Android Architect | Platform fit, Jetpack/Room correctness, Android 16 behaviour, maintainability |
| Security & Data Engineer | Threat model, key management, data integrity, schema soundness, backup safety |
| Product / UX Architect | Daily friction, mental model, setup cost, failure states for one person |

**Challenge seats (from hex/claude-council role lenses, always):**
| Seat | Lens |
|---|---|
| Devil's Advocate | Challenge assumptions, attack the likely consensus, find what everyone misses |
| Simplicity Champion | Identify over-engineering and complexity that doesn't pay for itself in V1 |

**Situational seats (add when relevant):**
| Seat | When |
|---|---|
| Performance Optimizer | Queries, indexes, startup, rendering, battery, APK size |
| Maintainability Advocate | Module boundaries, naming, testability, future banks/features |
| Compliance / Privacy Officer | Permissions, retention, data leaving the device |
| Data Visualization Specialist | Charts and analytics presentation |
| Android SMS Platform Engineer | Anything involving SMS, notifications or background execution |

**External seats (optional):** if the `claude-council` plugin is installed and has providers configured (check with `/claude-council:status`), you may also ask external models with `/claude-council:ask`. **Only send the abstract decision brief.** Never send SMS text, amounts, merchants, account numbers or any user data.

## 3. Synthesize (your job, not a vote)

1. Put the recommendations in a table: seat, option, confidence.
2. Find where seats agree, and what that agreement rests on. Find where they disagree, and the precise reason for each split.
3. Look for points that only one seat raised (often from the Devil's Advocate) and check whether they hold.
4. Decide on the merits for **this** app. Priority order: correctness → reliability → Android compatibility → privacy → performance → maintainability → UX → simplicity. A minority view wins if it is right.
5. If a split depends on a user preference rather than an engineering fact, pick a default and **ask the user**.

## 4. Record

Write `docs/adr/ADR-NNN-<slug>.md` using the template below, and add it to `docs/adr/README.md`. If the decision creates a non-negotiable rule, add one line to `CLAUDE.md`.

```markdown
# ADR-NNN: <title>
- Status: Accepted | Proposed (needs user input) | Superseded by ADR-XXX
- Date: YYYY-MM-DD
- Council: <seats>

## Context
## Options considered
## Council positions
| Seat | Recommendation | Confidence |
## Decision
## Why (and why not the alternatives)
## Consequences / implementation rules
## Revisit if
```
