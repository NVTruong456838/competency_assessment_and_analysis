# Taxonomy Database Contract

## Scope

This document defines the minimum PostgreSQL contract required to persist taxonomy releases and provenance without modifying the current production database. It is a design artifact for CF-03.5A and does not execute a migration or create a published release.

The contract intentionally separates:

- `exam_versions`: exam structure lifecycle
- `question_versions`: question content lifecycle
- `test_versions`: test configuration lifecycle
- `taxonomy_releases`: immutable competency framework release lifecycle
- `question_mapping_sets`: immutable mapping provenance for question-to-skill interpretation

The design preserves historical results by treating existing rows as legacy unless a human-reviewed release association is verified.

## Existing schema findings

The current DDL already contains the core assessment objects but does not carry taxonomy release identity:

- `exam_versions` version exam structure.
- `competencies`, `domains`, `topics`, and `skills` are global and mutable by design in the current schema.
- `question_versions` stores question content and review state, but no taxonomy release provenance.
- `question_competency_map` stores current mapping rows without a release or revision envelope.
- `test_versions` and `attempts` establish a stable test and attempt history, but no taxonomy release is recorded on the test or results.
- `skill_performances` and `competency_profiles` aggregate current scores without provenance of the taxonomy release or analytics version used.

This means the release identity must be added as an additive contract, not by overloading `exam_version` or current `skills` rows.

## Selected persistence model

### Recommended additive tables

| Table | Purpose | Notes |
| --- | --- | --- |
| `taxonomy_catalogs` | Stable logical identity for a competency framework. | One catalog per taxonomy family such as TSA or HSA. |
| `taxonomy_releases` | Immutable release record for a framework snapshot. | Stores release code, version, snapshot hash, manifest, parent release, and status. |
| `taxonomy_release_members` | Release-to-skill membership. | Ensures a mapping item can only reference skills in the same release. |
| `taxonomy_release_events` | Append-only lifecycle events. | Tracks review, candidate, publish, retire, and supersede state changes. |
| `question_mapping_sets` | Immutable question-to-skill mapping revision bundle. | One mapping revision per question version, bound to one taxonomy release. |
| `question_mapping_set_items` | Mapping row details within a set. | Stores skill membership, primary flag, and optional weight. |
| `analytics_runs` | Immutable analysis run metadata. | Tracks source release, analysis release, scoring version, and analytics version. |

### Existing tables to extend only when justified

| Table | Proposed extension | Rationale |
| --- | --- | --- |
| `skill_performances` | Add `taxonomy_release_id`, `analytics_run_id`, `scoring_version`, `analytics_version` | Allows result provenance without overwriting historical row values. |
| `competency_profiles` | Add `taxonomy_release_id`, `analytics_run_id`, `scoring_version`, `analytics_version` | Keeps historical profile snapshots while preserving current summary rows. |
| `test_versions` | Add `taxonomy_release_id` as a nullable field during rollout | Enables release-bound tests for new publications without retroactively changing old test versions. |
| `test_items` | Add `mapping_set_id` as nullable field during rollout | Keeps a per-item mapping provenance reference without mutating prior rows. |

### Design choice

The selected pattern is Option B at mapping-set level:

- Question content is versioned by `question_versions`.
- Mapping interpretation is versioned by `question_mapping_sets`.
- Each mapping set binds to exactly one taxonomy release.
- A question version may have multiple mapping sets over time.
- Published test versions select a single release and a concrete mapping set per item.

This preserves a single proven interpretation for a test while permitting future re-analysis without changing prior results.

## Contract details

### 1. taxonomy_catalogs

| Column | Type | Nullability | Constraint |
| --- | --- | --- | --- |
| `taxonomy_catalog_id` | BIGINT | NOT NULL | PK |
| `taxonomy_id` | VARCHAR(64) | NOT NULL | Unique, stable logical identifier |
| `catalog_name` | VARCHAR(255) | NOT NULL | Human-readable name |
| `catalog_type` | VARCHAR(32) | NOT NULL | `TSA`, `HSA`, or `CUSTOM` |
| `status` | VARCHAR(20) | NOT NULL | `DRAFT`, `ACTIVE`, `RETIRED` |
| `created_at` | TIMESTAMPTZ | NOT NULL | Default `CURRENT_TIMESTAMP` |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Default `CURRENT_TIMESTAMP` |

Notes:

- `taxonomy_id` is logical identity and must stay stable across releases.
- The catalog row should not be treated as an approved release.

### 2. taxonomy_releases

| Column | Type | Nullability | Constraint |
| --- | --- | --- | --- |
| `taxonomy_release_id` | BIGINT | NOT NULL | PK |
| `taxonomy_catalog_id` | BIGINT | NOT NULL | FK to `taxonomy_catalogs` |
| `release_code` | VARCHAR(64) | NOT NULL | Unique across catalog |
| `taxonomy_version` | VARCHAR(32) | NOT NULL | SemVer-like value; separated from exam version |
| `parent_release_id` | BIGINT | NULL | Self-FK |
| `status` | VARCHAR(32) | NOT NULL | `DRAFT`, `IN_REVIEW`, `RELEASE_CANDIDATE`, `PUBLISHED`, `RETIRED`, `SUPERSEDED` |
| `content_hash` | CHAR(64) | NOT NULL | SHA-256 of canonical snapshot |
| `snapshot_locator` | TEXT | NOT NULL | Immutable storage locator |
| `manifest_json` | JSONB | NOT NULL | Release manifest |
| `approval_references` | JSONB | NULL | Decision references and evidence |
| `source_artifact_hashes` | JSONB | NULL | Hash set for source files |
| `created_at` | TIMESTAMPTZ | NOT NULL | Default `CURRENT_TIMESTAMP` |
| `published_at` | TIMESTAMPTZ | NULL | Set on publication |
| `approved_by` | VARCHAR(255) | NULL | Reviewer or approval handle |
| `approval_decision_ref` | VARCHAR(255) | NULL | Decision Register reference |

Constraints:

- `UNIQUE (taxonomy_catalog_id, release_code)`
- `UNIQUE (taxonomy_catalog_id, taxonomy_version)`
- `CHECK (content_hash ~ '^[a-f0-9]{64}$')`
- `CHECK (status IN (...))`
- `CHECK (parent_release_id IS NULL OR parent_release_id <> taxonomy_release_id)`

Notes:

- `release_id` should be a DB surrogate key, while `release_code` remains the stable external identifier.
- `taxonomy_version` is not the same as `exam_version` and must not be inferred from exam structure.
- After `PUBLISHED`, release rows are immutable; a new release is created for any semantic change.

### 3. taxonomy_release_members

| Column | Type | Nullability | Constraint |
| --- | --- | --- | --- |
| `taxonomy_release_id` | BIGINT | NOT NULL | FK |
| `skill_id` | BIGINT | NOT NULL | FK to `skills` |
| `membership_status` | VARCHAR(16) | NOT NULL | `ACTIVE`, `RETIRED`, `SUPERSEDED` |
| `created_at` | TIMESTAMPTZ | NOT NULL | Default `CURRENT_TIMESTAMP` |

Constraints:

- PK: `(taxonomy_release_id, skill_id)`
- FK to `taxonomy_releases`
- FK to `skills`
- `CHECK (membership_status IN ('ACTIVE','RETIRED','SUPERSEDED'))`

Notes:

- This table is the release-bound membership layer that prevents skill rows from being used outside the release in which they were approved.
- It avoids copying all taxonomy metadata into a second mutable table while still allowing a mapping row to validate against the exact release membership.

### 4. taxonomy_release_events

| Column | Type | Nullability | Constraint |
| --- | --- | --- | --- |
| `taxonomy_release_event_id` | BIGINT | NOT NULL | PK |
| `taxonomy_release_id` | BIGINT | NOT NULL | FK |
| `event_type` | VARCHAR(32) | NOT NULL | `DRAFTED`, `APPROVED`, `CANDIDATE`, `PUBLISHED`, `RETIRED`, `SUPERSEDED` |
| `event_time` | TIMESTAMPTZ | NOT NULL | Default `CURRENT_TIMESTAMP` |
| `actor` | VARCHAR(255) | NULL | reviewer or system actor |
| `reason_code` | VARCHAR(64) | NULL | validation or review reason |
| `reason_text` | TEXT | NULL | explanation |
| `evidence_uri` | TEXT | NULL | artifact or decision reference |
| `metadata_json` | JSONB | NULL | additional event metadata |

Constraints:

- `UNIQUE (taxonomy_release_id, event_type, event_time, actor)`
- `CHECK (event_type IN (...))`

Notes:

- Append-only lifecycle history; no update or delete of an event row.
- This preserves evidence for publication readiness and human review approval.

### 5. question_mapping_sets

| Column | Type | Nullability | Constraint |
| --- | --- | --- | --- |
| `mapping_set_id` | BIGINT | NOT NULL | PK |
| `question_version_id` | BIGINT | NOT NULL | FK to `question_versions` |
| `taxonomy_release_id` | BIGINT | NOT NULL | FK to `taxonomy_releases` |
| `mapping_revision` | INTEGER | NOT NULL | positive integer |
| `status` | VARCHAR(20) | NOT NULL | `DRAFT`, `REVIEWED`, `PUBLISHED`, `RETIRED` |
| `created_at` | TIMESTAMPTZ | NOT NULL | Default `CURRENT_TIMESTAMP` |
| `published_at` | TIMESTAMPTZ | NULL | Set when approved |
| `metadata_json` | JSONB | NULL | provenance and source metadata |

Constraints:

- `UNIQUE (question_version_id, mapping_revision)`
- `UNIQUE (question_version_id, taxonomy_release_id, mapping_revision)`
- `CHECK (mapping_revision > 0)`
- `CHECK (status IN ('DRAFT','REVIEWED','PUBLISHED','RETIRED'))`

Notes:

- Mapping set is the immutable envelope for a mapping definition.
- Published sets remain immutable; re-analysis creates a new revision instead of changing the old set.

### 6. question_mapping_set_items

| Column | Type | Nullability | Constraint |
| --- | --- | --- | --- |
| `mapping_set_id` | BIGINT | NOT NULL | FK to `question_mapping_sets` |
| `skill_id` | BIGINT | NOT NULL | FK to `skills` |
| `taxonomy_release_id` | BIGINT | NOT NULL | FK to `taxonomy_releases` |
| `is_primary` | BOOLEAN | NOT NULL | Default FALSE |
| `weight` | NUMERIC(5,4) | NULL | 0..1 |
| `role` | VARCHAR(16) | NOT NULL | `PRIMARY`, `SECONDARY`, `EXCLUDED` |
| `created_at` | TIMESTAMPTZ | NOT NULL | Default `CURRENT_TIMESTAMP` |

Constraints:

- PK: `(mapping_set_id, skill_id)`
- `CHECK (weight IS NULL OR (weight >= 0 AND weight <= 1))`
- `CHECK (role IN ('PRIMARY','SECONDARY','EXCLUDED'))`
- Additional partial unique index: `CREATE UNIQUE INDEX ... WHERE is_primary = TRUE` on `(mapping_set_id)`

Notes:

- This table ensures every mapping item belongs to a single release and that a single question mapping set cannot mix skills from multiple releases.
- The item should not point directly to an unbounded global `skills` row without release validation.

### 7. analytics_runs

| Column | Type | Nullability | Constraint |
| --- | --- | --- | --- |
| `analysis_run_id` | BIGINT | NOT NULL | PK |
| `attempt_id` | BIGINT | NOT NULL | FK to `attempts` |
| `source_taxonomy_release_id` | BIGINT | NULL | FK to `taxonomy_releases` |
| `analysis_taxonomy_release_id` | BIGINT | NOT NULL | FK to `taxonomy_releases` |
| `scoring_version` | VARCHAR(50) | NOT NULL | scoring logic identifier |
| `analytics_version` | VARCHAR(50) | NOT NULL | analytics model version |
| `run_type` | VARCHAR(32) | NOT NULL | `INITIAL`, `RE_ANALYSIS`, `PROFILE_REFRESH` |
| `status` | VARCHAR(20) | NOT NULL | `DRAFT`, `COMPLETED`, `FAILED`, `REPLACED` |
| `sample_size` | INTEGER | NULL | Non-negative |
| `confidence` | NUMERIC(5,4) | NULL | 0..1 |
| `computed_at` | TIMESTAMPTZ | NOT NULL | Default `CURRENT_TIMESTAMP` |
| `provenance_json` | JSONB | NULL | evidence and input references |

Constraints:

- `CHECK (sample_size IS NULL OR sample_size >= 0)`
- `CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))`
- `CHECK (run_type IN ('INITIAL','RE_ANALYSIS','PROFILE_REFRESH'))`
- `CHECK (status IN ('DRAFT','COMPLETED','FAILED','REPLACED'))`

Notes:

- This table preserves an append-only analytics lineage and allows re-analysis without overwriting a previously computed result.
- It does not replace the current `skill_performances` or `competency_profiles` rows; it provides source provenance for them.

## Recommended migration guardrails

- Add tables as additive, not destructive.
- Keep all existing tables and data untouched.
- Treat legacy records as `unverified_legacy` until evidence exists.
- Do not create any published taxonomy release from the current draft or current working file.
- Do not overwrite released mapping sets or historical attempt results.
- Keep `exam_versions` and `taxonomy_releases` distinct by design.

## Conclusion

The database contract selects the smallest consistent relational model that protects taxonomy release provenance while staying compatible with the existing assessment schema. It preserves history, prevents mixed-release mappings, and creates explicit release and analytics lineage without changing live data.
