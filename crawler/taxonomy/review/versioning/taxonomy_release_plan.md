# Taxonomy Release Plan — CF-03.4B

## Release flow

```text
Working Draft
  -> structural validation
  -> mapping readiness
  -> human review and decision-register validation
  -> Release Candidate snapshot
  -> approval/readiness recheck and source-integrity verification
  -> Immutable Published Release
  -> append-only retirement event when superseded
```

A candidate is a reviewable snapshot, not an official publication. Publication
requires `structural_validation.passed`, `mapping_readiness.passed`,
`approval_readiness.passed`, empty failed/blocker arrays, and
`publication_ready=true`. The framework in the snapshot must have
`status="approved"`. Every approval reference must resolve to an approved
decision and a matching human audit event with reviewer and timezone-aware
timestamps.

## Artifact layout

Each candidate is stored under:

```text
<release-root>/<taxonomy_id>/<taxonomy_version>/<release_id>/
  manifest.json
  snapshot.json
  history.jsonl   # only when a published release is retired
```

`snapshot.json` contains the framework and exact UTF-8 text of every declared
source artifact, so the taxonomy, mapping/specification source evidence, and
their hashes remain locally verifiable after working files change. Include
the validation report and decision register in `source_artifact_paths`.
Snapshots and release manifests are canonical JSON. The only manifest
transition is candidate-to-published; once published, the manager rejects
updates. Retirement changes effective status through a separate append-only
event and leaves the published manifest untouched.

## Operations and refusal behavior

Instantiate `TaxonomyReleaseManager(artifact_root, release_root)`, call
`create_release_candidate(...)`, then `publish_release(...)` with the report,
register, and real `approval_decision_refs`. `verify_release(release_id)`
checks manifest fields, normalized snapshot SHA-256, source artifact
SHA-256s, and taxonomy counts. `list_releases()` returns effective status;
`retire_release(...)` records a human-attributed retirement event.

Candidate creation rejects reused taxonomy versions and release IDs, invalid
IDs/version formats, out-of-root source paths, corrupt parent releases, and
semantic changes under a reused Skill ID. Publication rejects any readiness,
approval, audit, hash, framework-status, or immutability failure and does not
promote the candidate.

## Current project gate (2026-10-12 report)

The current validation report has structural validation passing, but the
framework is still `draft`; mapping readiness is blocked by 36 mapping
blockers; approval readiness is false; the approval decision register is
empty; and `publication_ready` is false. CF-03.4B therefore creates no
candidate or published release from the current data. The release gate must
remain closed until a fresh report and genuine review evidence pass every
gate.
