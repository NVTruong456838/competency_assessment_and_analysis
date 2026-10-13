# CF-03.4C Integration Decisions

## Proposed decisions for review

| ID | Proposal | Status |
| --- | --- | --- |
| INT-01 | Keep `exam_version` and `taxonomy_release_id` separate. | Design recommendation; no database change. |
| INT-02 | Use a mapping-set header (Question Version + mapping revision + one Taxonomy Release) and immutable mapping items; do not place the sole release identity on `question_versions`. | Design recommendation; preserves remapping history and avoids mixed releases inside one set. |
| INT-03 | Require one Taxonomy Release across a newly published Test Version by default; bind each Test Item to the exact mapping set used. | Proposed product invariant; confirm that mixed-release tests are not required. |
| INT-04 | Persist a small release catalog and manifest/hash/locator, plus release Skill membership; keep complete taxonomy definitions in the immutable snapshot rather than duplicating mutable labels in relational rows. | Design recommendation; requires approval of durable snapshot storage. |
| INT-05 | Retain existing mapping and analytics rows as unverified legacy when exact release provenance cannot be proven. Do not infer taxonomy IDs or approvals. | Required safety rule. |
| INT-06 | Store original and re-analysis outputs as distinct immutable analysis runs with source release, analysis release, scoring version, analytics version, computed time, provenance, sample size, and confidence where applicable. | Design recommendation; metric semantics remain open. |
| INT-07 | Use explicit human-reviewed compatibility/successor decisions for rename, reparent, split, merge, and retirement. Preserve old release definitions and outputs. | Required safety rule; specific identity policy requires approval. |
| INT-08 | Do not change active APIs, PostgreSQL, current Question Bank publisher, or Taxonomy Release Manager in CF-03.4C. | Applied scope boundary. |

## Decisions requiring approval before CF-03.5

1. Authoritative persistence for release snapshots: repository files, object
   storage, or another managed store; immutable retention, access, backup, and
   integrity verification.
2. Taxonomy ID, release ID, and database key formats; whether file release IDs
   are retained as-is or mapped to database surrogate keys.
3. Whether a published Test Version is constrained to a single Taxonomy
   Release and the behavior for any existing/mixed-release tests.
4. Whether a rename-only Skill change may retain its stable ID. CF-03.4B's
   current compatibility check includes `skill_name` and conservatively
   blocks the reuse; changing that policy requires an explicit human-reviewed
   rule and test.
5. Compatibility rules and approval evidence for reparenting, splitting,
   merging, and retiring Skills, including whether a re-analysis crosswalk may
   be used and by whom it is approved.
6. Exact scoring/analytics version identifiers, result immutability rules,
   `sample_size` definitions, confidence semantics, and treatment of
   re-analysis in student-facing profiles.
7. Which legacy data has evidence adequate for release backfill; unresolved
   records must remain unverified rather than being assigned the first or
   latest release.
8. Migration ownership/tooling, rollout compatibility, production restore
   requirements, and approval to perform the separate CF-03.5 database change.

No item above is an approval record. The current decision register remains
untouched; no Taxonomy Release or Question Bank was published.
