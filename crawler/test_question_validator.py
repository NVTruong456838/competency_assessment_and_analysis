import argparse
import json
import unittest
from pathlib import Path
from typing import Any

from crawler.common.question_normalizer import QuestionNormalizer
from crawler.validation.question_validator import QuestionValidator


INPUT_FILE = Path("crawler/data/processed/tsa/tsa_extracted_questions.json")


def load_questions(input_file: Path) -> list[dict[str, Any]]:
    if not input_file.is_file():
        raise FileNotFoundError(f"File not found: {input_file}")

    with input_file.open("r", encoding="utf-8") as file:
        questions = json.load(file)

    if not isinstance(questions, list):
        raise ValueError("Question data must be a JSON list")

    return questions


def print_report(report: dict[str, Any]) -> None:
    print("=" * 70)
    print("QUESTION VALIDATION REPORT")
    print("=" * 70)
    print(f"Total questions  : {report['total_questions']}")
    print(f"Valid questions  : {report['valid_questions']}")
    print(f"Invalid questions: {report['invalid_questions']}")
    print(f"Errors           : {len(report['errors'])}")
    print(f"Warnings         : {len(report['warnings'])}")

    if report["errors"]:
        print("\nERRORS:")
        for item in report["errors"]:
            print(f"  [{item['question_id']}] {item['error']}")

    if report["warnings"]:
        print("\nWARNINGS:")
        for item in report["warnings"]:
            print(f"  [{item['question_id']}] {item['warning']}")

    print("\nSTATUS:")
    if report["valid"]:
        print("  [PASS] All questions are structurally valid.")
    else:
        print("  [FAIL] Some questions need correction.")
    print("=" * 70)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Normalize extracted question records in memory and validate "
            "their structure. No output files are written."
        )
    )
    parser.add_argument(
        "--input",
        type=Path,
        default=INPUT_FILE,
        help="Path to the extracted-question JSON file.",
    )
    parser.add_argument(
        "--normalized",
        action="store_true",
        help="Input already uses the normalized schema.",
    )
    args = parser.parse_args(argv)

    try:
        questions = load_questions(args.input)
        if not args.normalized:
            questions = QuestionNormalizer().normalize_many(questions)
    except (FileNotFoundError, json.JSONDecodeError, ValueError) as error:
        print(f"[ERROR] {error}")
        return 1

    report = QuestionValidator().validate(questions)
    print_report(report)
    return 0 if report["valid"] else 1


class QuestionValidatorTests(unittest.TestCase):
    def setUp(self):
        self.validator = QuestionValidator()

    @staticmethod
    def make_question() -> dict[str, Any]:
        return {
            "question_id": "DOC-1_Q001",
            "question_number": 1,
            "stem": "Choose one answer.",
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
                "difficulty": None,
            },
            "provenance": {
                "document_id": "DOC-1",
                "source": "HUST_TSA",
                "source_url": "file:///sample.pdf",
                "page_number": 1,
            },
            "quality": {
                "extraction_status": "extracted",
                "human_review_status": "pending",
                "warnings": [],
            },
            "passage": None,
            "group_id": None,
            "group_type": None,
            "extra": {},
        }

    def test_accepts_valid_question_with_unmapped_metadata(self):
        question = self.make_question()

        report = self.validator.validate([question])

        self.assertTrue(report["valid"])
        self.assertEqual(report["valid_questions"], 1)
        self.assertEqual(report["invalid_questions"], 0)

    def test_rejects_duplicate_question_ids(self):
        first = self.make_question()
        second = self.make_question()
        second["question_number"] = 2

        report = self.validator.validate([first, second])

        self.assertFalse(report["valid"])
        self.assertEqual(report["invalid_questions"], 2)
        self.assertIn(
            "duplicate_question_id",
            {item["error"] for item in report["errors"]},
        )

    def test_rejects_answer_not_in_options(self):
        question = self.make_question()
        question["correct_answer"] = ["C"]

        report = self.validator.validate([question])

        self.assertFalse(report["valid"])
        self.assertIn(
            "answer_not_in_options:C",
            {item["error"] for item in report["errors"]},
        )

    def test_accepts_text_answer_for_true_false_without_extracted_options(self):
        question = self.make_question()
        question.update({
            "question_type": "true_false",
            "options": [],
            "correct_answer": ["Đúng"],
        })
        question["quality"].update({
            "extraction_status": "needs_review",
            "warnings": ["layout_or_table_content_needs_review"],
        })

        report = self.validator.validate([question])

        self.assertTrue(report["valid"], report["errors"])
        self.assertEqual(report["warnings"][0]["warning"], "layout_or_table_content_needs_review")

    def test_rejects_unknown_text_answer_for_true_false_without_options(self):
        question = self.make_question()
        question.update({
            "question_type": "true_false",
            "options": [],
            "correct_answer": ["Maybe"],
        })

        report = self.validator.validate([question])

        self.assertFalse(report["valid"])
        self.assertIn(
            "invalid_true_false_answer:Maybe",
            {item["error"] for item in report["errors"]},
        )

    def test_quality_warnings_do_not_make_valid_record_invalid(self):
        question = self.make_question()
        question["quality"] = {
            "extraction_status": "needs_review",
            "human_review_status": "pending",
            "warnings": ["possible_merged_options"],
        }

        report = self.validator.validate([question])

        self.assertTrue(report["valid"])
        self.assertEqual(len(report["warnings"]), 1)
        self.assertEqual(report["errors"], [])

    def test_accepts_needs_revision_with_audit_fields(self):
        question = self.make_question()
        question["quality"].update({
            "human_review_status": "needs_revision",
            "reviewer": "reviewer-1",
            "reviewed_at": "2026-09-27T12:00:00+00:00",
        })

        report = self.validator.validate([question])

        self.assertTrue(report["valid"])

    def test_rejects_approved_review_without_audit_fields(self):
        question = self.make_question()
        question["quality"]["human_review_status"] = "approved"

        report = self.validator.validate([question])

        self.assertFalse(report["valid"])
        self.assertIn(
            "reviewer_required_for_decision",
            {item["error"] for item in report["errors"]},
        )
        self.assertIn(
            "reviewed_at_required_for_decision",
            {item["error"] for item in report["errors"]},
        )

    def test_rejects_review_timestamp_without_timezone(self):
        question = self.make_question()
        question["quality"].update({
            "human_review_status": "approved",
            "reviewer": "reviewer-1",
            "reviewed_at": "2026-09-27T12:00:00",
        })

        report = self.validator.validate([question])

        self.assertIn(
            "reviewed_at_required_for_decision",
            {item["error"] for item in report["errors"]},
        )

    def test_rejects_empirical_difficulty_outside_facility_index_range(self):
        question = self.make_question()
        question["metadata"]["difficulty"] = {
            "planned": "medium",
            "empirical": 1.2,
        }

        report = self.validator.validate([question])

        self.assertFalse(report["valid"])
        self.assertIn(
            "invalid_empirical_difficulty",
            {item["error"] for item in report["errors"]},
        )

    def test_rejects_invalid_question_type(self):
        question = self.make_question()
        question["question_type"] = "M2"

        report = self.validator.validate([question])

        self.assertFalse(report["valid"])
        self.assertIn(
            "invalid_question_type",
            {item["error"] for item in report["errors"]},
        )


if __name__ == "__main__":
    raise SystemExit(main() if __name__ == "__main__" else 0)
