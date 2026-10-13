# Human Review Workflow — CF-03.4A

## Purpose and boundary

This workflow prepares expert review of the draft TSA/HSA framework and its
Skill specifications. It records no real decisions by itself and does not
approve, publish, or synchronize taxonomy data. The framework and candidate
files remain unchanged until an authorized human review and a separately
versioned change process exist.

AI-generated summaries, mappings, proposed labels, and queue entries are
review aids only. An AI or system actor cannot be a reviewer or make a final
decision.

## Review states and permitted transitions

| State | Meaning |
| --- | --- |
| `pending_review` | Not yet assigned or reviewed. |
| `in_review` | A human reviewer has taken ownership; not an approval. |
| `needs_revision` | Review identified changes or evidence needed before a new review. |
| `approved` | A human made an evidence-backed approval for the specified target and taxonomy version. |
| `rejected` | A human rejected the target or proposal with a reason and evidence. |

Permitted transitions:

- `pending_review` → `in_review`
- `in_review` → `pending_review` (reviewer releases ownership)
- `in_review` → `needs_revision`
- `in_review` → `approved`
- `in_review` → `rejected`
- `needs_revision` → `in_review`

No direct `pending_review` → final transition is permitted. `approved` and
`rejected` are terminal for that decision record. Reconsideration creates a
new decision with a new ID and links the previous record through
`supersedes_decision_id`; it never edits or deletes old decisions or events.
Changing a decision or target is not an implicit state transition.

Approval is invalid without a named human reviewer, a timezone-aware review
timestamp, a reason, evidence references, and a taxonomy version. A `skill` or
`skill_mapping` cannot be approved while its mapping is invalid, and no
candidate is approved by virtue of passing structural validation.

## Review procedure

1. Select an open row in `review_queue.csv`; verify its evidence references and
   the exact target/version under review.
2. Assign a human subject-matter reviewer. Record a state transition to
   `in_review` and append an audit event. Do not replace a previous event.
3. Evaluate taxonomy placement, operational definition, observable response,
   scope boundaries, representative item/rubric, and overlap. Use the
   applicable checklist and record evidence, including item and rubric IDs
   where available.
4. Choose `needs_revision`, `approved`, or `rejected` only after recording the
   reason, human reviewer identity, timezone-aware time, evidence, and
   version. If more work is needed, leave the outcome unresolved.
5. For an approved taxonomy change, make the change in a new versioned
   proposal. Do not edit source framework or candidate review CSVs through
   this preparation workflow.
6. Re-run structural and mapping gates after approved changes. Publication
   requires the separate versioning/release policy and approval gates; CF-03.4A
   does not authorize publication.

## Decision Register

`decision_register.json` uses `decision_register.schema.json` and has two
append-only collections:

- `decisions`: one record per decision/revision, with stable `decision_id`,
  target, state, outcome, reason, human reviewer, review time, evidence
  references, proposed change, taxonomy version, creation time, and optional
  superseded decision ID.
- `audit_events`: immutable events with unique `event_id`, linked decision and
  target, before/after state, human actor, timezone-aware event time, reason,
  evidence references, and taxonomy version.

Both collections are empty until real authorized decisions exist. Pending
work belongs in `review_queue.csv`, not as fabricated decision records.
Validation checks target existence against the framework and candidate
artifacts; a genuinely new target must explicitly set `target_is_proposed`.
The JSON Schema checks shape; the workflow validator additionally checks
uniqueness, targets, timezone offsets, human-only final decisions, audit
completeness, and state transitions.

## Queue coverage

The queue is generated from the current CF-03.3 report and source review
artifacts. It includes:

- A human measurement/specification review for every TSA and HSA candidate.
- A separate mapping task for each current mapping-readiness blocker.
- Existing TSA Competencies with no Domains and TSA Domains with no Topics.
- Human decisions about TSA Reading Text Type, HSA Science primary path and
  `subject_choice`, HSA English proficiency versus M1–M3/difficulty, optional
  branch semantics, and taxonomy versioning.
- Representative item and scoring evidence for each of the 77 HSA candidates;
  structural `matched` status is not treated as measurement evidence.

Queue records always start with `queue_status=open` and
`review_status=pending_review`; they contain no reviewer identity or approval.
Regenerate with:

```powershell
.\.venv\Scripts\python.exe -m crawler.validation.human_review_workflow
```

Validate existing artifacts without writing:

```powershell
.\.venv\Scripts\python.exe -m crawler.validation.human_review_workflow --validate-only
```

