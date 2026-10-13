import copy
import csv
import json
import tempfile
import unittest
from pathlib import Path

from crawler.validation.human_review_workflow import (
    CRAWLER_ROOT,
    REVIEW_ROOT,
    TAXONOMY_ROOT,
    VALIDATION_ROOT,
    WORKFLOW_ROOT,
    HumanReviewWorkflow,
    validate_decision_register,
)


class HumanReviewWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.workflow = HumanReviewWorkflow()
        self.known_targets = self.workflow._known_target_ids()
        self.mapping_ready_targets = self.workflow._mapping_ready_target_ids()
        self.valid_register = {
            "schema_version": "1.0",
            "decisions": [
                {
                    "decision_id": "DEC-001",
                    "target_type": "skill",
                    "target_id": "SKILL_MATH_EQUATION_SOLVE",
                    "target_is_proposed": False,
                    "exam": "TSA",
                    "review_status": "approved",
                    "decision": "retain",
                    "reason": "Evidence supports the construct boundary.",
                    "reviewer": "Reviewer Example",
                    "reviewer_type": "human",
                    "reviewed_at": "2026-10-12T14:00:00Z",
                    "evidence_refs": ["item-bank/ITEM-001", "rubric/RUBRIC-001"],
                    "proposed_change": None,
                    "taxonomy_version": "draft-1",
                    "created_at": "2026-10-12T14:00:00Z",
                    "supersedes_decision_id": None,
                }
            ],
            "audit_events": [
                {
                    "event_id": "EVT-001",
                    "decision_id": "DEC-001",
                    "target_type": "skill",
                    "target_id": "SKILL_MATH_EQUATION_SOLVE",
                    "target_is_proposed": False,
                    "status_before": "in_review",
                    "status_after": "approved",
                    "performed_by": "Reviewer Example",
                    "actor_type": "human",
                    "occurred_at": "2026-10-12T14:00:00Z",
                    "reason": "Evidence supports the construct boundary.",
                    "evidence_refs": ["item-bank/ITEM-001"],
                    "taxonomy_version": "draft-1",
                }
            ],
        }

    def test_empty_decision_register_is_valid_and_schema_loads(self):
        schema_path = WORKFLOW_ROOT / "decision_register.schema.json"
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        empty = json.loads(
            (WORKFLOW_ROOT / "decision_register.json").read_text(encoding="utf-8")
        )

        self.assertEqual(schema["$schema"], "https://json-schema.org/draft/2020-12/schema")
        self.assertEqual(
            validate_decision_register(
                empty, self.known_targets, self.mapping_ready_targets
            ),
            [],
        )
        self.assertEqual(empty["decisions"], [])
        self.assertEqual(empty["audit_events"], [])

    def test_valid_decision_and_audit_event_pass(self):
        self.assertEqual(
            validate_decision_register(
                self.valid_register, self.known_targets, self.mapping_ready_targets
            ),
            [],
        )

    def test_duplicate_decision_id_is_rejected(self):
        register = copy.deepcopy(self.valid_register)
        register["decisions"].append(copy.deepcopy(register["decisions"][0]))

        errors = validate_decision_register(
            register, self.known_targets, self.mapping_ready_targets
        )

        self.assertTrue(any("Duplicate decision_id" in error for error in errors))

    def test_final_decision_without_reviewer_is_rejected(self):
        register = copy.deepcopy(self.valid_register)
        register["decisions"][0]["reviewer"] = ""

        errors = validate_decision_register(
            register, self.known_targets, self.mapping_ready_targets
        )

        self.assertTrue(any(".reviewer is required" in error for error in errors))

    def test_timestamp_without_timezone_is_rejected(self):
        register = copy.deepcopy(self.valid_register)
        register["decisions"][0]["reviewed_at"] = "2026-10-12T14:00:00"

        errors = validate_decision_register(
            register, self.known_targets, self.mapping_ready_targets
        )

        self.assertTrue(any("timezone-aware" in error for error in errors))

    def test_unknown_target_is_rejected(self):
        register = copy.deepcopy(self.valid_register)
        register["decisions"][0]["target_id"] = "UNKNOWN_SKILL"

        errors = validate_decision_register(
            register, self.known_targets, self.mapping_ready_targets
        )

        self.assertTrue(any("does not exist" in error for error in errors))

    def test_illegal_state_transition_is_rejected(self):
        register = copy.deepcopy(self.valid_register)
        register["audit_events"][0]["status_before"] = "pending_review"

        errors = validate_decision_register(
            register, self.known_targets, self.mapping_ready_targets
        )

        self.assertTrue(any("invalid state transition" in error for error in errors))

    def test_blocked_skill_mapping_cannot_be_approved(self):
        register = copy.deepcopy(self.valid_register)
        blocked_id = "SKILL_MATH_NUMBER_REMAINDER"
        register["decisions"][0]["target_id"] = blocked_id
        register["audit_events"][0]["target_id"] = blocked_id

        errors = validate_decision_register(
            register, self.known_targets, self.mapping_ready_targets
        )

        self.assertTrue(any("cannot approve a skill" in error for error in errors))

    def test_ai_cannot_auto_approve(self):
        register = copy.deepcopy(self.valid_register)
        register["decisions"][0]["reviewer"] = "Copilot"
        register["decisions"][0]["reviewer_type"] = "ai"
        register["audit_events"][0]["performed_by"] = "Copilot"
        register["audit_events"][0]["actor_type"] = "ai"

        errors = validate_decision_register(
            register, self.known_targets, self.mapping_ready_targets
        )

        self.assertTrue(any("AI or system reviewer" in error for error in errors))
        self.assertTrue(any("human reviewer" in error for error in errors))
        self.assertTrue(any("performed by a human" in error for error in errors))

    def test_review_queue_includes_every_current_blocker(self):
        queue = self.workflow.build_queue()
        mapping_ids = {
            row["target_id"]
            for row in queue
            if row["target_type"] == "skill_mapping"
        }
        report = json.loads(
            (VALIDATION_ROOT / "taxonomy_validation_report.json").read_text(
                encoding="utf-8"
            )
        )
        blocker_ids = {
            issue["location"]
            for issue in report["mapping_readiness"]["blockers"]
        }

        self.assertEqual(mapping_ids, blocker_ids)
        self.assertEqual(self.workflow.validate_queue(queue), [])

    def test_review_queue_rejects_missing_blocker(self):
        queue = self.workflow.build_queue()
        first_mapping = next(row for row in queue if row["target_type"] == "skill_mapping")
        queue.remove(first_mapping)

        errors = self.workflow.validate_queue(queue)

        self.assertTrue(any("missing mapping blockers" in error for error in errors))

    def test_hsa_optional_cannot_be_used_as_question_section(self):
        queue = self.workflow.build_queue()
        hsa_skill = next(
            row
            for row in queue
            if row["target_type"] == "skill" and row["exam"] == "HSA"
        )
        hsa_skill["section_id"] = "HSA_OPTIONAL"

        errors = self.workflow.validate_queue(queue)

        self.assertTrue(
            any("non-question-bearing HSA section" in error for error in errors)
        )

    def test_audit_event_requires_before_and_after_states(self):
        register = copy.deepcopy(self.valid_register)
        del register["audit_events"][0]["status_before"]
        del register["audit_events"][0]["status_after"]

        errors = validate_decision_register(
            register, self.known_targets, self.mapping_ready_targets
        )

        self.assertTrue(any("valid before and after states" in error for error in errors))

    def test_target_can_be_explicitly_marked_proposed(self):
        register = copy.deepcopy(self.valid_register)
        register["decisions"][0]["target_type"] = "topic"
        register["decisions"][0]["target_id"] = "PROPOSED:TSA_TOPIC_EXAMPLE"
        register["decisions"][0]["target_is_proposed"] = True
        register["audit_events"][0]["target_type"] = "topic"
        register["audit_events"][0]["target_id"] = "PROPOSED:TSA_TOPIC_EXAMPLE"
        register["audit_events"][0]["target_is_proposed"] = True

        self.assertEqual(
            validate_decision_register(
                register, self.known_targets, self.mapping_ready_targets
            ),
            [],
        )

    def test_generation_keeps_source_artifacts_unchanged(self):
        source_files = [
            TAXONOMY_ROOT / "competency_framework.json",
            REVIEW_ROOT / "proposed_taxonomy_changes.md",
            REVIEW_ROOT / "tsa_skill_specification.csv",
            REVIEW_ROOT / "hsa_skill_specification.csv",
            VALIDATION_ROOT / "taxonomy_validation_report.json",
        ]
        before = {path: path.read_bytes() for path in source_files}
        with tempfile.TemporaryDirectory() as directory:
            result = HumanReviewWorkflow(
                workflow_root=Path(directory) / "workflow"
            ).generate()

        self.assertEqual(result["decision_count"], 0)
        self.assertEqual(result["audit_event_count"], 0)
        self.assertEqual(before, {path: path.read_bytes() for path in source_files})

    def test_generation_refuses_to_overwrite_a_reviewed_queue(self):
        with tempfile.TemporaryDirectory() as directory:
            workflow_root = Path(directory) / "workflow"
            workflow_root.mkdir()
            queue_path = workflow_root / "review_queue.csv"
            queue_path.write_text("human-reviewed queue data\n", encoding="utf-8")
            workflow = HumanReviewWorkflow(workflow_root=workflow_root)

            with self.assertRaisesRegex(ValueError, "Refusing to overwrite"):
                workflow.generate()

            self.assertEqual(
                queue_path.read_text(encoding="utf-8"),
                "human-reviewed queue data\n",
            )

    def test_generated_queue_covers_review_tasks_and_hsa_item_evidence(self):
        queue = self.workflow.build_queue()
        skill_reviews = [row for row in queue if row["target_type"] == "skill"]
        hsa_item_reviews = [
            row
            for row in skill_reviews
            if row["issue_category"] == "representative_item_missing"
        ]

        self.assertEqual(len(skill_reviews), 125)
        self.assertEqual(len(hsa_item_reviews), 77)
        self.assertEqual(
            len([row for row in queue if row["target_type"] == "skill_mapping"]),
            36,
        )
        self.assertTrue(
            {
                "POLICY:HSA_SCIENCE_PRIMARY_PATH",
                "POLICY:HSA_ENGLISH_PROFICIENCY",
                "POLICY:TAXONOMY_VERSIONING",
            }
            <= {
                row["target_id"]
                for row in queue
                if row["target_type"] == "taxonomy_change"
            }
        )
        self.assertTrue(all(row["review_status"] == "pending_review" for row in queue))
        self.assertTrue(all(row["queue_status"] == "open" for row in queue))


if __name__ == "__main__":
    unittest.main()
