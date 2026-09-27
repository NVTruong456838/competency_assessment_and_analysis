import argparse
import json
import unittest
from collections import Counter
from pathlib import Path
from typing import Any


INPUT_FILE = Path("crawler/data/processed/tsa/tsa_extracted_questions.json")
QUESTION_TYPES = {
    "single_choice",
    "multiple_choice",
    "true_false",
    "numeric_input",
    "short_answer",
    "grouped_passage",
}
EXTRACTION_STATUSES = {"extracted", "needs_review"}
HUMAN_REVIEW_STATUSES = {"pending", "approved", "rejected", "needs_revision"}
OPTION_LABELS = {"A", "B", "C", "D"}


def load_questions(input_file: Path = INPUT_FILE) -> list[dict[str, Any]]:
    """Load extracted records and check that the root JSON value is a list."""

    if not input_file.is_file():
        raise FileNotFoundError(f"File not found: {input_file}")

    with input_file.open("r", encoding="utf-8") as file:
        questions = json.load(file)

    if not isinstance(questions, list):
        raise ValueError("Extracted question data must be a JSON list")

    return questions


def validate_questions(
    questions: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Return schema errors without treating review warnings as failures."""

    issues = []
    seen_question_numbers = set()

    def add_issue(index: int, question: dict[str, Any], code: str) -> None:
        issues.append({
            "index": index,
            "document_id": question.get("document_id", ""),
            "question_number": question.get("question_number"),
            "code": code,
        })

    for index, question in enumerate(questions, start=1):
        if not isinstance(question, dict):
            issues.append({
                "index": index,
                "document_id": "",
                "question_number": None,
                "code": "record_not_object",
            })
            continue

        if not isinstance(question.get("question_number"), int) or question.get("question_number", 0) < 1:
            add_issue(index, question, "invalid_question_number")

        if not isinstance(question.get("stem"), str) or not question.get("stem", "").strip():
            add_issue(index, question, "missing_stem")

        if question.get("question_type") not in QUESTION_TYPES:
            add_issue(index, question, "unsupported_question_type")

        options = question.get("options")
        if not isinstance(options, dict):
            add_issue(index, question, "options_not_object")
            options = {}
        else:
            for label, text in options.items():
                if label not in OPTION_LABELS or not isinstance(text, str):
                    add_issue(index, question, "invalid_option")
                    break

        answer = question.get("answer")
        if answer is not None and (
            not isinstance(answer, list)
            or any(not isinstance(item, str) or not item.strip() for item in answer)
        ):
            add_issue(index, question, "answer_must_be_string_list_or_null")
        elif answer and options and question.get("question_type") in {
            "single_choice",
            "multiple_choice",
            "true_false",
        }:
            if any(item not in options for item in answer):
                add_issue(index, question, "answer_label_missing_from_options")

        for field in ("document_id", "source", "source_url"):
            if not isinstance(question.get(field), str) or not question[field].strip():
                add_issue(index, question, f"missing_{field}")

        page_number = question.get("page_number")
        if page_number is not None and (
            not isinstance(page_number, int) or page_number < 1
        ):
            add_issue(index, question, "invalid_page_number")

        quality = question.get("quality")
        if not isinstance(quality, dict):
            add_issue(index, question, "missing_quality")
            continue

        if quality.get("extraction_status") not in EXTRACTION_STATUSES:
            add_issue(index, question, "invalid_extraction_status")
        if quality.get("human_review_status") not in HUMAN_REVIEW_STATUSES:
            add_issue(index, question, "invalid_human_review_status")
        if not isinstance(quality.get("warnings"), list):
            add_issue(index, question, "warnings_not_list")

        if question.get("group_id"):
            if not question.get("group_type") or not question.get("passage"):
                add_issue(index, question, "incomplete_passage_group")

        identity = (question.get("document_id"), question.get("question_number"))
        if identity in seen_question_numbers:
            add_issue(index, question, "duplicate_document_question_number")
        seen_question_numbers.add(identity)

    return issues


def summarize_questions(questions: list[dict[str, Any]]) -> dict[str, Any]:
    """Build useful counts for extraction and human-review triage."""

    type_counts = Counter(
        question.get("question_type", "unknown")
        for question in questions
        if isinstance(question, dict)
    )
    extraction_counts = Counter(
        question.get("quality", {}).get("extraction_status", "unknown")
        for question in questions
        if isinstance(question, dict)
    )
    review_counts = Counter(
        question.get("quality", {}).get("human_review_status", "unknown")
        for question in questions
        if isinstance(question, dict)
    )
    warning_records = [
        question
        for question in questions
        if isinstance(question, dict)
        and question.get("quality", {}).get("warnings")
    ]

    return {
        "total": len(questions),
        "question_types": dict(type_counts),
        "extraction_status": dict(extraction_counts),
        "human_review_status": dict(review_counts),
        "with_warnings": len(warning_records),
        "passage_group_questions": sum(
            bool(question.get("group_id"))
            for question in questions
            if isinstance(question, dict)
        ),
        "multiple_answer_questions": sum(
            isinstance(question.get("answer"), list)
            and len(question["answer"]) > 1
            for question in questions
            if isinstance(question, dict)
        ),
    }


def print_report(
    questions: list[dict[str, Any]],
    issues: list[dict[str, Any]],
) -> None:
    summary = summarize_questions(questions)

    print("=" * 70)
    print("EXTRACTED QUESTION DATA CHECK")
    print("=" * 70)
    print(f"Total questions: {summary['total']}")

    print("\nQuestion types:")
    for question_type, count in sorted(summary["question_types"].items()):
        print(f"  {question_type:<20}: {count}")

    print("\nExtraction status:")
    for status, count in sorted(summary["extraction_status"].items()):
        print(f"  {status:<20}: {count}")

    print("\nHuman review status:")
    for status, count in sorted(summary["human_review_status"].items()):
        print(f"  {status:<20}: {count}")

    print(f"\nQuestions with warnings: {summary['with_warnings']}")
    print(f"Passage-group questions: {summary['passage_group_questions']}")
    print(f"Multiple-answer questions: {summary['multiple_answer_questions']}")

    print("\nQuestions needing review:")
    for question in questions:
        if not isinstance(question, dict):
            continue
        quality = question.get("quality", {})
        warnings = quality.get("warnings", [])
        if warnings:
            print(
                f"  {question.get('document_id')} / "
                f"Question {question.get('question_number')}: "
                f"{', '.join(warnings)}"
            )

    if issues:
        print(f"\nSchema issues: {len(issues)}")
        for issue in issues:
            print(
                f"  Record {issue['index']} "
                f"({issue['document_id']} / Q{issue['question_number']}): "
                f"{issue['code']}"
            )
    else:
        print("\nSchema issues: 0")

    print("=" * 70)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Inspect and validate extracted question records."
    )
    parser.add_argument(
        "--input",
        type=Path,
        default=INPUT_FILE,
        help="Path to tsa_extracted_questions.json.",
    )
    args = parser.parse_args()

    try:
        questions = load_questions(args.input)
    except (FileNotFoundError, json.JSONDecodeError, ValueError) as error:
        print(f"[ERROR] {error}")
        return 1

    issues = validate_questions(questions)
    print_report(questions, issues)
    return 1 if issues else 0


class ExtractedQuestionValidationTests(unittest.TestCase):
    def make_question(self) -> dict[str, Any]:
        return {
            "question_number": 1,
            "stem": "Choose one answer.",
            "options": {"A": "One", "B": "Two"},
            "question_type": "single_choice",
            "answer": ["A"],
            "explanation": None,
            "document_id": "DOC-1",
            "source_url": "https://example.com/sample.pdf",
            "source": "HUST_TSA",
            "page_number": 1,
            "quality": {
                "extraction_status": "extracted",
                "human_review_status": "pending",
                "warnings": [],
            },
        }

    def test_accepts_valid_extracted_question(self):
        self.assertEqual(validate_questions([self.make_question()]), [])

    def test_rejects_non_list_answer(self):
        question = self.make_question()
        question["answer"] = "A"

        issues = validate_questions([question])

        self.assertIn(
            "answer_must_be_string_list_or_null",
            {issue["code"] for issue in issues},
        )

    def test_requires_passage_metadata_for_grouped_question(self):
        question = self.make_question()
        question["group_id"] = "DOC-1_GROUP_001"

        issues = validate_questions([question])

        self.assertIn(
            "incomplete_passage_group",
            {issue["code"] for issue in issues},
        )

    def test_allows_table_true_false_without_structured_options(self):
        question = self.make_question()
        question["question_type"] = "true_false"
        question["options"] = {}
        question["answer"] = ["Đúng"]
        question["quality"] = {
            "extraction_status": "needs_review",
            "human_review_status": "pending",
            "warnings": ["layout_or_table_content_needs_review"],
        }

        self.assertEqual(validate_questions([question]), [])

    def test_counts_review_and_multi_answer_records(self):
        first = self.make_question()
        second = self.make_question()
        second["question_number"] = 2
        second["answer"] = ["A", "B"]
        second["question_type"] = "multiple_choice"
        second["quality"]["extraction_status"] = "needs_review"
        second["quality"]["warnings"] = ["choice_options_incomplete"]

        summary = summarize_questions([first, second])

        self.assertEqual(summary["total"], 2)
        self.assertEqual(summary["with_warnings"], 1)
        self.assertEqual(summary["multiple_answer_questions"], 1)


if __name__ == "__main__":
    raise SystemExit(main())