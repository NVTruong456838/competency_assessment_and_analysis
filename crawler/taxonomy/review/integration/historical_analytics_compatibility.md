# Historical Analytics Compatibility — CF-03.4C

## Provenance path

For an original assessment, the verifiable path must be:

```text
Student
  -> Attempt
  -> immutable Test Version
  -> Test Item
  -> immutable Question Version
  -> selected immutable Question Mapping Set
  -> Taxonomy Release
  -> release-specific Skill and Competency definitions
```

The current DDL protects the central Attempt/Test Version and
AttemptAnswer/TestItem/QuestionVersion relationships with composite foreign
keys. It does not attach a Taxonomy Release to Question mappings or Test
Versions. Existing `skill_performances` and `competency_profiles` point to
global mutable Skill/Competency rows, so those FKs alone cannot identify the
historical definition. `competency_profiles` is unique per user and
Competency, which makes it a current projection rather than an analytics
history.

## Versioned analysis runs

Do not overload `attempt_results`, which is currently one row per Attempt, to
hold multiple re-analysis outputs. Add an immutable analysis-run identity for
each execution with:

- `attempt_id` and exact `test_version_id`.
- `source_taxonomy_release_id` from the Test Version's mapping sets.
- `analysis_taxonomy_release_id` used to interpret or re-analyze the answers.
- `scoring_version` and `analytics_version` as explicit algorithm/build
  identifiers (not free-form labels silently changed in place).
- `run_type` such as original or reanalysis; optional superseded-run link.
- `computed_at`, input/provenance digest, and run status.
- Result rows keyed to the run, including `sample_size` and `confidence`
  where the existing metric supports them.

The source release and analysis release are intentionally separate: a later
release may be used to re-analyze an old Attempt, but must not claim that it
was the release used by the original Test Version. Original correctness,
answers, score outputs, and analysis rows remain intact. A new run produces
new result rows and is surfaced as a distinct interpretation.

The current schema already has useful fields: `attempt_results.calculated_at`,
`skill_performances.question_count`, `confidence`, and `calculated_at`, and
`competency_profiles.sample_size`, `confidence`, and `calculated_at`. They do
not solve history/versioning: the result/profile uniqueness constraints allow
only one row per attempt or user/competency, and none stores release,
scoring-version, or analytics-version provenance. Retain those tables for
legacy/current projections; do not silently update them to represent a new
run.

`sample_size` must be defined per metric (for example, eligible responses
contributing to that specific result), and confidence semantics/calculation
must be approved before database adoption. CF-03.4C defines storage
provenance only; it does not choose or implement a scoring/mastery algorithm.

## Compatibility cases

| Change | Identity and historical rule |
| --- | --- |
| Rename a Skill, same construct and measurement | Keep the stable Skill ID only after human review confirms semantic identity. The new release snapshot records the new label; old runs continue to resolve the old label. The current CF-03.4B release manager treats `skill_name` as part of its strict compatibility signature and will block this case; an explicit reviewed rename exception is needed before operationalizing it. |
| Move a Skill to another Topic | Do not rewrite old mappings or release definitions. Treat as a new release-bound mapping and require a human decision whether the Skill construct is unchanged. The current release manager includes parent path fields in the signature and conservatively blocks reuse; follow its new-ID policy unless that compatibility rule is explicitly revised. |
| Split one Skill | Create new stable IDs for successor Skills. Preserve the original Skill in its old release; record an approved predecessor/successor relationship for new analysis. Do not divide old scores among successors without an approved mapping and scoring method. |
| Merge Skills | Create a new stable ID for the merged construct and record both predecessor IDs. Do not aggregate or rewrite historical scores as if they had always measured the merged Skill. |
| Retire a Skill | Mark it unavailable for new mappings in a later release; preserve it and its definition in prior snapshots. No hard-delete or cascade into historical mappings/results. |
| Re-analyze with a newer release | Create a new run with its own source/analysis release IDs, scoring and analytics versions, timestamp, input digest, and evidence-backed compatibility mapping. Keep original answers and original analysis untouched. If no approved compatibility path exists, refuse the re-analysis rather than guess. |

No compatibility mapping is implied by similar names, shared IDs, parent
relationships, or a release's existence. Human decisions and evidence are
required for identity changes and cross-release re-analysis.
