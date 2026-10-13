# Taxonomy Release Compatibility — CF-03.4B

## Independent version dimensions

`exam_version` identifies exam structure (for example, sections and timing).
`taxonomy_version` identifies the competency model and Skill measurement
definitions. They are separate dimensions: a test may use an exam version
without that version identifying which taxonomy definitions were used.

## Assessment history invariants

- A `question_version` mapped to taxonomy content must retain the exact
  `taxonomy_id`, `taxonomy_version`, and preferably the immutable
  `release_id` used for that mapping. Changing a mapping creates a new mapping
  record/version; it never updates the historical mapping in place.
- A `test_version` freezes its Question Version set, ordering, scoring, and
  relevant exam configuration. Its release provenance must identify the
  taxonomy release used by its mappings. Republishing a test creates a new
  `test_version`.
- An `attempt` references one immutable `test_version`. Regrading or
  re-analysis must not silently replace that version or its original results.
- Historical competency results must resolve the Skill and Competency
  definitions from the taxonomy release applicable to the attempt. A display
  name or current taxonomy lookup is not sufficient provenance.
- Updating a taxonomy does not rewrite old question mappings or student
  results. Re-analysis with a newer release creates a separate result with
  explicit taxonomy release, analysis version, timestamp, and provenance.

The current SQL model has independent `exam_versions`, `question_versions`,
and `test_versions`. Question mappings refer to Skill rows, test items bind
Question Versions, and attempts bind Test Versions; those links preserve exam
and content versions but do not identify an immutable taxonomy release. CF-
03.4B intentionally introduces no database columns, tables, or migration.
Database integration and backfill policy are deferred to CF-03.4C and a later
approved database change.

## Before database adoption

CF-03.4C must define how approval refs and taxonomy release IDs are associated
with question mapping versions, tests, and re-analysis outputs; how existing
rows are backfilled without asserting fabricated provenance; and how
application reads select historical versus current taxonomy definitions.
Until then, file-based releases are authoritative only for release design and
local artifact integrity, not a claim that existing database rows are bound
to a release.
