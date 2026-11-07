# Docs index

Every planning/record doc, grouped by purpose. New here? Read in this order: **AI_HANDOFF → SUMMARY →
research/architecture-and-patterns → UX_RESEARCH_AND_GAPS → WORK_QUEUE**. Updated 7 Nov 2026.

## Start here
- [AI_HANDOFF.md](AI_HANDOFF.md) — repos, live services, where creds live, how to run, full lineage, pending. **First read.**
- [SESSION_LOG_2026-11-07.md](SESSION_LOG_2026-11-07.md) — the full 6-7 Nov session: every owner request/instruction, decisions, what shipped, pending, next steps.
- [SUMMARY.md](SUMMARY.md) — one page: what it is, built, live, deploy status, plan.
- [research/architecture-and-patterns.md](research/architecture-and-patterns.md) — system model, stack, data flow, why.

## Product & UX direction
- [PRODUCT.md](PRODUCT.md) — the product definition. [PRODUCT_REVIEW.md](PRODUCT_REVIEW.md) — user-centric review + roadmap.
- [UX_RESEARCH_AND_GAPS.md](UX_RESEARCH_AND_GAPS.md) — competitor research, flow gaps, platform-limit vs our-choice, priorities.
- [flows/send-first-spec.md](flows/send-first-spec.md) — P0 buildable flow (tap = send). [flows/onboarding-spec.md](flows/onboarding-spec.md) — P1 first-run.
- [GROWTH_AND_MONETIZATION.md](GROWTH_AND_MONETIZATION.md) — positioning, acquisition, retention, clean monetization, milestones.
- [PLAY_STORE_LAUNCH.md](PLAY_STORE_LAUNCH.md) — launch checklist: tracks, AAB, data-safety/IARC, IP risk, QA blockers.

## Research library (why/how, with links)
- [research/README.md](research/README.md) — index + method. [research/watermark-handling.md](research/watermark-handling.md) ·
  [research/animated-stickers.md](research/animated-stickers.md) · [research/deduplication.md](research/deduplication.md).

## Quality, reliability & plan
- [REVIEW_2026-11-07.md](REVIEW_2026-11-07.md) — the "car wash": ~60 verified code findings (admin/worker/app) + status.
- [OPERATIONS_AND_RELIABILITY.md](OPERATIONS_AND_RELIABILITY.md) — silent-failure modes, health/alerting, content lifecycle.
- [WORK_QUEUE.md](WORK_QUEUE.md) — the ordered plan (review fixes → product work → later → owner-only).
- [TEST_MATRIX.md](TEST_MATRIX.md) — what's tested where.

## Going live & operating
- [GO_LIVE_STATUS.md](GO_LIVE_STATUS.md) — what's live, the DB changes made, the two owner actions for 24/7.
- [DEPLOY.md](DEPLOY.md) — Vercel/Pages for site + admin. [ORACLE_VM.md](ORACLE_VM.md) — the collector VM.
- [HANDNAVIGATE_PLAN.md](HANDNAVIGATE_PLAN.md) — collection integration. [CONTENT_STRATEGY.md](CONTENT_STRATEGY.md) — topics/voice/safety.
- [CONTENT_POLICY.md](CONTENT_POLICY.md) — safety rules. [IOS.md](IOS.md) — iOS notes.

## Reference & history
- [ARCHITECTURE.md](ARCHITECTURE.md) — component detail. [STATUS.md](STATUS.md) — verified state. [V1_FEATURE_PARITY.md](V1_FEATURE_PARITY.md) — v1→v2.
- [BACKLOG.md](BACKLOG.md) · [OWNER_REQUESTS.md](OWNER_REQUESTS.md) · [WORKLOG.md](WORKLOG.md).

## Conventions
Secrets live only in the git-ignored `.env` (never committed; the repo is public). Small tested commits, each pushed.
Guardrails in the repo root `AGENTS.md` hold even under "just do it". Validate: worker=pytest, site=smoke.py, app=
jvmTest+device, admin=CI.
