import json
import tempfile
import unittest
from pathlib import Path
from typing import Any

from crawler.common.duplicate_detector import DuplicateDetector
from crawler.common.question_bank_publisher import QuestionBankPublisher
from crawler.common.question_mapper import QuestionMapper
from crawler.pipelines.question_bank_lifecycle import create_mapping_templates


class QuestionLifecycleTests(unittest.TestCase):
    def setUp(self):
        self.framework = {
            "status": "approved",
            "exams": [{
                "id": "TSA",
                "sections": [{
                    "id": "TSA_MATH",
                    "competencies": [{
                        "id": "TSA_QUANT",
                        "domains": [{
                            "id": "TSA_NUMBER",
                            "topics": [{"id": "TSA_REMAINDER"}],
                        }],
                    }],
                }],
            }],
        }
        self.cognitive_levels = {
            "levels": [{"id": "M1"}, {"id": "M2"}, {"id": "M3"}],
        }
        self.mapper = QuestionMapper(self.framework, self.cognitive_levels)
        self.question = self.make_question()
        self.mapping = self.make_mapping()

    @staticmethod
    def make_question(
        question_id: str = "DOC_Q001",
        stem: str = "Choose the correct remainder for the given numbers.",
    ) -> dict[str, Any]:
        return {
            "question_id": question_id,
            "question_number": 1,
            "stem": stem,
            "question_type": "single_choice",
            "options": [
                {"key": "A", "text": "One"},
                {"key": "B", "text": "Two"},
            ],
            "correct_answer": ["A"],
            "explanation": None,
            "metadata": {
                "exam": "TSA",
                "section": None,
                "competency": None,
                "domain": None,
                "topic": None,
                "skill": None,
                "cognitive_level": None,
                "difficulty": {"planned": None, "empirical": None},
            },
            "provenance": {
                "document_id": "DOC",
                "source": "HUST_TSA",
                "source_url": "file:///exam.pdf",
                "page_number": 1,
            },
            "quality": {
                "extraction_status": "extracted",
                "human_review_status": "approved",
                "warnings": [],
                "reviewer": "reviewer-1",
                "reviewed_at": "2026-09-27T12:00:00+00:00",
            },
            "source_pages": [1],
            "visual_assets": [],
        }

    @staticmethod
    def make_mapping(**overrides: Any) -> dict[str, Any]:
        mapping = {
            "question_id": "DOC_Q001",
            "exam": "TSA",
            "section_id": "TSA_MATH",
            "competency_id": "TSA_QUANT",
            "domain_id": "TSA_NUMBER",
            "topic_id": "TSA_REMAINDER",
            "skill_id": None,
            "cognitive_level_id": "M1",
            "mapping_source": "human",
            "mapping_status": "approved",
            "reviewer": "reviewer-1",
            "reviewed_at": "2026-09-27T12:00:00+00:00",
        }
        mapping.update(overrides)
        return mapping

    def test_mapping_rejects_cross_parent_taxonomy_reference(self):
        mapping = self.make_mapping(domain_id="UNKNOWN_DOMAIN")

        report = self.mapper.validate([self.question], [mapping], require_all=True)

        self.assertFalse(report["valid"])
        self.assertIn("invalid_domain_id", {item["error"] for item in report["errors"]})

    def test_only_human_mapping_can_be_approved(self):
        mapping = self.make_mapping(mapping_source="ai_suggestion")

        report = self.mapper.validate([self.question], [mapping], require_all=True)

        self.assertFalse(report["valid"])
        self.assertIn(
            "approval_requires_human_mapping",
            {item["error"] for item in report["errors"]},
        )

    def test_pending_mapping_template_does_not_claim_a_mapping_source(self):
        template = create_mapping_templates([self.question], "TSA")

        self.assertEqual(len(template), 1)
        self.assertIsNone(template[0]["mapping_source"])
        self.assertEqual(template[0]["mapping_status"], "pending")
        self.assertIsNone(template[0]["reviewed_at"])
        report = self.mapper.validate([self.question], template, require_all=True)
        self.assertTrue(report["valid"], report["errors"])

    def test_hsa_english_uses_proficiency_not_m1_m3(self):
        hsa_framework = {
            "status": "draft",
            "exams": [{
                "id": "HSA",
                "sections": [{
                    "id": "HSA_OPTIONAL",
                    "branches": [{
                        "id": "HSA_ENGLISH",
                        "competencies": [{
                            "id": "HSA_ENG_READING",
                            "domains": [],
                        }],
                    }],
                }],
            }],
        }
        hsa_config = {
            "sections": [{
                "id": "HSA_OPTIONAL",
                "branches": [{
                    "id": "HSA_ENGLISH",
                    "proficiency_distribution": {"vietnamese_level_2_a2": 0.25},
                }],
            }],
        }
        mapper = QuestionMapper(
            hsa_framework,
            self.cognitive_levels,
            hsa_config,
        )
        question = self.make_question()
        question["metadata"]["exam"] = "HSA"
        mapping = self.make_mapping(
            exam="HSA",
            section_id="HSA_ENGLISH",
            competency_id="HSA_ENG_READING",
            domain_id=None,
            topic_id=None,
            cognitive_level_id="M1",
            proficiency_level_id=None,
        )

        cognitive_report = mapper.validate([question], [mapping], require_all=True)
        self.assertIn(
            "hsa_english_uses_proficiency_not_cognitive_level",
            {item["error"] for item in cognitive_report["errors"]},
        )

        mapping["cognitive_level_id"] = None
        mapping["proficiency_level_id"] = "vietnamese_level_2_a2"
        proficiency_report = mapper.validate([question], [mapping], require_all=True)
        self.assertTrue(proficiency_report["valid"], proficiency_report["errors"])

    def test_approved_mapping_is_applied_without_mutating_source(self):
        mapped = self.mapper.apply_approved_mappings(
            [self.question],
            [self.mapping],
        )

        self.assertEqual(mapped[0]["metadata"]["competency"], "TSA_QUANT")
        self.assertEqual(mapped[0]["taxonomy_mapping"], self.mapping)
        self.assertIsNone(self.question["metadata"]["competency"])

    def test_duplicate_detector_requires_review_for_near_matches(self):
        first = self.make_question(stem=(
            "A student calculates the remainder after dividing a sum by "
            "the total digit value of a natural number."
        ))
        second = self.make_question(
            question_id="DOC_Q002",
            stem=(
                "A student calculates the remainder after dividing a sum by "
                "the total digit values of a natural number."
            ),
        )
        detector = DuplicateDetector(near_duplicate_threshold=0.9)

        candidates = detector.find_candidates([first, second])
        report = detector.validate_reviews(candidates, [])

        self.assertEqual(len(candidates), 1)
        self.assertEqual(candidates[0]["kind"], "near")
        self.assertFalse(report["valid"])
        self.assertEqual(len(report["unresolved"]), 1)

    def test_publisher_requires_review_and_writes_immutable_version(self):
        publisher = QuestionBankPublisher(self.mapper)
        pending_mapping = self.make_mapping(mapping_status="pending", reviewer=None)
        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ValueError, "mappings must be approved"):
                publisher.publish(
                    "TSA",
                    [self.question],
                    [pending_mapping],
                    [],
                    temporary_directory,
                    "publisher-1",
                )

            manifest = publisher.publish(
                "TSA",
                [self.question],
                [self.mapping],
                [],
                temporary_directory,
                "publisher-1",
            )
            bank_path = Path(temporary_directory) / manifest["question_bank_file"]
            bank = json.loads(bank_path.read_text(encoding="utf-8"))

            self.assertEqual(manifest["publication_id"], "TSA-v0001")
            self.assertEqual(manifest["question_count"], 1)
            self.assertEqual(bank[0]["metadata"]["section"], "TSA_MATH")
            self.assertTrue((Path(temporary_directory) / "tsa_question_bank_publication_v0001.json").is_file())

            with self.assertRaises(FileExistsError):
                publisher._write_new_file(bank_path, "[]\n")

    def test_publisher_blocks_unresolved_duplicate_candidate(self):
        publisher = QuestionBankPublisher(self.mapper)
        duplicate_question = self.make_question(question_id="DOC_Q002")
        duplicate_mapping = self.make_mapping(question_id="DOC_Q002")

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ValueError, "duplicate review gate failed"):
                publisher.publish(
                    "TSA",
                    [self.question, duplicate_question],
                    [self.mapping, duplicate_mapping],
                    [],
                    temporary_directory,
                    "publisher-1",
                )

    def test_publisher_blocks_draft_taxonomy(self):
        draft_framework = dict(self.framework, status="draft")
        draft_mapper = QuestionMapper(draft_framework, self.cognitive_levels)
        publisher = QuestionBankPublisher(draft_mapper)

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ValueError, "framework must be approved"):
                publisher.publish(
                    "TSA",
                    [self.question],
                    [self.mapping],
                    [],
                    temporary_directory,
                    "publisher-1",
                )

    def test_publisher_requires_planned_and_empirical_difficulty_fields(self):
        question = self.make_question()
        question["metadata"]["difficulty"] = None
        publisher = QuestionBankPublisher(self.mapper)

        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(ValueError, "planned and empirical difficulty"):
                publisher.publish(
                    "TSA",
                    [question],
                    [self.mapping],
                    [],
                    temporary_directory,
                    "publisher-1",
                )


if __name__ == "__main__":
    unittest.main(verbosity=2)