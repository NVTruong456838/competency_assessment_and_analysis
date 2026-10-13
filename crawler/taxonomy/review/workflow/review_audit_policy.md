# Human Review Audit Policy — CF-03.4A

## Principles

- Decision and audit data are append-only. Never overwrite or delete an event
  or prior decision to represent a correction or reversal.
- Every event identifies one target, its previous and resulting state, the
  human actor, a timezone-aware timestamp, a reason, and evidence references.
- Record times as ISO 8601 with an explicit UTC offset, preferably UTC
  (`2026-10-12T14:00:00Z`).
- A final decision requires an identifiable authorized human reviewer,
  evidence, reason, and taxonomy version. AI output is not review evidence
  unless a human independently evaluates and cites the underlying source.
- Preserve source artifact snapshots or immutable repository revisions as
  evidence references when making decisions. Do not use mutable display names
  as the sole target identity.
- Decisions apply to the explicitly recorded target and version only; they do
  not silently approve descendants, mappings, or later versions.

## Event and decision handling

1. Create a decision record with a unique `DEC-...` ID. For a pending item,
   keep the register empty until a real reviewer action occurs.
2. On each state change, append an `EVT-...` event that references the
   decision ID and records `status_before` and `status_after`.
3. Keep the decision record immutable after finalization. A correction or
   reconsideration uses a new decision ID, identifies the superseded decision,
   and appends a new event; it does not erase history.
4. Use `target_is_proposed=true` only for a proposed target that does not yet
   have a canonical ID. Never reserve a proposed label as an official ID by
   recording it in this register.
5. If an event or decision is malformed, retain the original evidence and
   append a corrective event/decision with an explanation. Do not silently
   repair the historical record.

## Access, retention, and release

The project has not yet selected an authoritative storage service, access
control model, retention period, or signing mechanism. These are open CF-03.4B
governance decisions; JSON files in the repository are review artifacts, not a
production audit store. Restrict production reviewer identity and evidence
access according to organizational policy once that storage decision is made.

No taxonomy publication or database synchronization is permitted solely
because a decision register is schema-valid. Release must also establish
version identity, complete reviewer evidence, mapping readiness, resolved
blockers, and a separately approved release record.
