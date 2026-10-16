# Phase 05 Runtime Validation and Acceptance

**Generated:** 2026-10-16  
**Target:** `D:\TTDN\competency_assessment_and_analysis`  
**Environment:** Java 21.0.7, PostgreSQL 16.15, Node.js 24.19.0, Playwright 1.64.1, Chromium

## Summary

| Check | Status | Evidence |
|---|---|---|
| Java / PostgreSQL runtime | PASS | Spring Boot startup logs show Java 21.0.7 and PostgreSQL 16.15 |
| Maven full suite | PASS | Exit 0; 54 tests, 53 passed, 0 failed, 0 errors, 1 skipped |
| PostgreSQL-backed integration | PASS | Assessment and Question Bank integration suites ran against isolated PostgreSQL 16.15 |
| Baseline and migrations V001–V008 | PASS | Applied in order to a disposable PostgreSQL database; V003/V004 constraints and V008 triggers asserted |
| Frontend lint | PASS | Exit 0; 0 errors, 2 `<img>` warnings in `components/exam-player.tsx` |
| TypeScript typecheck | PASS | `npm run typecheck`, exit 0 |
| Next.js production build | PASS | `npm run build`, exit 0 |
| Mocked Playwright | PASS | 5 passed, 0 failed, including autosave, unsaved-submit prevention, structured table and chart rendering |
| Real HTTP Playwright | PASS | 2 passed, 0 failed; browser requests traversed Next.js, Spring Boot, JWT, and PostgreSQL |
| `git diff --check` | PASS | Exit 0 |

The optional real-E2E fixture-seeding test is skipped during the regular Maven run and was invoked separately with a synthetic password in the isolated test database. Synthetic approvals and fixtures were not written to an operational database.

## Question Group and Shared Stimulus

- V008 adds `question_groups`, immutable versioned group snapshots, ordered Question Version membership, review evidence, provenance and rights evidence, plus `test_items.question_group_version_id`.
- Database triggers enforce published group and membership immutability, same-Section membership, matching Test Item/group snapshots, and a publication gate requiring at least two eligible, rights-approved Question Versions.
- Teacher service operations create groups and versions, add/remove draft members, record review decisions, and validate/publish; publication remains behind human-review and rights-evidence gates.
- Test Builder selects an eligible published group atomically, preserves member order, stores the group-version reference on each Test Item, and rejects a partial group when the requested count cannot fit it.
- Exam Player returns only the published shared stimulus and group order/count, not correct answers or explanations.
- PostgreSQL integration tests cover passage, structured table/chart payload validation, group ordering, atomic selection, insufficient capacity, publication eligibility, and published snapshot immutability.
- Mocked browser tests verify semantic table and chart rendering. Real browser E2E builds, publishes, starts, and renders separate structured TABLE and CHART Question Groups through the real HTTP path, in addition to the three-question passage group and image/table/chart visual assets.

## Real HTTP Evidence

The real browser suite ran against **Next.js → Spring Boot Java 21 → PostgreSQL 16.15**:

- Teacher JWT creates Diagnostic and HSA branch blueprints and builds tests; invalid, mixed, and incompatible HSA branch/release selections are rejected.
- Admin JWT publishes eligible test versions; Student access to Teacher routes is forbidden. Invalid/expired JWTs are rejected.
- The Teacher reads the published Question Group snapshot, verifies its three ordered members, and cannot append a member after publication.
- Student registers, reads the catalog, starts the published grouped test, sees the shared passage and ordered questions, and receives no answer key or explanation.
- Teacher builds and Admin publishes separate tests using synthetic TABLE and CHART groups; Student starts each through the catalog and the Exam Player renders the structured datasets as accessible tables.
- Browser coverage exercises network interruption, retry/idempotency, stale revision, autosave confirmation, mark-for-review, clear answer, reload/resume, manual submit, duplicate submit, and server-deadline auto-submit.
- PostgreSQL read-back confirmed the real student attempt was submitted with a persisted answer; the timeout flow also reached `AUTO_SUBMITTED`.

## API Surface

Backend API routes are rooted at `/api`:

- **Teacher Question Groups:** `POST /teacher/question-groups`, `POST /teacher/question-groups/{groupId}/versions`, `GET /teacher/question-groups`, `GET /teacher/question-group-versions/{versionId}`, `POST /teacher/question-group-versions/{versionId}/items`, `DELETE /teacher/question-group-versions/{versionId}/items/{questionVersionId}`, `POST /teacher/question-group-versions/{versionId}/reviews`.
- **Admin publication:** `POST /admin/question-group-versions/{versionId}/publish`.
- **Teacher assessment:** `GET /teacher/assessments/exam-versions`, `GET|POST /teacher/assessments/blueprints`, `POST /teacher/assessments/blueprints/{blueprintId}/versions`, `POST /teacher/assessments/tests/build`.
- **Admin assessment:** `POST /admin/assessments/test-versions/{testVersionId}/publish`.
- **Student assessment:** `GET /student/assessments/tests`, `POST /student/assessments/test-versions/{testVersionId}/attempts`, `POST /student/assessments/attempts/{attemptId}/resume`, `GET /student/assessments/attempts/{attemptId}`, `POST|DELETE /student/assessments/attempts/{attemptId}/items/{itemId}/answer`, `POST /student/assessments/attempts/{attemptId}/items/{itemId}/review`, `POST /student/assessments/attempts/{attemptId}/submit`.
- **Authentication:** `POST /auth/login`, `POST /auth/register`.

The browser reaches these routes through the Next.js `/api/assessments/[...path]` proxy.

## Test Evidence

- Maven reports: `backend/target/surefire-reports/`
- Mocked Playwright: `playwright.config.ts` — 5 passed
- Real HTTP Playwright: `playwright.real.config.ts` — 2 passed
- Migration validation: baseline plus V001–V008 and `database/tests/V003_V004_constraints.sql` on a disposable PostgreSQL 16.15 container
- `git diff --check` exited 0

## Readiness

- **Technical Readiness:** **PASS** for Phase 05 Assessment Engine, versioned Question Groups, shared stimulus, Test Builder integration, Exam Player, attempt persistence, authentication, and RBAC. No P0/P1 blocker was observed in the tested scope.
- **Content Readiness:** **NOT READY**. Acceptance used synthetic, test-only fixture content and approval evidence; this does not establish readiness of TSA/HSA operational content.
- **Phase 06:** **GO** from the technical Phase 05 gate. This does not imply publication approval or TSA/HSA content readiness.

## Runtime Verdict

```yaml
environment:
  docker: AVAILABLE — isolated PostgreSQL 16.15 container
  node: AVAILABLE — 24.19.0
  playwright: AVAILABLE — 1.64.1 with Chromium
  infra-tier: PRIMARY(Docker-based) — disposable PostgreSQL 16.15
  browser-tier: PRIMARY(Playwright) — mocked and real HTTP suites
startup: PASS — Spring Boot Java 21.0.7 and Next.js production server responded
integration: PASS — exit_code: 0, passed: 53, failed: 0, skipped: 1; PostgreSQL 16.15-backed
e2e: PASS — exit_code: 0, real HTTP 2 passed and mocked 5 passed; Next.js/Spring Boot/JWT/PostgreSQL exercised
overall: PASS — Phase 05 technical scope verified; synthetic-only content remains not ready for publication
```

# Phase 06 Scoring and Analytics Acceptance

**Generated:** 2026-10-16  
**Target:** `D:\TTDN\competency_assessment_and_analysis`  
**Environment:** Temurin Java 21.0.12.1, PostgreSQL 16.15, Node.js 24.19.0, Playwright 1.64.1

## Summary

| Check | Status | Evidence |
|---|---|---|
| Baseline and migrations V001–V009 | PASS | Applied in order on a clean isolated PostgreSQL 16.15 database; V003/V004 constraint assertions passed |
| Maven full suite | PASS | Exit 0; 66 tests, 0 failures, 0 errors, 1 skipped (optional synthetic fixture seeder); Java 21.0.12.1 against PostgreSQL 16.15 |
| PostgreSQL scoring persistence | PASS | Weighted multi-Skill, unmapped/ineligible mapping, persisted timing, concurrency and idempotency assertions passed |
| Real HTTP Playwright | PASS | Exit 0; 2 passed, 0 failed in 1.4 minutes against Next.js → Spring Boot Java 21 → PostgreSQL 16.15 |
| Mocked Playwright | PASS | Exit 0; 5 passed, 0 failed after production build |
| `npm run lint` | PASS | Exit 0; 0 errors, 2 existing `<img>` warnings in `components/exam-player.tsx` |
| `npm run typecheck` | PASS | Exit 0 after final E2E assertions |
| `npm run build` | PASS | Exit 0; Next.js production build completed as part of `npm run test:e2e` |
| `git diff --check` | PASS | Exit 0 |

The current real HTTP run used isolated synthetic fixtures only. Student and Teacher analytics APIs and dashboard filters were exercised separately from mocked Playwright coverage. Test 1 exercised Teacher JWT blueprint/build and Admin JWT publication; Test 2 exercised the Student scoring and analytics flow.

## Scoring and Persistence Evidence

- Scoring policy: `objective-exact-v1`; analytics policy: `evidence-mean-v1`.
- Real HTTP TSA result: score `0/3`, accuracy `0`, 1 answered (incorrect), 2 skipped. Real HTTP HSA result: score `1/1`, accuracy `1`, 1 answered. PostgreSQL read-back confirmed separate submitted attempts and scoring runs for TSA and HSA; the HSA attempt was not included in the selected TSA cohort.
- The isolated database read-back found two immutable scoring runs for the same submitted attempt, corresponding to distinct request IDs. Both had the same input SHA-256, score, question counts, taxonomy release, and provenance. The HTTP retry with the same request ID returned the existing run ID.
- Each run persisted 3 question rows, 1 section row, 1 skill row, 1 competency row, and 6 dimension rows. Provenance recorded taxonomy release, Question Version IDs, Mapping Set IDs, normalized mapping evidence, and the policy interpretation.
- New PostgreSQL tests assert that questions mapped to two Skills with inverse weights produce weighted observed accuracy of `0.8` and `0.2`; a correct answer with zero mapping weights creates no Skill evidence, and overall attempt accuracy remains `0.6667`.
- Unmapped questions still count toward exam score. Draft and zero-weight mappings produce no Skill result and are recorded as ineligible, distinct from unmapped Question Versions in persisted provenance.
- Concurrent scoring with the same request ID returns one Scoring Run and one question-result row; a pre-submit scoring failure writes no run, and retry after submission succeeds.
- Per-question time is `NULL` when absent and remains `NULL` in section results. A stored test-only value of 17 seconds is reflected in question and section results; autosave timestamps are not used as timing evidence.
- Scoring is internal exact-match: multi-choice requires exact set match; numeric tolerance is applied only when specified in answer data; text uses NFC normalization and trimmed, case-sensitive matching; boolean text is case-insensitive. There is no partial credit. Accuracy divides correct answers by all questions, including skipped questions. Mastery equals observed mapped accuracy; confidence `n/(n+3)` is an evidence indicator, not a probability. Error causes are not inferred.

## API Surface Exercised

Backend scoring and analytics endpoints (under `/api`):

- `POST /student/assessments/attempts/{attemptId}/scoring-runs`
- `GET /student/assessments/attempts/{attemptId}/scoring`
- `GET /teacher/assessments/attempts/{attemptId}/scoring`
- `GET /student/analytics` (filters: Exam, Exam Version, Test Version, Taxonomy Release, Scoring Version, Analytics Version)
- `GET /teacher/analytics` (same cohort filters; existing teacher ownership scope retained)

The real HTTP browser flow verified student results/dashboard and teacher analytics/dashboard. Both API responses exposed TSA and HSA as separate available exams; Student defaulted to the latest HSA result, and explicitly filtering to TSA returned only the TSA cohort. Teacher HSA and TSA filters each returned the corresponding single-test cohort, and the browser dashboard switched between them without mixing results. Teacher ownership scope, cross-student access, invalid/expired JWTs, and scoring before submission were rejected. V009 stores append-only scoring runs, question/section/skill/competency/dimension results, version identifiers, input fingerprint and provenance.

## Remaining Limits

- Per-question time ingestion is not supplied by the current autosave flow. Missing time remains `NULL`; median response time and time-pressure claims remain unsupported without a valid timing source.
- Fixture content and approval evidence are synthetic and isolated. This run does not establish operational TSA/HSA content readiness.

## Readiness

- **Technical Readiness: PASS.** Scoring, provenance, cohort isolation, Student/Teacher filtering, persistence, RBAC, and real HTTP browser flows passed the recorded checks. Time analytics remain limited to supported persisted evidence; no P0/P1 technical blocker was observed.
- **Content Readiness: NOT READY.** Acceptance data and approval evidence were synthetic and isolated; this is not evidence that operational TSA/HSA content is reviewed or publishable.
- **Phase 07: GO on the technical dependency gate only.** Content readiness remains separate. No Phase 07 implementation was started in this task.

## Phase 06 Runtime Verdict

```yaml
environment:
  docker: AVAILABLE — isolated PostgreSQL 16.15 acceptance container
  node: AVAILABLE — 24.19.0
  playwright: AVAILABLE — 1.64.1 with Chromium
  infra-tier: PRIMARY(Docker-based) — isolated PostgreSQL 16.15
  browser-tier: PRIMARY(Playwright) — real HTTP and mocked suites reported separately
startup: PASS — isolated Spring Boot Java 21 API and Next.js production server responded; readiness evidenced by completed real HTTP browser suite
integration: PASS — exit_code: 0, passed: 65, failed: 0, skipped: 1; 66 total, Java 21.0.12.1/PostgreSQL 16.15; baseline and V001–V009 migration checks passed
e2e: PASS — exit_code: 0, real HTTP 2 passed and mocked 5 passed; TSA/HSA cohort filters, scoring persistence, JWT/RBAC, and dashboards exercised
overall: PASS — Phase 06 technical acceptance met for the tested scope; content remains synthetic-only and time metrics remain evidence-limited
```
