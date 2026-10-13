# Taxonomy Persistence Migration Plan — CF-03.4C

## Preconditions

Do not begin a production migration until all of the following are approved:

1. A real immutable Taxonomy Release exists and passes structural, mapping,
   approval, and integrity gates. Current draft data is not a valid backfill
   target.
2. Taxonomy identity/version format, release ID representation, parent
   behavior, compatibility policy, retirement and approval evidence retention
   are agreed.
3. The authoritative snapshot store, stable locator, retention, backup,
   access control, and integrity-verification schedule are selected.
4. Mapping-set and Test Version policy (one release per Test Version by
   default), legacy behavior, scoring/analytics run identifiers, and
   re-analysis rules are approved.
5. Database ownership, migration runner/tooling, production release/rollback
   window, and application compatibility are established. Current repository
   documentation lists ordered SQL migrations, but the inspected backend
   configuration does not establish a taxonomy migration runner.

## Ordered additive rollout

All steps below are a future plan. No SQL draft is created and no migration is
executed by CF-03.4C.

1. **Baseline and backups.** Take a verified database backup and record the
   current schema version, row counts, constraints, indexes, and representative
   integrity queries. Rehearse restore on a separate database.
2. **Create catalog tables.** Add logical taxonomy identity, Taxonomy Release
   manifest/hash metadata, release-to-Skill membership, and append-only release
   events. Create parent/self and uniqueness constraints after the referenced
   catalog tables. Use non-destructive `RESTRICT`/no-delete semantics for
   published history.
3. **Create mapping revision tables.** Add mapping-set headers and mapping
   items referencing Question Version, release, and release Skill membership.
   Add unique keys and composite FKs so all Skills in a set use one release.
   Keep `question_competency_map` unchanged as a legacy source until the
   cutover is approved.
4. **Add Test Version/Item selection.** Add nullable release provenance for
   existing Test Versions during transition and mapping-set selection for new
   Test Items. Add composite constraints proving release consistency and
   Question Version consistency. Require release identity for newly published
   Test Versions through a controlled publish transaction/application gate;
   do not mark unknown existing Test Versions as released.
5. **Add analysis-run/result history.** Create immutable run identity and
   versioned result tables before changing any analytics read path. Keep
   existing `attempt_results`, `skill_performances`, and
   `competency_profiles` available as legacy/current projections. Do not
   update historical scores, mappings, or profiles as a migration side effect.
6. **Validate and index.** Profile actual access patterns; add indexes for
   release joins and run lookups only when justified. For large existing
   tables, evaluate concurrent index creation outside transactions. Validate
   `NOT VALID` foreign keys/checks after data checks; do not weaken constraints
   to make invalid rows pass.
7. **Application adoption.** Deploy read-compatible code, verify release
   identity and immutable snapshot hash, then enable writes only for approved
   release-bound question mapping sets and Test Versions. Keep current
   endpoints compatible; API changes require separate approval and a staged
   rollout.

## Legacy inventory and backfill rules

Before backfill, report totals and exceptions for:

- Question Versions with no `question_competency_map` rows, invalid Skill
  references, or unexpected/multiple primary mappings.
- Published Test Versions with items whose Question Version or section does
  not match the selected test context.
- Attempts whose Test Version is absent; answers whose composite context is
  inconsistent; question/section results that do not belong to their Attempt.
- Skill performance/profile rows whose user, Attempt, Skill, or Competency
  association cannot be reconciled from existing FKs and source evidence.
- Duplicate or missing business IDs, orphaned mappings, and conflicting
  candidate-to-canonical IDs.

Backfill a release reference only when immutable source evidence proves the
exact release and mapping used for that record (for example, verified
snapshot/hash plus versioned mapping artifact and reviewer decision). Never
derive a release from exam year, the current framework file, current Skill
parent, display text, latest version, or publication timestamp alone.

Legacy rows without sufficient evidence remain explicitly
`unverified_legacy`/release-null and are excluded from release-dependent
analytics or publishing until reviewed. Do not fabricate a release ID or
backfill an approval. Store the provenance assessment and evidence reference
for any individually verified association.

## Verification before cutover

- Assert every new mapping-set header has one published release, and all its
  items reference Skills belonging to that release.
- Assert every release-bound Test Item uses a mapping set for the same
  Question Version and Taxonomy Release as its Test Version.
- Assert each AttemptAnswer's Attempt, Test Version, Test Item, and Question
  Version agree; check existing composite FKs remain enabled and validated.
- Check result-to-attempt/item/answer consistency with explicit joins; existing
  independent FKs in `question_results` do not prove the full context.
- Verify published manifest and snapshot hashes, unique taxonomy versions,
  release IDs, parent chains, audit references, and status transitions.
- Compare immutable pre/post row counts and hashes for existing answers,
  results, mappings, and profiles. Confirm migration itself has not updated
  historical result values.
- Exercise migration from a production-like backup in an isolated PostgreSQL
  database, run all integrity queries and application smoke tests there, then
  rehearse rollback and restore.

## Rollback and recovery

Prefer additive, forward-compatible changes. Before application cutover,
disable the new release-bound write/read path and leave additive tables and
nullable columns in place; do not drop tables, delete release records, or
rewrite legacy mappings as a routine rollback. If new writes have occurred,
retain them and repair forward or restore from a verified backup after
incident approval. A down migration must not erase release events, new
analysis runs, or provenance records. Restore testing is mandatory before
production rollout.
