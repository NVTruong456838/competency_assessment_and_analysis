# Phase 04 — Final Verification, Content Quality & Release Readiness

**Generated:** 2026-10-14  
**Overall:** PARTIAL — target Java runtime and full publication/query E2E remain unverified  
**Publication readiness:** NOT READY  
**Phase 05 direction:** Development may proceed using isolated synthetic fixtures; do not publish or depend on the crawled corpus.

This report audits the implementation and checked-in normalized TSA/HSA corpus. All PostgreSQL 16 checks used a disposable container and isolated databases, which were removed after testing. No operational database was modified and no content was approved or published.

## 1. Implemented functionality

- V001–V005 schema and migration foundation for taxonomy release provenance, Question Bank imports, pattern candidates, review records, Question Version history, passages, visual assets, rights evidence, and immutability protections.
- Question Foundation APIs for taxonomy candidate registration/verification, import preview/staging/materialization, version history/revision/review/publication, mapping sets and review queues, pattern candidates/mappings/reviews, student question listing, and approved pattern lookup.
- Import batches use a unique source fingerprint; staged payloads have canonical hashes for idempotency and duplicate detection.
- Question Versions have content hashes and sequential supersession history. Passage and visual-asset content protections are present.
- Review/publication checks require the configured evidence and approved states. These controls do not independently establish the legal sufficiency of an external rights document.
- Pattern mining groups records by observable structure and emits candidates as pending/unapproved. The generation contract accepts only approved patterns with verified rights and matching published taxonomy/Skill references; it excludes source question text.

## 2. API surface and target runtime

Implemented API groups include:

- `/api/admin/taxonomy/releases/candidates`, `/api/admin/taxonomy/releases/{releaseId}/verify`
- `/api/admin/question-imports` and preview, item listing, and materialization routes
- `/api/teacher/questions/{questionId}/versions`, revision, review, and review-queue routes
- Question mapping-set creation, review, and review-queue routes
- Question pattern candidate creation, Skill mapping/review, and pattern review-queue routes
- `/api/admin/question-versions/{versionId}/publish`, `/api/student/questions`, and `/api/teacher/question-patterns/approved`

| Target | Result | Evidence |
|---|---|---|
| PostgreSQL 16 | PASS — PostgreSQL 16.15 | Disposable container; fresh baseline plus V001–V005; schema/constraint assertions passed |
| Migration failure rollback | PASS | Injected failure before V003 commit left no V003 table or provenance column |
| Migration replay guard | PASS | Reapplying V003 was rejected because target objects already existed |
| Spring Boot | 3.5.6 configured in `backend/pom.xml` | Full backend suite passed with PostgreSQL 16 and `ddl-auto=validate` |
| Java 21 | BLOCKED — unavailable in the environment | Installed Java was 26.0.1; this is not equivalent evidence for Java 21 |

The initial Java 26 run inherited the Windows timezone mapping `Asia/Saigon`, which PostgreSQL 16 rejected at connection startup. The suite passed when rerun with the test-process-only `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`; no application configuration was changed. This does not resolve the missing Java 21 verification.

## 3. TSA/HSA corpus quality

Counts are recomputed from the checked-in normalized `tsa_question_bank.json` and `hsa_question_bank.json` files. “Taxonomy mapped” means at least one of Section, Competency, Domain, Topic, or Skill is populated in the question or its mapping artifact.

| Exam | Total | Answer present | Answer missing | Blank stem | Passage | Visual asset | Taxonomy mapped | Non-empty stem + source provenance |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| TSA | 39 | 31 | 8 | 0 | 19 | 7 | 0 | 39 |
| HSA | 225 | 0 | 225 | 0 | 50 | 62 | 0 | 225 |
| **Total** | **264** | **31** | **233** | **0** | **69** | **69** | **0** | **264** |

Passage and visual counts may overlap. Normalized-stem comparison found **3 duplicate-candidate groups covering 9 records**; this is a review signal, not an automatic merge or deletion.

Both mapping artifacts contain entries for all 264 questions, but every entry remains `pending` with null Section/Competency/Domain/Topic/Skill IDs and no reviewer evidence.

Review eligibility must be separated by task:

- **Content/provenance triage:** 264 records have a non-empty stem and source provenance; they may be sent for human inspection but remain pending.
- **Answer-key review:** 31 TSA records contain a candidate answer, and 8 TSA records lack one. All 225 HSA records lack a correct answer. A present answer is not evidence that its answer key has been verified.
- **Publication:** 0 records are publication-ready. Taxonomy mapping is absent, rights are unverified, and no human approval evidence was created.

### HSA answer-key reconciliation

The current normalized HSA corpus provides no `correct_answer` values. Preserve each question’s source document/page provenance and keep the answer state unresolved. Reconcile only against an authoritative, rights-cleared source; capture the answer-key source locator and evidence, record the human verifier and decision, and leave unresolved or AI-inferred answers unverified. No authoritative answer-key evidence or real reviewer decision was supplied during this verification, so no HSA key was added.

## 4. Pattern quality and generation readiness

The corpus miner analyzed all 264 records without skips and generated **207** unique structural candidates.

| Metric | Result |
|---|---:|
| Choice with options | 169 |
| Text response | 28 |
| True/false | 8 |
| Numeric response | 2 |
| Stimulus: none / passage / table | 154 / 52 / 1 |
| Candidates with one source record | 180 |
| Candidates with multiple source records | 27 |
| Candidate with most source records | 9 |
| Taxonomy/Skill-linked candidates | 0 |
| Pending review | 207 |
| Unverified rights | 207 |

Deduplication is structural: candidates with the same observable-shape key are grouped. It is not semantic question deduplication; the 3 stem-duplicate groups still require human review. The teacher pattern review-queue API and candidate registration endpoints exist, but this run did not import all 207 mining outputs into the application queue. Queue persistence/count for the operational database was not inspected.

**Generation examples were not produced.** The 207 patterns are unreviewed and rights-unverified, while the generation contract correctly rejects such inputs. Producing examples from them would bypass that gate. Existing contract tests use synthetic fixtures and do not establish that a real pattern is generation-ready.

## 5. Governance and publication gates

| Area | Finding |
|---|---|
| Release identity | Database identity, release code/version, and content hash are represented. The external release-ID format and reuse/generation policy remain open in the taxonomy decision register. |
| Single-release Test Version | A nullable taxonomy-release reference is stored, but policy for mixed-release legacy tests and reinterpretation is unresolved. |
| Skill rename/reparent | Rename, compatibility, reparenting, and successor mapping rules remain undecided. |
| Legacy backfill | Nullable provenance allows old data to remain unassigned. Evidence thresholds and `verified_legacy` handling remain undecided; no release IDs were inferred. |
| Rights evidence | Review and visual-asset fields/gates record evidence references, reviewer identity, and verification time. External evidence validity and legal rights remain human checks. |
| Reviewer identity and audit | Review actions are associated with an authenticated user subject and append-only review/event records. No real approval was made in this run. |
| Publication Gate | Publication requires a latest approved Question Version, an approved review tied to a published taxonomy release and published mapping, rights evidence, and verified/non-review visual assets. No publication was attempted. |

The unresolved decisions are documented in `crawler/taxonomy/review/integration/taxonomy_open_decisions.md`. The existing migration verification details remain in [CF-03.5B_verification_report.md](./CF-03.5B_verification_report.md).

## 6. End-to-end verification coverage

| Requested boundary | Result |
|---|---|
| Import idempotency | PASS — PostgreSQL integration test stages the same fingerprint twice and receives the same batch. |
| Draft materialization, passage/asset persistence | PASS — integration test verifies materialized options, passage revisions, and visual-asset persistence. |
| Question Version history | PASS — integration test verifies two versions, latest state, supersession link, and changed content hash. |
| Review | PARTIAL — integration test exercises a `NEEDS_REVISION` decision; a real approval was neither performed nor claimed. |
| Mapping/publication gate | Negative gate assertions PASS in isolated migration constraints; a successful publication flow was not run. |
| Student query and answer/explanation leakage | Code inspection shows the student listing query returns published, approved content and does not select answer or explanation columns. No runtime assertion against the student API was executed. |
| JWT/RBAC | PASS for representative role denials: admin import preview, teacher review/revision, and student listing restrictions are covered by controller tests. Unauthenticated denial and the complete endpoint matrix were not separately exercised. |
| Rollback | PASS for PostgreSQL migration transaction failure. Application-service rollback on a mid-flow failure was not independently tested. |
| Historical provenance | Schema and migration constraints were validated; no real corpus record was assigned a taxonomy release. |
| Full REST flow | PARTIAL — no single HTTP test traversed crawl → preview → draft/version → assets → review → mapping → publication gate → student query. |

## 7. Test results

| Suite | Result |
|---|---|
| Backend, PostgreSQL 16.15, Java 26.0.1 | **36/36 PASS**, 0 failures/errors/skips; includes the PostgreSQL integration test and Hibernate schema validation |
| Crawler | **146/146 PASS** |
| Pattern intelligence subset | **7/7 PASS** within the crawler suite |
| PostgreSQL 16 migrations/schema/constraints | **PASS** |
| PostgreSQL 16 injected rollback/replay guard | **PASS** |

The PostgreSQL 16 container and its isolated databases were removed after testing.

Commands used:

- `backend\mvnw.cmd -f backend\pom.xml test` with the isolated PostgreSQL 16 URL and test-only UTC JVM override.
- `.venv\Scripts\python.exe -m unittest discover -s crawler -p "test*.py" -v`.
- Migration application, schema assertions, replay-guard, and injected-rollback checks executed with PostgreSQL 16 `psql` inside the disposable container.

## 8. Remaining blockers

### Technical

- Java 21 verification is still outstanding. Java 26 passing is not a substitute for the required target runtime.
- The requested full HTTP E2E across publication and student query is not present in the executed test evidence. In particular, answer/explanation non-disclosure is verified by query inspection, not a runtime assertion.

### Content and approval

- HSA has 225/225 unanswered records; authoritative answer-key evidence and human reconciliation are required.
- All 264 records have no taxonomy mapping in the normalized bank; all 207 patterns remain unlinked, pending, and rights-unverified.
- Duplicate candidates require human confirmation.
- Rights documents, reviewer decisions, and publication approvals were not supplied.
- Release identity, single-release, Skill rename/reparent, and legacy backfill decisions remain unresolved.

## 9. Technical Readiness for Phase 05

**Conditional readiness to begin Phase 05 development with isolated synthetic fixtures: YES.** PostgreSQL 16 migrations, schema validation, persistence/version history, import idempotency, representative RBAC checks, and the current backend regression suite pass. Phase 05 work must not require these unreviewed TSA/HSA records or expose answer keys through student APIs.

**Full target-runtime/production readiness: NO.** Re-run the backend on Java 21 and add/execute the missing HTTP E2E assertions before declaring full Phase 04 technical acceptance.

## 10. Publication Readiness

**NOT READY — no Question Bank or taxonomy publication is authorized by this verification.** Current evidence supports content triage only. Keep the corpus and all patterns in pending/unverified states until source rights, answer keys, taxonomy mappings, reviewer identity/evidence, and publication gates are satisfied by real human review.
