import unittest
import json
from pathlib import Path

from crawler.patterns.generation_contract import (
    GenerationContractError,
    GenerationRequestBuilder,
)
from crawler.patterns.mine_corpus import mine_corpus
from crawler.patterns.mining import QuestionPatternMiner


def sample_question(question_id, stem, options=None):
    return {
        "question_id": question_id,
        "stem": stem,
        "question_type": "single_choice",
        "options": options or [
            {"key": "A", "text": "12"},
            {"key": "B", "text": "14"},
            {"key": "C", "text": "16"},
            {"key": "D", "text": "18"},
        ],
        "correct_answer": ["A"],
        "provenance": {
            "document_id": "synthetic-fixture",
            "source": "unit-test",
            "source_url": "https://example.invalid/fixture",
            "page_number": 1,
        },
    }


class QuestionPatternMinerTests(unittest.TestCase):
    def test_groups_by_observable_structure_without_copying_question_text(self):
        questions = [
            {
                **sample_question("fixture-1", "Tính giá trị của biểu thức 2 + 2."),
                "explanation": "Dùng công thức phù hợp để tính.",
            },
            sample_question("fixture-2", "Tính giá trị của biểu thức 8 + 8."),
        ]

        result = QuestionPatternMiner().analyze(questions)

        self.assertEqual(result["status"], "PENDING_REVIEW")
        self.assertEqual(len(result["patterns"]), 1)
        pattern = result["patterns"][0]
        self.assertEqual(pattern["rights_status"], "UNVERIFIED")
        self.assertEqual(
            pattern["reasoning_candidates"]["verification_status"],
            "UNVERIFIED",
        )
        self.assertEqual(pattern["structure"]["stimulus_type"], "NONE")
        self.assertEqual(
            pattern["competency_evidence"]["status"],
            "UNVERIFIED",
        )
        self.assertEqual(
            pattern["reasoning_candidates"]["solution_strategies"][0]["strategy_code"],
            "use_formula",
        )
        serialized = str(pattern)
        self.assertNotIn(questions[0]["stem"], serialized)
        self.assertNotIn(questions[0]["options"][0]["text"], serialized)
        self.assertEqual(len(pattern["source_evidence"]), 2)

    def test_rejects_missing_stem(self):
        with self.assertRaisesRegex(ValueError, "stem"):
            QuestionPatternMiner().analyze([
                {"question_id": "fixture", "question_type": "single_choice"}
            ])

    def test_mines_checked_in_tsa_and_hsa_question_banks_as_unapproved_candidates(self):
        result = mine_corpus(Path(__file__).resolve().parents[1])

        self.assertEqual(result["corpus"]["records_by_exam"], {"HSA": 225, "TSA": 39})
        self.assertEqual(result["corpus"]["records_analyzed"], 264)
        self.assertTrue(result["corpus"]["all_candidates_unapproved"])
        self.assertEqual(result["status"], "PENDING_REVIEW")
        self.assertTrue(all(pattern["rights_status"] == "UNVERIFIED"
                            for pattern in result["patterns"]))
        serialized = json.dumps(result, ensure_ascii=False)
        self.assertNotIn('"stem":', serialized)
        self.assertNotIn('"option_text":', serialized)


class GenerationContractTests(unittest.TestCase):
    def setUp(self):
        self.skill = {
            "skill_id": "skill-1",
            "skill_code": "TSA.MATH.1",
            "skill_name": "Synthetic skill",
            "exam": "TSA",
            "section": "Mathematical Thinking",
            "competency": {
                "id": "competency-1",
                "code": "TSA.MATH",
                "name": "Mathematical reasoning",
            },
            "topic": {
                "id": "topic-1",
                "code": "TSA.MATH.NUMBERS",
                "name": "Numbers",
            },
            "operational_definition": "A synthetic observable definition.",
            "observable_action": "Selects the correct result.",
        }
        self.release = {
            "release_id": "test-release",
            "taxonomy_release_id": "release-db-1",
            "taxonomy_version": "1.0.0",
            "status": "PUBLISHED",
        }
        self.pattern = {
            "pattern_id": "pattern-1",
            "skill_id": "skill-1",
            "taxonomy_release_id": "release-db-1",
            "version": 1,
            "version_hash": "a" * 64,
            "review_status": "APPROVED",
            "rights_status": "VERIFIED",
            "structure": {"pattern_type": "CHOICE_WITH_OPTIONS"},
            "reasoning": {
                "verification_status": "VERIFIED",
                "actions": [{
                    "action_code": "calculate",
                    "verification_status": "VERIFIED",
                }],
                "solution_strategies": [{
                    "strategy_code": "use_formula",
                    "verification_status": "VERIFIED",
                }],
            },
            "distractor_observations": {
                "observed_distractor_count": 3,
                "semantic_interpretation": "UNVERIFIED",
            },
            "source_url": "https://example.invalid/must-not-be-copied",
            "source_question_text": "must not be copied",
        }

    def test_rejects_unreviewed_or_unverified_patterns(self):
        self.pattern["rights_status"] = "UNVERIFIED"
        with self.assertRaisesRegex(GenerationContractError, "rights"):
            GenerationRequestBuilder().build(
                skill=self.skill,
                taxonomy_release=self.release,
                cognitive_level="M1",
                difficulty="easy",
                patterns=[self.pattern],
            )

    def test_rejects_pattern_from_another_release(self):
        self.pattern["taxonomy_release_id"] = "release-db-2"

        with self.assertRaisesRegex(GenerationContractError, "taxonomy release"):
            GenerationRequestBuilder().build(
                skill=self.skill,
                taxonomy_release=self.release,
                cognitive_level="M1",
                difficulty="easy",
                patterns=[self.pattern],
            )

    def test_builds_request_without_source_material(self):
        request = GenerationRequestBuilder().build(
            skill=self.skill,
            taxonomy_release=self.release,
            cognitive_level="M1",
            difficulty="easy",
            patterns=[self.pattern],
        )

        serialized = str(request)
        self.assertNotIn(self.pattern["source_url"], serialized)
        self.assertNotIn(self.pattern["source_question_text"], serialized)
        self.assertEqual(
            request["request"]["content_policy"]["use_source_question_text"],
            False,
        )
        self.assertEqual(
            request["request"]["patterns"][0]["reasoning_actions"],
            ["calculate"],
        )
        self.assertEqual(
            request["request"]["patterns"][0]["solution_strategies"],
            ["use_formula"],
        )
        self.assertNotIn(
            "semantic_interpretation",
            request["request"]["patterns"][0]["distractor_observations"],
        )
        self.assertEqual(
            request["request"]["competency"]["code"],
            "TSA.MATH",
        )
        self.assertEqual(request["output_schema"]["type"], "object")

    def test_rejects_source_text_embedded_in_pattern_structure(self):
        self.pattern["structure"]["example"] = "source question text"

        with self.assertRaisesRegex(GenerationContractError, "safe metadata contract"):
            GenerationRequestBuilder().build(
                skill=self.skill,
                taxonomy_release=self.release,
                cognitive_level="M1",
                difficulty="easy",
                patterns=[self.pattern],
            )


if __name__ == "__main__":
    unittest.main()
