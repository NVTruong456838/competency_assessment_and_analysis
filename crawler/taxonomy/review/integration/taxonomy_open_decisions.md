# Taxonomy Open Decisions

## Objective

This file records the remaining decisions that must be formally approved before a taxonomy release may be persisted, published, or used in a production migration. These are design-level open questions for CF-03.5A and should be resolved before any live PostgreSQL migration is staged.

## Decision 1: authoritative release snapshot storage

Question:

- Where will the immutable taxonomy snapshot live?
- Is the canonical location repository files, object storage, or a controlled artifact store?

Required decision:

- durable retention,
- integrity verification schedule,
- access control,
- backup policy,
- snapshot locator format.

## Decision 2: identifier strategy for release metadata

Question:

- Should the public release identifier be `release_code` only, or should it also be backed by a database surrogate key?

Required decision:

- stable external release naming format,
- database `BIGINT` surrogate key usage,
- rules for release ID re-use and generation.

## Decision 3: policy for single-release test versions

Question:

- Must each published test version be tied to one taxonomy release only?

Required decision:

- confirm default invariant,
- define handling for mixed-release legacy tests,
- define what happens when a test must be reinterpreted under a new taxonomy release.

## Decision 4: skill rename and reparenting policy

Question:

- May a skill keep the same stable skill ID when the name or parent topic changes?

Required decision:

- rename policy,
- compatibility window,
- reparenting rule,
- requirement for explicit successor mapping or new release.

## Decision 5: split, merge, and retirement policy

Question:

- How should a taxonomy handle one skill becoming many, or many skills becoming one?

Required decision:

- explicit compatibility rules,
- time of retirement,
- replace-with policy,
- approval evidence required for each content change.

## Decision 6: analytics versioning and result immutability

Question:

- Which scoring and analytics versions will be stored when a student result is re-analyzed?

Required decision:

- `scoring_version` naming,
- `analytics_version` naming,
- whether a re-analysis creates a new row or an append-only result envelope,
- how `sample_size` and `confidence` are interpreted across skill and competency results.

## Decision 7: legacy evidence threshold for backfill

Question:

- What evidence is sufficient to assign a taxonomy release to a historical question mapping or analytics result?

Required decision:

- a documented evidence standard,
- definition of `verified_legacy` versus `unverified_legacy`,
- handling of historical rows with incomplete provenance.

## Decision 8: migration ownership and rollout approval

Question:

- Who owns the actual PostgreSQL migration window and backup validation?

Required decision:

- migration owner,
- pre-production validation environment,
- database backup process,
- rollback strategy,
- approval to execute the migration after isolated tests pass.

## Decision 9: publication gate for taxonomy release

Question:

- Is the current taxonomy still blocked from publication?

Required decision:

- mapping readiness pass/fail,
- approval readiness pass/fail,
- human reviewer evidence threshold,
- release candidate status handling prior to publication.

## Decision 10: developer scope boundary

Question:

- Can we proceed with additive design artifacts without mutating the actual database or application code?

Current scope answer:

- Yes, design artifacts and migration specification may proceed without executing DDL or changing live data.
- No release publication or automated approval may be inferred from draft taxonomy files.

## Final note

These open questions are not implementation blockers for the design contract itself, but they are required before moving into controlled database migration and release publication. Until these decisions are approved, the repository remains in a design-safe state and no published taxonomy release is created.
