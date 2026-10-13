import copy
import csv
import json
import tempfile
import unittest
from pathlib import Path

from crawler.taxonomy.taxonomy_release_manager import (
    MANIFEST_NAME,
    SNAPSHOT_NAME,
    ReleaseGateError,
    TaxonomyReleaseManager,
)


class TaxonomyReleaseManagerTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.artifact_root = self.root / "artifacts"
        self.artifact_root.mkdir()
        self.release_root = self.root / "releases"
        self.manager = TaxonomyReleaseManager(self.artifact_root, self.release_root)
        self.framework_path = self.artifact_root / "framework.json"
        self.spec_path = self.artifact_root / "skills.csv"
        self.mapping_path = self.artifact_root / "mappings.csv"
        self.report_path = self.artifact_root / "validation_report.json"
        self.register_path = self.artifact_root / "decision_register.json"
        self._write_fixture_files()

    def tearDown(self):
        self.temp_dir.cleanup()

    def _write_fixture_files(self, framework_status="approved", mapping_passed=True,
                             approval_passed=True, register=None):
        framework = {
            "version": "fixture",
            "status": framework_status,
            "exams": [
                {
                    "id": "EXAM-1",
                    "sections": [
                        {
                            "id": "SECTION-1",
                            "competencies": [
                                {
                                    "id": "COMP-1",
                                    "domains": [
                                        {
                                            "id": "DOMAIN-1",
                                            "topics": [{"id": "TOPIC-1", "name": "Topic"}],
                                        }
                                    ],
                                }
                            ],
                        }
                    ],
                }
            ],
        }
        self.framework_path.write_text(json.dumps(framework), encoding="utf-8")
        self._write_skills_csv()
        self.mapping_path.write_text(
            "candidate_skill_id,mapping_status,exam,section_id,competency_id,domain_id,topic_id\n"
            "SKILL-1,matched,EXAM-1,SECTION-1,COMP-1,DOMAIN-1,TOPIC-1\n",
            encoding="utf-8",
        )
        report = {
            "generated_at": "2026-10-13T00:00:00Z",
            "structural_validation": {"passed": True, "failed": [], "blockers": []},
            "mapping_readiness": {
                "passed": mapping_passed,
                "failed": [],
                "blockers": [] if mapping_passed else [
                    {"code": "mapping_missing", "message": "Mapping is unresolved."}
                ],
            },
            "approval_readiness": {
                "passed": approval_passed,
                "failed": [],
                "blockers": [] if approval_passed else [
                    {"code": "approval_missing", "message": "Human review is pending."}
                ],
            },
            "publication_ready": mapping_passed and approval_passed,
        }
        self.report_path.write_text(json.dumps(report), encoding="utf-8")
        if register is None:
            register = self._valid_register()
        self.register_path.write_text(json.dumps(register), encoding="utf-8")

    def _write_skills_csv(
        self,
        operational_definition="Solve a linear equation.",
        topic_id="TOPIC-1",
    ):
        fields = [
            "skill_id",
            "skill_name",
            "exam",
            "section_id",
            "competency_id",
            "domain_id",
            "topic_id",
            "operational_definition",
            "observable_action",
            "in_scope",
            "out_of_scope",
            "assessment_evidence",
            "distinguishing_criteria",
        ]
        row = {
            "skill_id": "SKILL-1",
            "skill_name": "Linear equation solving",
            "exam": "EXAM-1",
            "section_id": "SECTION-1",
            "competency_id": "COMP-1",
            "domain_id": "DOMAIN-1",
            "topic_id": topic_id,
            "operational_definition": operational_definition,
            "observable_action": "Finds the unknown.",
            "in_scope": "One-variable linear equations.",
            "out_of_scope": "Quadratic equations.",
            "assessment_evidence": "Correct solution steps.",
            "distinguishing_criteria": "Isolates the variable.",
        }
        with self.spec_path.open("w", encoding="utf-8", newline="") as file:
            writer = csv.DictWriter(file, fieldnames=fields)
            writer.writeheader()
            writer.writerow(row)

    @staticmethod
    def _valid_register():
        decision = {
            "decision_id": "DEC-APPROVAL-1",
            "target_type": "taxonomy_change",
            "target_id": "TAXONOMY-1",
            "target_is_proposed": True,
            "exam": None,
            "review_status": "approved",
            "decision": "retain",
            "reason": "Human reviewers accepted the versioned taxonomy evidence.",
            "reviewer": "Reviewer Example",
            "reviewer_type": "human",
            "reviewed_at": "2026-10-13T00:00:00Z",
            "evidence_refs": ["evidence/review-001"],
            "proposed_change": None,
            "taxonomy_version": "1.0.0",
            "created_at": "2026-10-13T00:00:00Z",
            "supersedes_decision_id": None,
        }
        event = {
            "event_id": "EVT-APPROVAL-1",
            "decision_id": "DEC-APPROVAL-1",
            "target_type": "taxonomy_change",
            "target_id": "TAXONOMY-1",
            "target_is_proposed": True,
            "status_before": "in_review",
            "status_after": "approved",
            "performed_by": "Reviewer Example",
            "actor_type": "human",
            "occurred_at": "2026-10-13T00:00:00Z",
            "reason": "Human reviewers accepted the taxonomy evidence.",
            "evidence_refs": ["evidence/review-001"],
            "taxonomy_version": "1.0.0",
        }
        return {
            "schema_version": "1.0",
            "decisions": [decision],
            "audit_events": [event],
        }

    def _create_candidate(self, release_id="REL-1", version="1.0.0",
                          parent_release_id=None):
        return self.manager.create_release_candidate(
            framework_path=self.framework_path,
            taxonomy_id="smart-assessment",
            taxonomy_version=version,
            release_id=release_id,
            source_artifact_paths=[
                self.spec_path,
                self.mapping_path,
                self.report_path,
                self.register_path,
            ],
            parent_release_id=parent_release_id,
            created_at="2026-10-13T00:00:00Z",
        )

    def _publish(self, release_id="REL-1", approval_refs=None):
        return self.manager.publish_release(
            release_id=release_id,
            validation_report_path=self.report_path,
            decision_register_path=self.register_path,
            approval_decision_refs=(
                ["DEC-APPROVAL-1"] if approval_refs is None else approval_refs
            ),
            published_at="2026-10-13T00:00:00Z",
        )

    def _append_approval_version(self, version, sequence):
        register = json.loads(self.register_path.read_text(encoding="utf-8"))
        decision = copy.deepcopy(register["decisions"][0])
        event = copy.deepcopy(register["audit_events"][0])
        decision_id = f"DEC-APPROVAL-{sequence}"
        decision["decision_id"] = decision_id
        decision["taxonomy_version"] = version
        event["event_id"] = f"EVT-APPROVAL-{sequence}"
        event["decision_id"] = decision_id
        event["taxonomy_version"] = version
        register["decisions"].append(decision)
        register["audit_events"].append(event)
        self.register_path.write_text(json.dumps(register), encoding="utf-8")

    def test_candidate_captures_manifest_hashes_counts_and_source_snapshot(self):
        manifest = self._create_candidate()
        release_dir = self.release_root / "smart-assessment" / "1.0.0" / "REL-1"
        bundle = json.loads((release_dir / SNAPSHOT_NAME).read_text(encoding="utf-8"))

        self.assertEqual(manifest["status"], "release_candidate")
        self.assertEqual(manifest["counts"], {
            "exams": 1,
            "sections": 1,
            "competencies": 1,
            "domains": 1,
            "topics": 1,
            "skills": 1,
        })
        self.assertIn("framework.json", manifest["source_artifact_hashes"])
        self.assertIn("skills.csv", bundle["source_artifacts"])
        self.assertEqual(self.manager.verify_release("REL-1"), [])

    def test_schema_has_required_manifest_fields_and_publication_condition(self):
        schema_path = (
            Path(__file__).resolve().parent
            / "taxonomy"
            / "review"
            / "versioning"
            / "taxonomy_release_manifest.schema.json"
        )
        schema = json.loads(schema_path.read_text(encoding="utf-8"))

        self.assertIn("snapshot_sha256", schema["required"])
        self.assertIn("parent_release_id", schema["required"])
        self.assertEqual(schema["properties"]["taxonomy_version"]["pattern"],
                         "^[0-9]+\\.[0-9]+\\.[0-9]+$")
        self.assertTrue(schema["allOf"])

    def test_missing_manifest_field_is_reported(self):
        self._create_candidate()
        manifest_path = self.release_root / "smart-assessment" / "1.0.0" / "REL-1" / MANIFEST_NAME
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        del manifest["source_artifact_hashes"]
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

        errors = self.manager.verify_release("REL-1")

        self.assertTrue(any("missing fields" in error for error in errors))

    def test_duplicate_release_id_and_taxonomy_version_are_rejected(self):
        self._create_candidate()

        with self.assertRaisesRegex(ValueError, "release_id has already been used"):
            self._create_candidate(release_id="REL-1", version="1.1.0")
        with self.assertRaisesRegex(ValueError, "taxonomy version has already been used"):
            self._create_candidate(release_id="REL-2", version="1.0.0")

    def test_tampered_snapshot_and_source_hash_mismatch_are_detected(self):
        self._create_candidate()
        release_snapshot = self.release_root / "smart-assessment" / "1.0.0" / "REL-1" / SNAPSHOT_NAME
        snapshot = json.loads(release_snapshot.read_text(encoding="utf-8"))
        snapshot["framework"]["exams"][0]["name"] = "Tampered"
        release_snapshot.write_text(json.dumps(snapshot), encoding="utf-8")

        errors = self.manager.verify_release("REL-1")

        self.assertTrue(any("Snapshot SHA-256" in error for error in errors))

    def test_source_change_after_candidate_blocks_publication(self):
        self._create_candidate()
        self.spec_path.write_text(self.spec_path.read_text(encoding="utf-8") + "\n", encoding="utf-8")

        with self.assertRaisesRegex(ReleaseGateError, "Source artifact changed"):
            self._publish()

    def test_draft_framework_blocks_publication(self):
        self._write_fixture_files(framework_status="draft")
        self._create_candidate()

        with self.assertRaisesRegex(ReleaseGateError, "Framework status must be 'approved'"):
            self._publish()

    def test_mapping_blocker_keeps_candidate_unpublished(self):
        self._write_fixture_files(mapping_passed=False)
        self._create_candidate()

        with self.assertRaisesRegex(ReleaseGateError, "mapping_readiness did not pass"):
            self._publish()
        self.assertEqual(self.manager._find_manifest("REL-1")[1]["status"], "release_candidate")

    def test_approval_blocker_keeps_candidate_unpublished(self):
        self._write_fixture_files(approval_passed=False)
        self._create_candidate()

        with self.assertRaisesRegex(ReleaseGateError, "approval_readiness did not pass"):
            self._publish()
        self.assertEqual(self.manager._find_manifest("REL-1")[1]["status"], "release_candidate")

    def test_candidate_is_not_published_without_approval_reference(self):
        self._create_candidate()

        with self.assertRaisesRegex(ReleaseGateError, "At least one approval_decision_ref"):
            self._publish(approval_refs=[])

    def test_invalid_human_audit_evidence_blocks_publication(self):
        register = self._valid_register()
        register["audit_events"][0]["actor_type"] = "system"
        self._write_fixture_files(register=register)
        self._create_candidate()

        with self.assertRaises(ReleaseGateError):
            self._publish()

    def test_invalid_approval_reference_blocks_publication(self):
        self._create_candidate()

        with self.assertRaisesRegex(ReleaseGateError, "not an approved decision"):
            self._publish(approval_refs=["DEC-NOT-APPROVED"])

    def test_skill_decision_alone_cannot_authorize_taxonomy_publication(self):
        register = self._valid_register()
        register["decisions"][0]["target_type"] = "skill"
        register["decisions"][0]["target_id"] = "SKILL-1"
        register["audit_events"][0]["target_type"] = "skill"
        register["audit_events"][0]["target_id"] = "SKILL-1"
        self._write_fixture_files(register=register)
        self._create_candidate()

        with self.assertRaisesRegex(ReleaseGateError, "must cover the framework or taxonomy"):
            self._publish()

    def test_unsafe_source_artifact_path_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "path traversal"):
            self.manager.create_release_candidate(
                framework_path=self.framework_path,
                taxonomy_id="smart-assessment",
                taxonomy_version="1.0.0",
                release_id="REL-1",
                source_artifact_paths=["..\\outside.json"],
            )

    def test_stable_skill_id_cannot_be_reused_for_changed_semantics(self):
        self._create_candidate()
        self._publish()
        self._write_skills_csv(operational_definition="Classify a quadratic equation.")

        with self.assertRaisesRegex(ValueError, "Stable Skill ID semantics changed"):
            self._create_candidate(
                release_id="REL-2",
                version="1.1.0",
                parent_release_id="REL-1",
            )

    def test_stable_skill_id_cannot_be_reparented_to_a_different_topic(self):
        self._create_candidate()
        self._publish()
        self._write_skills_csv(topic_id="TOPIC-2")
        mapping = self.mapping_path.read_text(encoding="utf-8")
        self.mapping_path.write_text(mapping.replace("TOPIC-1", "TOPIC-2"), encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "Stable Skill ID semantics changed"):
            self._create_candidate(
                release_id="REL-2",
                version="1.1.0",
                parent_release_id="REL-1",
            )

    def test_new_taxonomy_version_requires_parent_after_first_release(self):
        self._create_candidate()
        self._publish()

        with self.assertRaisesRegex(ValueError, "parent_release_id is required"):
            self._create_candidate(release_id="REL-2", version="1.1.0")

    def test_stable_skill_id_cannot_be_reused_after_intermediate_release_removes_it(self):
        self._create_candidate()
        self._publish()

        skill_header = self.spec_path.read_text(encoding="utf-8").splitlines()[0]
        self.spec_path.write_text(skill_header + "\n", encoding="utf-8")
        self._append_approval_version("1.1.0", 2)
        self._create_candidate(
            release_id="REL-2",
            version="1.1.0",
            parent_release_id="REL-1",
        )
        self._publish(release_id="REL-2", approval_refs=["DEC-APPROVAL-2"])
        self._write_skills_csv(operational_definition="Classify a quadratic equation.")
        self._append_approval_version("1.2.0", 3)

        with self.assertRaisesRegex(ValueError, "Stable Skill ID semantics changed"):
            self._create_candidate(
                release_id="REL-3",
                version="1.2.0",
                parent_release_id="REL-2",
            )

    def test_published_release_is_immutable_and_retirement_is_append_only(self):
        self._create_candidate()
        manifest = self._publish()
        release_dir = self.release_root / "smart-assessment" / "1.0.0" / "REL-1"
        published_manifest_bytes = (release_dir / MANIFEST_NAME).read_bytes()
        snapshot_bytes = (release_dir / SNAPSHOT_NAME).read_bytes()

        with self.assertRaisesRegex(ValueError, "immutable"):
            self._publish()
        self.manager.retire_release(
            "REL-1",
            retired_by="Reviewer Example",
            reason="Replaced by a newer approved release.",
            occurred_at="2026-10-14T00:00:00Z",
        )

        self.assertEqual((release_dir / MANIFEST_NAME).read_bytes(), published_manifest_bytes)
        self.assertEqual((release_dir / SNAPSHOT_NAME).read_bytes(), snapshot_bytes)
        self.assertEqual(self.manager.list_releases()[0]["effective_status"], "retired")
        self.assertEqual(manifest["status"], "published")
        self.assertEqual(self.manager.verify_release("REL-1"), [])

    def test_report_with_false_publication_flag_blocks_candidate(self):
        self._write_fixture_files(mapping_passed=False)
        report = json.loads(self.report_path.read_text(encoding="utf-8"))
        report["publication_ready"] = False
        self.report_path.write_text(json.dumps(report), encoding="utf-8")
        self._create_candidate()

        with self.assertRaises(ReleaseGateError) as caught:
            self._publish()

        self.assertTrue(any("publication_ready is not true" in item for item in caught.exception.blockers))


if __name__ == "__main__":
    unittest.main()
