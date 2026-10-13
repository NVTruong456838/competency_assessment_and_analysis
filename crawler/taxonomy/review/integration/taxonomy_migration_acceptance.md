# Taxonomy Migration Acceptance Criteria

## Objective

This document defines the conditions under which a future PostgreSQL migration for taxonomy release persistence may be accepted for isolated validation. It is a design-only checkpoint for CF-03.5A and is not a production migration approval.

## Mandatory acceptance conditions

### 1. Schema contract conformance

The planned migration must ensure the following:

- all new tables and foreign keys reference existing schema objects,
- no release table uses `exam_version` as a substitute for taxonomy release,
- `taxonomy_release_members` prevents a mapping set from referencing skills outside its release,
- `question_mapping_sets` is immutable at publication and only revised through a new revision,
- `analytics_runs` preserves source and analysis release provenance.

### 2. Data safety

Migration acceptance requires the following safety checks:

- no destructive DDL,
- no row deletion or rewrite of historical results,
- no backfill of legacy taxonomy release IDs without verified evidence,
- no generated approval or reviewer identity,
- no release publication from an unapproved draft taxonomy.

### 3. Legacy handling

The migration must preserve these categories explicitly:

- `verified_legacy`: rows with sufficient evidence and a proven release association,
- `unverified_legacy`: rows without enough evidence to assign a taxonomy release,
- `current_live_rows`: rows still used by active application logic,
- `future_release_rows`: newly created rows associated with an approved release.

Legacy rows in `unverified_legacy` state must remain queryable and must not be silently reassigned to the latest or first release.

### 4. Approval gate alignment

The migration must not proceed under the following conditions:

- framework is still `DRAFT`,
- mapping readiness does not pass,
- approval readiness does not pass,
- decision register has no real human approval record,
- publication gate is blocked by unresolved mapping blockers.

### 5. Backward compatibility

The migration must preserve current application behavior in the following ways:

- current `exam_versions`, `question_versions`, `test_versions`, and `attempts` rows remain usable,
- modernized release-aware reads remain additive and backward compatible,
- old analytics queries continue to operate while new release-aware analytics is introduced,
- no active API contract is changed without separate approval.

## Isolation test requirement

Migration acceptance requires testing on a separate PostgreSQL instance or isolated database copy before any production use. The test database must verify:

- all table creation succeeds,
- all foreign keys validate,
- the question mapping set cannot cross release boundaries,
- published release rows remain immutable,
- legacy rows without evidence remain unassigned,
- rollback and restore plan is known and reproducible.

## Rejection conditions

A migration is rejected if any of the following occurs:

- release code is inferred from the current working taxonomy file,
- historical scores are rewritten during migration,
- release membership is assigned without a review record,
- mapping sets cross release boundary due to missing FK or validation,
- migration is applied without a verified database backup and restore rehearsal,
- any row or table is removed, truncated, or reset as part of the migration.

## Sign-off rule

The migration is considered ready for isolated testing only if the following are all true:

1. the change is additive,
2. the release guardrails are explicit,
3. the legacy evidence policy is documented,
4. the approval gate is unresolved or explicitly noted as blocking publish,
5. the database owner and application owner agree that production rollout is deferred until a controlled migration window.

This is a technical readiness gate, not a business approval to publish taxonomy content.
