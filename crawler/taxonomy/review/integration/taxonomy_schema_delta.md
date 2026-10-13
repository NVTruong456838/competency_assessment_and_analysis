# Taxonomy Schema Delta

## Objective

This document captures the proposed additive schema delta for taxonomy release persistence and question mapping provenance. It is a contract specification for a future migration and must not be executed in the current task.

## Delta summary

### Additive tables

```sql
CREATE TABLE taxonomy_catalogs (
    taxonomy_catalog_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    taxonomy_id VARCHAR(64) NOT NULL UNIQUE,
    catalog_name VARCHAR(255) NOT NULL,
    catalog_type VARCHAR(32) NOT NULL CHECK (catalog_type IN ('TSA', 'HSA', 'CUSTOM')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE taxonomy_releases (
    taxonomy_release_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    taxonomy_catalog_id BIGINT NOT NULL,
    release_code VARCHAR(64) NOT NULL,
    taxonomy_version VARCHAR(32) NOT NULL,
    parent_release_id BIGINT NULL,
    status VARCHAR(32) NOT NULL CHECK (
        status IN ('DRAFT', 'IN_REVIEW', 'RELEASE_CANDIDATE', 'PUBLISHED', 'RETIRED', 'SUPERSEDED')
    ),
    content_hash CHAR(64) NOT NULL CHECK (content_hash ~ '^[a-f0-9]{64}$'),
    snapshot_locator TEXT NOT NULL,
    manifest_json JSONB NOT NULL,
    approval_references JSONB NULL,
    source_artifact_hashes JSONB NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ NULL,
    approved_by VARCHAR(255) NULL,
    approval_decision_ref VARCHAR(255) NULL,
    CONSTRAINT uq_taxonomy_release_code UNIQUE (taxonomy_catalog_id, release_code),
    CONSTRAINT uq_taxonomy_release_version UNIQUE (taxonomy_catalog_id, taxonomy_version),
    CONSTRAINT fk_taxonomy_releases_catalog FOREIGN KEY (taxonomy_catalog_id)
        REFERENCES taxonomy_catalogs (taxonomy_catalog_id) ON DELETE RESTRICT,
    CONSTRAINT fk_taxonomy_releases_parent FOREIGN KEY (parent_release_id)
        REFERENCES taxonomy_releases (taxonomy_release_id) ON DELETE RESTRICT,
    CONSTRAINT chk_taxonomy_parent_self CHECK (parent_release_id IS NULL OR parent_release_id <> taxonomy_release_id)
);

CREATE TABLE taxonomy_release_members (
    taxonomy_release_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    membership_status VARCHAR(16) NOT NULL CHECK (membership_status IN ('ACTIVE', 'RETIRED', 'SUPERSEDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (taxonomy_release_id, skill_id),
    CONSTRAINT fk_taxonomy_release_members_release FOREIGN KEY (taxonomy_release_id)
        REFERENCES taxonomy_releases (taxonomy_release_id) ON DELETE RESTRICT,
    CONSTRAINT fk_taxonomy_release_members_skill FOREIGN KEY (skill_id)
        REFERENCES skills (skill_id) ON DELETE RESTRICT
);

CREATE TABLE taxonomy_release_events (
    taxonomy_release_event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    taxonomy_release_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL CHECK (
        event_type IN ('DRAFTED', 'APPROVED', 'CANDIDATE', 'PUBLISHED', 'RETIRED', 'SUPERSEDED')
    ),
    event_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor VARCHAR(255) NULL,
    reason_code VARCHAR(64) NULL,
    reason_text TEXT NULL,
    evidence_uri TEXT NULL,
    metadata_json JSONB NULL,
    CONSTRAINT uq_taxonomy_release_event UNIQUE (taxonomy_release_id, event_type, event_time, actor),
    CONSTRAINT fk_taxonomy_release_events_release FOREIGN KEY (taxonomy_release_id)
        REFERENCES taxonomy_releases (taxonomy_release_id) ON DELETE RESTRICT
);

CREATE TABLE question_mapping_sets (
    mapping_set_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question_version_id BIGINT NOT NULL,
    taxonomy_release_id BIGINT NOT NULL,
    mapping_revision INTEGER NOT NULL CHECK (mapping_revision > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'REVIEWED', 'PUBLISHED', 'RETIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ NULL,
    metadata_json JSONB NULL,
    CONSTRAINT uq_question_mapping_set UNIQUE (question_version_id, mapping_revision),
    CONSTRAINT fk_question_mapping_set_question_version FOREIGN KEY (question_version_id)
        REFERENCES question_versions (question_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_question_mapping_set_release FOREIGN KEY (taxonomy_release_id)
        REFERENCES taxonomy_releases (taxonomy_release_id) ON DELETE RESTRICT
);

CREATE TABLE question_mapping_set_items (
    mapping_set_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    taxonomy_release_id BIGINT NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    weight NUMERIC(5,4) NULL CHECK (weight IS NULL OR (weight >= 0 AND weight <= 1)),
    role VARCHAR(16) NOT NULL CHECK (role IN ('PRIMARY', 'SECONDARY', 'EXCLUDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (mapping_set_id, skill_id),
    CONSTRAINT fk_question_mapping_set_item_set FOREIGN KEY (mapping_set_id)
        REFERENCES question_mapping_sets (mapping_set_id) ON DELETE CASCADE,
    CONSTRAINT fk_question_mapping_set_item_skill FOREIGN KEY (skill_id)
        REFERENCES skills (skill_id) ON DELETE RESTRICT,
    CONSTRAINT fk_question_mapping_set_item_release FOREIGN KEY (taxonomy_release_id)
        REFERENCES taxonomy_releases (taxonomy_release_id) ON DELETE RESTRICT
);

CREATE TABLE analytics_runs (
    analysis_run_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attempt_id BIGINT NOT NULL,
    source_taxonomy_release_id BIGINT NULL,
    analysis_taxonomy_release_id BIGINT NOT NULL,
    scoring_version VARCHAR(50) NOT NULL,
    analytics_version VARCHAR(50) NOT NULL,
    run_type VARCHAR(32) NOT NULL CHECK (run_type IN ('INITIAL', 'RE_ANALYSIS', 'PROFILE_REFRESH')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'COMPLETED', 'FAILED', 'REPLACED')),
    sample_size INTEGER NULL CHECK (sample_size IS NULL OR sample_size >= 0),
    confidence NUMERIC(5,4) NULL CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    computed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    provenance_json JSONB NULL,
    CONSTRAINT fk_analytics_runs_attempt FOREIGN KEY (attempt_id)
        REFERENCES attempts (attempt_id) ON DELETE RESTRICT,
    CONSTRAINT fk_analytics_runs_source_release FOREIGN KEY (source_taxonomy_release_id)
        REFERENCES taxonomy_releases (taxonomy_release_id) ON DELETE RESTRICT,
    CONSTRAINT fk_analytics_runs_analysis_release FOREIGN KEY (analysis_taxonomy_release_id)
        REFERENCES taxonomy_releases (taxonomy_release_id) ON DELETE RESTRICT
);
```

## Existing table extensions

```sql
ALTER TABLE test_versions
    ADD COLUMN taxonomy_release_id BIGINT NULL,
    ADD CONSTRAINT fk_test_versions_taxonomy_release
        FOREIGN KEY (taxonomy_release_id) REFERENCES taxonomy_releases (taxonomy_release_id) ON DELETE RESTRICT;

ALTER TABLE test_items
    ADD COLUMN mapping_set_id BIGINT NULL,
    ADD CONSTRAINT fk_test_items_mapping_set
        FOREIGN KEY (mapping_set_id) REFERENCES question_mapping_sets (mapping_set_id) ON DELETE RESTRICT;

ALTER TABLE skill_performances
    ADD COLUMN taxonomy_release_id BIGINT NULL,
    ADD COLUMN analytics_run_id BIGINT NULL,
    ADD COLUMN scoring_version VARCHAR(50) NULL,
    ADD COLUMN analytics_version VARCHAR(50) NULL,
    ADD CONSTRAINT fk_skill_performances_taxonomy_release
        FOREIGN KEY (taxonomy_release_id) REFERENCES taxonomy_releases (taxonomy_release_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_skill_performances_analytics_run
        FOREIGN KEY (analytics_run_id) REFERENCES analytics_runs (analysis_run_id) ON DELETE RESTRICT;

ALTER TABLE competency_profiles
    ADD COLUMN taxonomy_release_id BIGINT NULL,
    ADD COLUMN analytics_run_id BIGINT NULL,
    ADD COLUMN scoring_version VARCHAR(50) NULL,
    ADD COLUMN analytics_version VARCHAR(50) NULL,
    ADD CONSTRAINT fk_competency_profiles_taxonomy_release
        FOREIGN KEY (taxonomy_release_id) REFERENCES taxonomy_releases (taxonomy_release_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_competency_profiles_analytics_run
        FOREIGN KEY (analytics_run_id) REFERENCES analytics_runs (analysis_run_id) ON DELETE RESTRICT;
```

## Constraint strategy

### Integrity rules

1. Each `question_mapping_set` belongs to exactly one `taxonomy_release_id`.
2. Each `question_mapping_set_item` references a `skill_id` that is part of the same release via `taxonomy_release_members`.
3. A `test_versions` record may carry one taxonomy release; new published tests must be release-bound.
4. A `test_items` record uses the `question_version_id` and `mapping_set_id` belonging to the same test version and release.
5. Historical analytics rows remain append-only and cannot be rewritten by migration.

### Indexing

Suggested indexes:

- `taxonomy_releases (taxonomy_catalog_id, status)`
- `taxonomy_release_members (skill_id, taxonomy_release_id)`
- `question_mapping_sets (question_version_id, status, taxonomy_release_id)`
- `question_mapping_set_items (taxonomy_release_id, skill_id)`
- `analytics_runs (attempt_id, analysis_taxonomy_release_id, status)`
- `skill_performances (taxonomy_release_id, analytics_run_id)`
- `competency_profiles (taxonomy_release_id, analytics_run_id)`

These indexes are additive and should be justified by query plans before production use.

## Guardrails

- Do not create a release from unreviewed draft data.
- Do not implicitly backfill `taxonomy_release_id` for legacy rows.
- Do not retroactively mutate previous mapping or result rows.
- Keep `exam_version` and `taxonomy_release` as separate dimensions.
- Preserve the existing schema until the migration is approved and backed up.

## Acceptance rule

The migration is acceptable only when all of the following are true:

- all foreign keys point to existing tables and columns,
- release membership validation prevents mixed-release mapping sets,
- historical result rows remain intact,
- no published release is created from current draft taxonomy,
- the migration is isolated in a non-production database for validation before production rollout.
