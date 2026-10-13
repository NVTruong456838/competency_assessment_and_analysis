"""Human review queue and append-only decision-register validation."""

from __future__ import annotations

import argparse
import csv
import io
import json
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Any


CRAWLER_ROOT = Path(__file__).resolve().parents[1]
TAXONOMY_ROOT = CRAWLER_ROOT / "taxonomy"
REVIEW_ROOT = TAXONOMY_ROOT / "review"
WORKFLOW_ROOT = REVIEW_ROOT / "workflow"
VALIDATION_ROOT = REVIEW_ROOT / "validation"

QUEUE_FIELDS = (
    "queue_item_id",
    "target_type",
    "target_id",
    "target_is_proposed",
    "exam",
    "section_id",
    "priority",
    "issue_category",
    "summary",
    "required_decision",
    "evidence_refs",
    "queue_status",
    "review_status",
)
VALID_REVIEW_STATES = {
    "pending_review",
    "in_review",
    "needs_revision",
    "approved",
    "rejected",
}
VALID_TARGET_TYPES = {
    "framework",
    "competency",
    "domain",
    "topic",
    "skill",
    "skill_mapping",
    "taxonomy_change",
}
VALID_DECISIONS = {"retain", "revise", "split", "merge", "reparent", "reject"}
VALID_TRANSITIONS = {
    ("pending_review", "in_review"),
    ("in_review", "pending_review"),
    ("in_review", "needs_revision"),
    ("in_review", "approved"),
    ("in_review", "rejected"),
    ("needs_revision", "in_review"),
}
FINAL_STATES = {"approved", "rejected"}
SKILL_SPEC_FILES = {
    "TSA": "tsa_skill_specification.csv",
    "HSA": "hsa_skill_specification.csv",
}
SKILL_MAPPING_FILES = {
    "TSA": "tsa_skill_candidate_mapping.csv",
    "HSA": "hsa_skill_candidate_mapping.csv",
}
HSA_QUESTION_SECTIONS = {
    "HSA_MATH_DATA",
    "HSA_LITERATURE_LANGUAGE",
    "HSA_SCIENCE",
    "HSA_ENGLISH",
}


def _read_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as file:
        return json.load(file)


def _read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as file:
        reader = csv.DictReader(file, strict=True)
        if reader.fieldnames is None or len(set(reader.fieldnames)) != len(reader.fieldnames):
            raise ValueError(f"Missing or duplicate CSV headers: {path}")
        rows = list(reader)
        if any(None in row for row in rows):
            raise ValueError(f"CSV row has more values than headers: {path}")
        return rows


def _timestamp_has_timezone(value: Any) -> bool:
    if not isinstance(value, str) or not value.strip():
        return False
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return False
    return parsed.tzinfo is not None and parsed.utcoffset() is not None


def validate_decision_register(
    register: dict[str, Any],
    known_target_ids: set[str],
    mapping_ready_ids: set[str] | None = None,
) -> list[str]:
    """Return validation errors without changing the supplied register."""

    errors: list[str] = []
    if register.get("schema_version") != "1.0":
        errors.append("schema_version must be '1.0'.")

    decisions = register.get("decisions")
    events = register.get("audit_events")
    if not isinstance(decisions, list):
        errors.append("decisions must be an array.")
        decisions = []
    if not isinstance(events, list):
        errors.append("audit_events must be an array.")
        events = []

    decision_ids: set[str] = set()
    for index, decision in enumerate(decisions):
        prefix = f"decisions[{index}]"
        if not isinstance(decision, dict):
            errors.append(f"{prefix} must be an object.")
            continue
        required_fields = {
            "decision_id",
            "target_type",
            "target_id",
            "target_is_proposed",
            "exam",
            "review_status",
            "decision",
            "reason",
            "reviewer",
            "reviewer_type",
            "reviewed_at",
            "evidence_refs",
            "proposed_change",
            "taxonomy_version",
            "created_at",
            "supersedes_decision_id",
        }
        missing_fields = required_fields - decision.keys()
        if missing_fields:
            errors.append(
                f"{prefix} is missing fields: {', '.join(sorted(missing_fields))}."
            )
        decision_id = decision.get("decision_id")
        if not isinstance(decision_id, str) or not decision_id.strip():
            errors.append(f"{prefix}.decision_id is required.")
        elif decision_id in decision_ids:
            errors.append(f"Duplicate decision_id: {decision_id}.")
        else:
            decision_ids.add(decision_id)
        if decision.get("target_type") not in VALID_TARGET_TYPES:
            errors.append(f"{prefix}.target_type is invalid.")
        if decision.get("review_status") not in VALID_REVIEW_STATES:
            errors.append(f"{prefix}.review_status is invalid.")
        outcome = decision.get("decision")
        if outcome is not None and outcome not in VALID_DECISIONS:
            errors.append(f"{prefix}.decision is invalid.")
        if not isinstance(decision.get("target_is_proposed"), bool):
            errors.append(f"{prefix}.target_is_proposed must be boolean.")
        target_id = decision.get("target_id")
        if not isinstance(target_id, str) or not target_id.strip():
            errors.append(f"{prefix}.target_id is required.")
        elif not decision.get("target_is_proposed", False) and target_id not in known_target_ids:
            errors.append(f"{prefix}.target_id does not exist: {target_id}.")

        status = decision.get("review_status")
        if status in FINAL_STATES or outcome is not None:
            if status == "approved" and outcome == "reject":
                errors.append(f"{prefix} cannot mark a rejected outcome approved.")
            if status == "rejected" and outcome != "reject":
                errors.append(f"{prefix} must use a reject outcome when rejected.")
            reviewer = decision.get("reviewer")
            if not isinstance(reviewer, str) or not reviewer.strip():
                errors.append(f"{prefix}.reviewer is required for a decision.")
            if not _timestamp_has_timezone(decision.get("reviewed_at")):
                errors.append(f"{prefix}.reviewed_at must be a timezone-aware timestamp.")
            if decision.get("reviewer_type") != "human":
                errors.append(f"{prefix} must identify a human reviewer.")
            if not isinstance(decision.get("reason"), str) or not decision["reason"].strip():
                errors.append(f"{prefix}.reason is required for a decision.")
            evidence = decision.get("evidence_refs")
            if not isinstance(evidence, list) or not evidence:
                errors.append(f"{prefix}.evidence_refs must contain evidence.")
            version = decision.get("taxonomy_version")
            if not isinstance(version, str) or not version.strip():
                errors.append(f"{prefix}.taxonomy_version is required for a decision.")
            if isinstance(reviewer, str) and reviewer.strip().lower() in {
                "ai",
                "assistant",
                "copilot",
                "system",
                "automated",
            }:
                errors.append(f"{prefix} cannot use an AI or system reviewer.")
            if (
                status == "approved"
                and decision.get("target_type") in {"skill", "skill_mapping"}
                and (
                    mapping_ready_ids is None
                    or target_id not in mapping_ready_ids
                )
            ):
                errors.append(
                    f"{prefix} cannot approve a skill with an invalid or unverified mapping."
                )
        elif decision.get("reviewer_type") not in {None, "human"}:
            errors.append(f"{prefix}.reviewer_type must be human or null.")
        if decision.get("reviewed_at") is not None and not _timestamp_has_timezone(
            decision.get("reviewed_at")
        ):
            errors.append(f"{prefix}.reviewed_at must be a timezone-aware timestamp.")
        if not _timestamp_has_timezone(decision.get("created_at")):
            errors.append(f"{prefix}.created_at must be a timezone-aware timestamp.")

    seen_event_ids: set[str] = set()
    for index, event in enumerate(events):
        prefix = f"audit_events[{index}]"
        if not isinstance(event, dict):
            errors.append(f"{prefix} must be an object.")
            continue
        required_fields = {
            "event_id",
            "decision_id",
            "target_type",
            "target_id",
            "target_is_proposed",
            "status_before",
            "status_after",
            "performed_by",
            "actor_type",
            "occurred_at",
            "reason",
            "evidence_refs",
            "taxonomy_version",
        }
        missing_fields = required_fields - event.keys()
        if missing_fields:
            errors.append(
                f"{prefix} is missing fields: {', '.join(sorted(missing_fields))}."
            )
        event_id = event.get("event_id")
        if not isinstance(event_id, str) or not event_id.strip():
            errors.append(f"{prefix}.event_id is required.")
        elif event_id in seen_event_ids:
            errors.append(f"Duplicate event_id: {event_id}.")
        else:
            seen_event_ids.add(event_id)

        before = event.get("status_before")
        after = event.get("status_after")
        if before not in VALID_REVIEW_STATES or after not in VALID_REVIEW_STATES:
            errors.append(f"{prefix} must include valid before and after states.")
        elif before != after and (before, after) not in VALID_TRANSITIONS:
            errors.append(f"{prefix} has an invalid state transition: {before} -> {after}.")
        if event.get("actor_type") != "human":
            errors.append(f"{prefix} must be performed by a human.")
        if event.get("target_type") not in VALID_TARGET_TYPES:
            errors.append(f"{prefix}.target_type is invalid.")
        if not isinstance(event.get("target_is_proposed"), bool):
            errors.append(f"{prefix}.target_is_proposed must be boolean.")
        if not isinstance(event.get("performed_by"), str) or not event["performed_by"].strip():
            errors.append(f"{prefix}.performed_by is required.")
        if not _timestamp_has_timezone(event.get("occurred_at")):
            errors.append(f"{prefix}.occurred_at must have a timezone.")
        if not isinstance(event.get("reason"), str) or not event["reason"].strip():
            errors.append(f"{prefix}.reason is required.")
        if not isinstance(event.get("evidence_refs"), list) or not event["evidence_refs"]:
            errors.append(f"{prefix}.evidence_refs must contain evidence.")
        if event.get("decision_id") not in decision_ids:
            errors.append(f"{prefix}.decision_id must reference a registered decision.")
        else:
            linked = next(
                item
                for item in decisions
                if isinstance(item, dict)
                and item.get("decision_id") == event.get("decision_id")
            )
            if (
                event.get("target_type") != linked.get("target_type")
                or event.get("target_id") != linked.get("target_id")
                or event.get("target_is_proposed") != linked.get("target_is_proposed")
            ):
                errors.append(f"{prefix} target does not match its decision record.")
        target_id = event.get("target_id")
        if (
            not isinstance(target_id, str)
            or not target_id.strip()
            or (not event.get("target_is_proposed", False) and target_id not in known_target_ids)
        ):
            errors.append(f"{prefix}.target_id must identify an existing or proposed target.")
    return errors


class HumanReviewWorkflow:
    """Build and validate queue artifacts from the current review evidence."""

    def __init__(
        self,
        review_root: Path = REVIEW_ROOT,
        workflow_root: Path = WORKFLOW_ROOT,
        validation_root: Path = VALIDATION_ROOT,
        schema_path: Path | None = None,
        framework_path: Path | None = None,
        proposed_changes_path: Path | None = None,
    ):
        self.review_root = Path(review_root)
        self.workflow_root = Path(workflow_root)
        self.validation_root = Path(validation_root)
        self.schema_path = schema_path or WORKFLOW_ROOT / "decision_register.schema.json"
        self.framework_path = framework_path or TAXONOMY_ROOT / "competency_framework.json"
        self.proposed_changes_path = (
            proposed_changes_path or self.review_root / "proposed_taxonomy_changes.md"
        )

    def build_queue(self) -> list[dict[str, str]]:
        report = _read_json(
            self.validation_root / "taxonomy_validation_report.json"
        )
        framework = _read_json(self.framework_path)
        specs = {
            exam: _read_csv(self.review_root / filename)
            for exam, filename in SKILL_SPEC_FILES.items()
        }
        mappings = {
            exam: _read_csv(self.review_root / filename)
            for exam, filename in SKILL_MAPPING_FILES.items()
        }
        spec_by_id = {
            exam: {row["skill_id"]: row for row in rows}
            for exam, rows in specs.items()
        }
        mapping_by_id = {
            exam: {row["candidate_skill_id"]: row for row in rows}
            for exam, rows in mappings.items()
        }
        blockers = {
            item["location"]: item
            for item in report["mapping_readiness"]["blockers"]
        }
        queue: list[dict[str, str]] = []

        for exam in ("TSA", "HSA"):
            if len(spec_by_id[exam]) != len(specs[exam]):
                raise ValueError(f"Duplicate {exam} skill specification candidate ID.")
            if len(mapping_by_id[exam]) != len(mappings[exam]):
                raise ValueError(f"Duplicate {exam} skill mapping candidate ID.")
            if set(spec_by_id[exam]) != set(mapping_by_id[exam]):
                raise ValueError(f"{exam} specification and mapping candidate IDs differ.")
            for skill_id, spec in spec_by_id[exam].items():
                if exam == "HSA" and spec.get("section_id") not in HSA_QUESTION_SECTIONS:
                    raise ValueError(
                        f"{skill_id} uses a non-question-bearing HSA section."
                    )
                is_hsa = exam == "HSA"
                evidence_refs = [f"taxonomy/review/{SKILL_SPEC_FILES[exam]}#{skill_id}"]
                if is_hsa:
                    evidence_refs.append("taxonomy/review/taxonomy_gap_resolution.md#hsa")
                    summary = (
                        f"Review the definition, observable action, evidence, and distinction "
                        f"for {skill_id}; obtain a representative HSA item and scoring key."
                    )
                    category = "representative_item_missing"
                else:
                    summary = (
                        f"Review the definition, observable action, assessment evidence, "
                        f"scope, and distinction for {skill_id}."
                    )
                    category = "skill_specification_review"
                self._add(
                    queue,
                    target_type="skill",
                    target_id=skill_id,
                    exam=exam,
                    section_id=spec.get("section_id", ""),
                    priority="high" if is_hsa else "medium",
                    category=category,
                    summary=summary,
                    required_decision=(
                        "Record retain/revise/split/merge/reject with scored response evidence; "
                        "keep pending until an authorized human reviewer decides."
                    ),
                    evidence_refs=evidence_refs,
                )

                blocker = blockers.get(skill_id)
                mapping = mapping_by_id[exam][skill_id]
                if blocker is not None:
                    self._add(
                        queue,
                        target_type="skill_mapping",
                        target_id=skill_id,
                        exam=exam,
                        section_id=mapping.get("section_id", ""),
                        priority="high",
                        category=blocker["code"],
                        summary=blocker["message"],
                        required_decision=(
                            "Resolve the primary canonical Topic path or explicitly resolve the "
                            "expert classification; do not invent or approve a Topic ID."
                        ),
                        evidence_refs=[
                            "taxonomy/review/validation/taxonomy_validation_report.json"
                            f"#mapping_readiness/{skill_id}",
                            f"taxonomy/review/{SKILL_MAPPING_FILES[exam]}#{skill_id}",
                            "taxonomy/review/proposed_taxonomy_changes.md",
                        ],
                    )

        self._add_missing_tsa_nodes(queue, framework)
        self._add_policy_items(queue)
        return queue

    @staticmethod
    def _add(
        queue: list[dict[str, str]],
        *,
        target_type: str,
        target_id: str,
        exam: str,
        section_id: str,
        priority: str,
        category: str,
        summary: str,
        required_decision: str,
        evidence_refs: list[str],
        target_is_proposed: bool = False,
    ) -> None:
        queue.append(
            {
                "queue_item_id": f"RQ-{len(queue) + 1:04d}",
                "target_type": target_type,
                "target_id": target_id,
                "target_is_proposed": str(target_is_proposed).lower(),
                "exam": exam,
                "section_id": section_id,
                "priority": priority,
                "issue_category": category,
                "summary": summary,
                "required_decision": required_decision,
                "evidence_refs": ";".join(evidence_refs),
                "queue_status": "open",
                "review_status": "pending_review",
            }
        )

    def _add_missing_tsa_nodes(
        self,
        queue: list[dict[str, str]],
        framework: dict[str, Any],
    ) -> None:
        for exam in framework.get("exams", []):
            if exam.get("id") != "TSA":
                continue
            for section in exam.get("sections", []):
                for competency in section.get("competencies", []):
                    if not competency.get("domains"):
                        self._add(
                            queue,
                            target_type="competency",
                            target_id=competency["id"],
                            exam="TSA",
                            section_id=section["id"],
                            priority="high",
                            category="competency_without_domain",
                            summary=f"Review proposed Domain structure for {competency['id']}.",
                            required_decision=(
                                "Decide whether to add Domains or model this as a cross-cutting "
                                "competency; no IDs are assigned in this review queue."
                            ),
                            evidence_refs=[
                                "taxonomy/competency_framework.json",
                                "taxonomy/review/proposed_taxonomy_changes.md#tsa-math",
                                "taxonomy/review/taxonomy_gap_resolution.md#tsa-math",
                            ],
                        )
                    for domain in competency.get("domains", []):
                        if not domain.get("topics"):
                            self._add(
                                queue,
                                target_type="domain",
                                target_id=domain["id"],
                                exam="TSA",
                                section_id=section["id"],
                                priority="high",
                                category="domain_without_topic",
                                summary=f"Review Topic coverage and boundaries for {domain['id']}.",
                                required_decision=(
                                    "Approve a scope and boundary proposal before creating a "
                                    "canonical Topic ID; keep this item unresolved meanwhile."
                                ),
                                evidence_refs=[
                                    "taxonomy/competency_framework.json",
                                    "taxonomy/review/proposed_taxonomy_changes.md",
                                    "taxonomy/review/taxonomy_gap_resolution.md",
                                ],
                            )

    def _add_policy_items(self, queue: list[dict[str, str]]) -> None:
        policies = [
            (
                "POLICY:TSA_READING_TEXT_TYPE",
                "TSA",
                "TSA_READING",
                "text_type_vs_skill",
                "Decide whether Literary, Informational, and Argumentative are stimulus metadata, Topics, learner Skills, or separately modelled axes.",
                "taxonomy/review/proposed_taxonomy_changes.md#tsa-reading",
            ),
            (
                "POLICY:HSA_SCIENCE_PRIMARY_PATH",
                "HSA",
                "HSA_SCIENCE",
                "primary_path_policy",
                "Define the primary-path tie-breaker for items combining subject knowledge, data, and reasoning; retain item-level subject_choice where supported.",
                "taxonomy/review/taxonomy_gap_resolution.md#hsa",
            ),
            (
                "POLICY:HSA_ENGLISH_PROFICIENCY",
                "HSA",
                "HSA_ENGLISH",
                "proficiency_separation",
                "Define how A2-C1 proficiency is evidenced and kept independent of M1-M3 cognitive level and item difficulty.",
                "taxonomy/review/taxonomy_gap_resolution.md#hsa",
            ),
            (
                "POLICY:TAXONOMY_VERSIONING",
                "Cross-exam",
                "",
                "versioning_policy",
                "Define immutable taxonomy versions, compatibility and release rules, and audit semantics before publication or database integration.",
                "taxonomy/review/taxonomy_gap_resolution.md#canonical-id-and-persistence-notes",
            ),
            (
                "POLICY:HSA_BRANCH_SELECTION",
                "HSA",
                "HSA_OPTIONAL",
                "optional_branch_selection",
                "Confirm Science and English are mutually exclusive question-bearing branches and HSA_OPTIONAL remains only a selection container.",
                "taxonomy/competency_framework.json#HSA_OPTIONAL",
            ),
        ]
        for target_id, exam, section_id, category, summary, evidence in policies:
            self._add(
                queue,
                target_type="taxonomy_change",
                target_id=target_id,
                target_is_proposed=True,
                exam=exam,
                section_id=section_id,
                priority="high",
                category=category,
                summary=summary,
                required_decision=(
                    "Record an authorized, evidence-backed human decision and create a new "
                    "audit event; do not alter source taxonomy during preparation."
                ),
                evidence_refs=[evidence],
            )

    def validate_queue(self, queue: list[dict[str, str]]) -> list[str]:
        errors: list[str] = []
        seen_queue_ids: set[str] = set()
        for index, row in enumerate(queue):
            missing = [field for field in QUEUE_FIELDS if field not in row]
            if missing:
                errors.append(f"queue[{index}] missing fields: {', '.join(missing)}.")
                continue
            if row["queue_item_id"] in seen_queue_ids:
                errors.append(f"Duplicate queue_item_id: {row['queue_item_id']}.")
            seen_queue_ids.add(row["queue_item_id"])
            if row["review_status"] not in VALID_REVIEW_STATES:
                errors.append(f"{row['queue_item_id']} has invalid review_status.")
            if not row["evidence_refs"].strip():
                errors.append(f"{row['queue_item_id']} is missing evidence references.")
            if (
                row["exam"] == "HSA"
                and row["target_type"] in {"skill", "skill_mapping"}
                and row["section_id"] not in HSA_QUESTION_SECTIONS
            ):
                errors.append(
                    f"{row['queue_item_id']} uses a non-question-bearing HSA section."
                )

        report = _read_json(
            self.validation_root / "taxonomy_validation_report.json"
        )
        mapped_queue = {
            row["target_id"]
            for row in queue
            if row.get("target_type") == "skill_mapping"
        }
        blocker_ids = {
            issue["location"]
            for issue in report["mapping_readiness"]["blockers"]
            if issue.get("location")
        }
        missing_blockers = blocker_ids - mapped_queue
        if missing_blockers:
            errors.append(
                "Review queue is missing mapping blockers: "
                + ", ".join(sorted(missing_blockers))
            )

        spec_ids = {
            exam: {
                row["skill_id"]
                for row in _read_csv(self.review_root / filename)
            }
            for exam, filename in SKILL_SPEC_FILES.items()
        }
        for exam, ids in spec_ids.items():
            queued = {
                row["target_id"]
                for row in queue
                if row.get("target_type") == "skill" and row.get("exam") == exam
            }
            if ids != queued:
                errors.append(
                    f"{exam} skill review queue mismatch; missing={sorted(ids - queued)}, "
                    f"unexpected={sorted(queued - ids)}."
                )
        hsa_skills = {
            row["target_id"]
            for row in queue
            if row.get("target_type") == "skill"
            and row.get("exam") == "HSA"
            and row.get("issue_category") == "representative_item_missing"
        }
        if hsa_skills != spec_ids["HSA"]:
            errors.append("Review queue must include all HSA candidates for item evidence.")
        required_policies = {
            "POLICY:HSA_SCIENCE_PRIMARY_PATH",
            "POLICY:HSA_ENGLISH_PROFICIENCY",
            "POLICY:TAXONOMY_VERSIONING",
        }
        queued_policies = {
            row["target_id"]
            for row in queue
            if row.get("target_type") == "taxonomy_change"
        }
        if not required_policies <= queued_policies:
            errors.append(
                "Review queue is missing required policy items: "
                + ", ".join(sorted(required_policies - queued_policies))
            )
        return errors

    def generate(self) -> dict[str, Any]:
        queue = self.build_queue()
        errors = self.validate_queue(queue)
        if errors:
            raise ValueError("Review queue validation failed: " + " ".join(errors))
        self.workflow_root.mkdir(parents=True, exist_ok=True)
        queue_path = self.workflow_root / "review_queue.csv"
        buffer = io.StringIO(newline="")
        writer = csv.DictWriter(buffer, fieldnames=QUEUE_FIELDS, quoting=csv.QUOTE_MINIMAL)
        writer.writeheader()
        writer.writerows(queue)
        generated_queue = buffer.getvalue()
        if queue_path.exists():
            if queue_path.read_bytes() != generated_queue.encode("utf-8"):
                raise ValueError(
                    f"Refusing to overwrite an existing review queue: {queue_path}"
                )
        else:
            with queue_path.open("w", encoding="utf-8", newline="") as file:
                file.write(generated_queue)
        register_path = self.workflow_root / "decision_register.json"
        if not register_path.exists():
            register_path.write_text(
                json.dumps(
                    {"schema_version": "1.0", "decisions": [], "audit_events": []},
                    ensure_ascii=False,
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )
        with self.schema_path.open(encoding="utf-8") as file:
            json.load(file)
        decision_register = _read_json(register_path)
        known_ids = self._known_target_ids()
        register_errors = validate_decision_register(
            decision_register,
            known_ids,
            self._mapping_ready_target_ids(),
        )
        if register_errors:
            raise ValueError(
                "Decision register validation failed: " + " ".join(register_errors)
            )
        return {
            "queue_path": str(queue_path),
            "decision_register_path": str(register_path),
            "queue_count": len(queue),
            "queue_by_type": dict(Counter(row["target_type"] for row in queue)),
            "queue_by_category": dict(Counter(row["issue_category"] for row in queue)),
            "decision_count": len(decision_register["decisions"]),
            "audit_event_count": len(decision_register["audit_events"]),
        }

    def _known_target_ids(self) -> set[str]:
        framework = _read_json(self.framework_path)
        target_ids: set[str] = set()

        def add_nodes(nodes: list[dict[str, Any]]) -> None:
            for node in nodes:
                if isinstance(node, dict):
                    if isinstance(node.get("id"), str):
                        target_ids.add(node["id"])
                    for key, value in node.items():
                        if key in {"sections", "branches", "competencies", "domains", "topics"} and isinstance(value, list):
                            add_nodes(value)

        add_nodes(framework.get("exams", []))
        for exam, filename in SKILL_SPEC_FILES.items():
            target_ids.update(
                row["skill_id"]
                for row in _read_csv(self.review_root / filename)
            )
        for exam, filename in SKILL_MAPPING_FILES.items():
            target_ids.update(
                row["candidate_skill_id"]
                for row in _read_csv(self.review_root / filename)
            )
        return target_ids

    def _mapping_ready_target_ids(self) -> set[str]:
        all_candidates = {
            row["skill_id"]
            for filename in SKILL_SPEC_FILES.values()
            for row in _read_csv(self.review_root / filename)
        }
        report = _read_json(
            self.validation_root / "taxonomy_validation_report.json"
        )
        blocked = {
            issue["location"]
            for issue in report["mapping_readiness"]["blockers"]
            if issue.get("location")
        }
        return all_candidates - blocked


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--validate-only",
        action="store_true",
        help="Validate the existing review queue and decision register without writing.",
    )
    args = parser.parse_args()
    workflow = HumanReviewWorkflow()
    if args.validate_only:
        queue_path = workflow.workflow_root / "review_queue.csv"
        queue = _read_csv(queue_path)
        errors = workflow.validate_queue(queue)
        register = _read_json(workflow.workflow_root / "decision_register.json")
        errors.extend(
            validate_decision_register(
                register,
                workflow._known_target_ids(),
                workflow._mapping_ready_target_ids(),
            )
        )
        if errors:
            print("\n".join(errors))
            return 1
        print(f"Review queue PASS ({len(queue)} items); decision register PASS.")
        return 0
    result = workflow.generate()
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
