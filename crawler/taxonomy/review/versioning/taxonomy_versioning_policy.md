# Taxonomy Versioning Policy — CF-03.4B

## Scope and identity

Taxonomy versioning is independent of `exam_version`. An exam version controls
the structure and schedule of an exam. A taxonomy release defines what
competencies mean, how they are organized, and how Skills are measured.
Questions and tests must carry both identities where applicable.

- `taxonomy_id` identifies the logical framework, independent of a file name.
- `taxonomy_version` is a MAJOR.MINOR.PATCH release number.
- `release_id` is a unique, non-time-based identifier for one snapshot.
- `parent_release_id` links a release to its prior published release.
- SHA-256 digests use UTF-8 bytes. The snapshot digest is computed over
  canonical JSON (sorted keys, compact separators, no ASCII escaping, plus a
  final newline in the stored file). Each source artifact digest covers its
  exact UTF-8 content.
- Candidate creation does not imply approval. Published manifests and
  `snapshot.json` are immutable; retirement is recorded as an append-only
  `history.jsonl` event and does not rewrite a published manifest.

Taxonomy and release IDs are never reused, including after retirement.
Published versions are never overwritten. An existing release is verified
against both its manifest digest and the bundled source-artifact digests.
Source paths must resolve to files inside the configured artifact root; paths
outside it and traversal attempts are rejected.

## Lifecycle and release gates

1. **Working draft** — editable source framework and candidate artifacts.
2. **Validation** — run structural, mapping, and approval-readiness checks.
3. **Human review and approval** — record real approvals and audit events in
   the decision register. Do not manufacture reviewer evidence.
4. **Release candidate** — create a versioned snapshot and manifest. Candidate
   status is not publication authorization.
5. **Immutable published release** — promotion succeeds only when the
   framework is approved, all three report gates pass, no gate failure or
   blocker remains, the decision register validates, and referenced human
   approvals have matching audit events, reviewers, and timezone-aware times.
6. **Retired / superseded** — append a retirement event. Preserve the
   published manifest and snapshot for historical verification.

The manager refuses publication if the framework is still draft, the report is
stale relative to any source artifact captured in the candidate, any readiness
gate fails, the decision register is malformed, a referenced approval is not
approved, or an ID/version has already been used. A failed attempt leaves the
candidate unpromoted and reports the blocking reasons.

## Stable Skill IDs and semantic changes

A Skill ID identifies one construct, not a label slot. Do not reassign a
stable ID to a different construct. If a published Skill's meaning changes,
create a new Skill ID and record an explicit successor/compatibility decision.
The candidate manager compares stable Skill definitions across parent and
candidate snapshots and rejects changed definitions under a reused ID.
Compatible non-semantic corrections should be reviewed and documented; they
must not silently rewrite historical results.

The compatibility classification (backward compatible, changed measurement,
deprecated, or successor Skill) must be decided by humans for each change.
This file-based release mechanism does not invent those decisions or assign
them automatically.

## File-based limitations

The manager provides integrity checks, unique IDs, write-once publication,
and a local append-only retirement record. It is not a cryptographic signature
service, access-control boundary, or production audit database. Repository
permissions and protected review workflows remain necessary. No PostgreSQL
migration or synchronization is part of CF-03.4B.
