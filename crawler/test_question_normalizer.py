import unittest

from crawler.common.question_normalizer import QuestionNormalizer


class QuestionNormalizerTests(unittest.TestCase):
    def setUp(self):
        self.normalizer = QuestionNormalizer()

    def test_normalizes_extracted_question_to_schema(self):
        raw_question = {
            "question_number": 5,
            "stem": "  A question   stem ",
            "options": {
                "D": " Four ",
                "B": " Two ",
                "A": " One ",
                "C": " Three ",
            },
            "question_type": "single_choice",
            "answer": ["B"],
            "explanation": None,
            "document_id": "HUST_TSA_LOCAL_000003",
            "source": "HUST_TSA",
            "source_url": "file:///exam.pdf",
            "page_number": 4,
            "source_pages": [4, 5],
            "visual_assets": [{"page_number": 4, "path": "visuals/page_004.png"}],
            "exam": "HSA",
            "quality": {
                "extraction_status": "extracted",
                "human_review_status": "pending",
                "warnings": [],
            },
        }

        normalized = self.normalizer.normalize(raw_question)

        self.assertEqual(
            normalized["question_id"],
            "HUST_TSA_LOCAL_000003_Q005",
        )
        self.assertEqual(normalized["stem"], "A question stem")
        self.assertEqual(
            normalized["options"],
            [
                {"key": "A", "text": "One"},
                {"key": "B", "text": "Two"},
                {"key": "C", "text": "Three"},
                {"key": "D", "text": "Four"},
            ],
        )
        self.assertEqual(normalized["correct_answer"], ["B"])
        self.assertEqual(normalized["metadata"]["exam"], "HSA")
        self.assertEqual(normalized["source_pages"], [4, 5])
        self.assertEqual(
            normalized["visual_assets"],
            [{"page_number": 4, "path": "visuals/page_004.png"}],
        )
        self.assertIsNone(normalized["metadata"]["competency"])
        self.assertEqual(
            normalized["metadata"]["difficulty"],
            {"planned": None, "empirical": None},
        )
        self.assertIsNone(normalized["metadata"]["proficiency_level"])
        self.assertEqual(
            normalized["provenance"],
            {
                "document_id": "HUST_TSA_LOCAL_000003",
                "source": "HUST_TSA",
                "source_url": "file:///exam.pdf",
                "page_number": 4,
            },
        )
        self.assertEqual(normalized["quality"]["human_review_status"], "pending")

    def test_normalizes_multiple_answers_and_group_fields(self):
        raw_question = {
            "question_number": 17,
            "stem": "Chọn hai đáp án đúng.",
            "options": {"A": "One", "B": "Two"},
            "question_type": "multiple_choice",
            "answer": ["B", "D"],
            "document_id": "DOC-1",
            "source": "HUST_TSA",
            "source_url": "https://example.com/exam.pdf",
            "page_number": 9,
            "quality": {
                "extraction_status": "needs_review",
                "human_review_status": "pending",
                "warnings": ["layout_needs_review"],
            },
            "group_id": "DOC-1_GROUP_001",
            "group_type": "passage",
            "passage": "Shared passage text.",
            "response_mode": "drag_drop",
        }

        normalized = self.normalizer.normalize(raw_question)

        self.assertEqual(normalized["correct_answer"], ["B", "D"])
        self.assertEqual(normalized["group_id"], "DOC-1_GROUP_001")
        self.assertEqual(normalized["group_type"], "passage")
        self.assertEqual(normalized["passage"], "Shared passage text.")
        self.assertEqual(normalized["extra"], {"response_mode": "drag_drop"})
        self.assertEqual(normalized["quality"]["warnings"], ["layout_needs_review"])

    def test_converts_legacy_string_answer_to_list(self):
        normalized = self.normalizer.normalize({
            "question_number": 1,
            "stem": "A fill-in question.",
            "options": {},
            "answer": " 42 ",
        })

        self.assertEqual(normalized["correct_answer"], ["42"])

    def test_preserves_null_answer_and_explanation(self):
        normalized = self.normalizer.normalize({
            "question_number": 2,
            "stem": "An unanswered question.",
            "answer": None,
            "explanation": None,
        })

        self.assertIsNone(normalized["correct_answer"])
        self.assertIsNone(normalized["explanation"])
        self.assertEqual(normalized["metadata"]["exam"], "TSA")
        self.assertEqual(
            normalized["metadata"]["difficulty"],
            {"planned": None, "empirical": None},
        )

    def test_splits_legacy_difficulty_into_planned_and_empirical(self):
        normalized = self.normalizer.normalize({
            "question_number": 2,
            "stem": "A question.",
            "difficulty": "medium",
        })

        self.assertEqual(
            normalized["metadata"]["difficulty"],
            {"planned": "medium", "empirical": None},
        )

    def test_preserves_review_audit_fields(self):
        normalized = self.normalizer.normalize({
            "question_number": 1,
            "stem": "Reviewed question.",
            "quality": {
                "extraction_status": "extracted",
                "human_review_status": "approved",
                "warnings": [],
                "reviewer": "reviewer-1",
                "reviewed_at": "2026-09-27T12:00:00+00:00",
            },
        })

        self.assertEqual(normalized["quality"]["reviewer"], "reviewer-1")
        self.assertEqual(
            normalized["quality"]["reviewed_at"],
            "2026-09-27T12:00:00+00:00",
        )

    def test_keeps_option_order_stable_for_list_input(self):
        normalized = self.normalizer.normalize({
            "question_number": 1,
            "stem": "Question.",
            "options": [
                {"key": "D", "text": "Four"},
                {"key": "A", "text": "One"},
            ],
        })

        self.assertEqual(
            normalized["options"],
            [
                {"key": "A", "text": "One"},
                {"key": "D", "text": "Four"},
            ],
        )

    def test_rejects_nonpositive_question_number(self):
        with self.assertRaises(ValueError):
            self.normalizer.normalize({
                "question_number": 0,
                "stem": "Question.",
            })


if __name__ == "__main__":
    unittest.main(verbosity=2)