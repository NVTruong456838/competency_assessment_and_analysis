# Taxonomy Constraints Matrix

## Goal

This matrix records the required integrity constraints for the additive taxonomy release contract. It is a design artifact and is not executed against the running database.

| Table | PK | FK | Unique constraints | Check constraints | Status lifecycle | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| `taxonomy_catalogs` | `taxonomy_catalog_id` | None | `taxonomy_id` unique | `catalog_type` and `status` enumerations | `DRAFT`, `ACTIVE`, `RETIRED` | Stable logical identity for a taxonomy family |
| `taxonomy_releases` | `taxonomy_release_id` | `taxonomy_catalog_id`, `parent_release_id` | `(taxonomy_catalog_id, release_code)`, `(taxonomy_catalog_id, taxonomy_version)` | `content_hash` format, `status` enum, self-parent guard | `DRAFT`, `IN_REVIEW`, `RELEASE_CANDIDATE`, `PUBLISHED`, `RETIRED`, `SUPERSEDED` | Immutable published release row; new version for change |
| `taxonomy_release_members` | `(taxonomy_release_id, skill_id)` | `taxonomy_release_id`, `skill_id` | None beyond PK | membership status enum | `ACTIVE`, `RETIRED`, `SUPERSEDED` | Converts a release into a validated skill membership set |
| `taxonomy_release_events` | `taxonomy_release_event_id` | `taxonomy_release_id` | `(taxonomy_release_id, event_type, event_time, actor)` | event type enum | append-only | Event log for approval and release changes |
| `question_mapping_sets` | `mapping_set_id` | `question_version_id`, `taxonomy_release_id` | `(question_version_id, mapping_revision)`, `(question_version_id, taxonomy_release_id, mapping_revision)` | mapping revision positive, status enum | `DRAFT`, `REVIEWED`, `PUBLISHED`, `RETIRED` | Immutable mapping revision bundle |
| `question_mapping_set_items` | `(mapping_set_id, skill_id)` | `mapping_set_id`, `skill_id`, `taxonomy_release_id` | Partial unique index on `mapping_set_id` for `is_primary = TRUE` | weight range, role enum | same as set | Release-bound mapping element |
| `analytics_runs` | `analysis_run_id` | `attempt_id`, `source_taxonomy_release_id`, `analysis_taxonomy_release_id` | none required by design | run type, status, sample size and confidence ranges | `DRAFT`, `COMPLETED`, `FAILED`, `REPLACED` | Append-only analysis provenance |
| `test_versions` (extension) | existing PK | add `taxonomy_release_id` | existing unique keys remain | status enum for test | `DRAFT`, `ACTIVE`, `INACTIVE`, `ARCHIVED` | release-bound test version for future publication |
| `test_items` (extension) | existing PK | add `mapping_set_id` | existing test item uniqueness remains | mapping set must be consistent with question version/test version | existing lifecycle | item-level map provenance |
| `skill_performances` (extension) | existing PK | `taxonomy_release_id`, `analytics_run_id` | policy-specific: keep existing unique key | scoring/analytics version may be null on legacy rows | current analysis row lifecycle | retain historical results without mutation |
| `competency_profiles` (extension) | existing PK | `taxonomy_release_id`, `analytics_run_id` | policy-specific: keep existing unique key | scoring/analytics version may be null on legacy rows | current profile lifecycle | preserve ability to show current and historical profiles |

## Required invariants

### Invariant 1: one taxonomy release per mapping set

A question mapping set always belongs to one taxonomy release. A mapping item cannot declare a different release than the set header.

### Invariant 2: release membership must match skill membership

`question_mapping_set_items` must only reference a skill that is also included in the parent release via `taxonomy_release_members`.

### Invariant 3: historical results are append-only

Existing `skill_performances` and `competency_profiles` rows remain as historical evidence. New analysis runs create new records or new result variants, without overwriting prior values.

### Invariant 4: exam structure and taxonomy structure remain separate

`exam_versions` and `taxonomy_releases` must not be used interchangeably. The first represents test structure; the second represents competency definition and interpretation.

### Invariant 5: publish decisions are evidence-based

A release may transition to `PUBLISHED` only if approval evidence, mapping readiness, and structural validation are all satisfied.

## Required migration policy checks

Before any migration can proceed, the following must be asserted:

- no duplicate release codes within one catalog,
- no mapping set uses skills outside its release,
- no published mapping set is modified in place,
- all legacy rows without a verified release remain explicitly unverified,
- no release is created from the current draft taxonomy without an approval record.

## Missing or deferred constraints

The following are intentionally deferred until a production approval and migration run:

- a stronger composite FK from `test_items` to `test_versions` + `question_versions` + `mapping_set_id`,
- a stronger `question_mapping_set_items` validation requiring match to the exact question version from its test item,
- a full analytics result lineage model if product requirements justify separate result tables beyond the current summary tables.

These constraints are design-level decisions and should only be introduced after a controlled migration and a separate approval review.
