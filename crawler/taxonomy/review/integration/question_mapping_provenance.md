# Question Mapping Provenance — CF-03.4C

## Decision: one immutable mapping set per Question Version revision

Question Version and Taxonomy Release answer different questions. A Question
Version freezes item content and scoring inputs; a Taxonomy Release freezes
Skill/Competency definitions. A Question Version can be evaluated with a new
mapping set later without changing its content or erasing how an earlier test
used it.

The current `question_competency_map` primary key is
`(question_version_id, skill_id)`. Its Skill FK references the global `skills`
table, not a taxonomy release. It cannot represent two historical mappings
for the same Question Version and Skill, nor prove that all Skills in a
mapping were interpreted under one release. Do not add only a release ID to
the Question Version row: that would version content and mapping together,
preventing a controlled remap without a new content version.

Use a mapping-set header to bind:

```text
question_mapping_set
  mapping_set_id
  question_version_id
  mapping_revision
  taxonomy_release_id
  status / created_at / published_at

question_mapping_set_item
  mapping_set_id
  skill_id
  is_primary
  weight
```

The header has one release, so all its item rows necessarily share that
release. `mapping_revision` is unique within a Question Version. Published
sets and items are immutable; corrections create another set. The item FK
should reference `(taxonomy_release_id, skill_id)` in a release membership
table so a Skill that is absent from the release cannot be attached by
mistake.

For a published Test Version, add its selected `taxonomy_release_id` and
select a mapping set on every Test Item. Composite unique keys/FKs should
enforce all of the following together:

1. Test Item belongs to the stated Test Version.
2. Test Item references the exact Question Version in that Test Version.
3. Mapping set belongs to that Question Version.
4. Mapping set's Taxonomy Release equals the Test Version's Taxonomy Release.
5. Every mapped Skill is a member of that release.

This is Option B in the prompt, refined to a mapping-set rather than per-row
release reference. Option A on `question_versions` is simpler for a strict
one-content/one-taxonomy rule, but it couples otherwise independent version
dimensions and cannot preserve successive mapping interpretations of one
unchanged Question Version.

## Legacy and publication handling

Existing mapping rows have no provable release ID. Preserve them as
unverified legacy mappings; do not infer a release from the current framework,
question exam, current Skill parent, exam year, or a matching display name.
Only a verified source snapshot plus mapping evidence reviewed by an
authorized human can justify a release association. Unresolved legacy rows
remain queryable in their existing table and are excluded from release-bound
publication until resolved.

The current Python `QuestionBankPublisher` checks an approved in-memory
framework and mapping validity, then writes a question-bank file and a
manifest containing an exam-specific publication ID and content hash. It
does not record Taxonomy Release or mapping-set identity. Future integration
must require an immutable published `release_id`, include it in the bank
manifest, and verify every mapping belongs to that release before publishing.
Do not update the publisher or publish a bank in CF-03.4C.

Approval is attached to the exact release/change decision, not inherited by
all mappings or later releases. This design does not create approval refs or
claim the current draft is approved.
