import argparse
import json
import sys
from pathlib import Path
from typing import Any

from crawler.common.duplicate_detector import DuplicateDetector
from crawler.common.question_bank_publisher import QuestionBankPublisher
from crawler.common.question_mapper import QuestionMapper
from crawler.validation.question_validator import QuestionValidator
from crawler.validation.taxonomy_validator import TaxonomyValidator


PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROCESSED_ROOT = PROJECT_ROOT / "crawler" / "data" / "processed"
TAXONOMY_ROOT = PROJECT_ROOT / "crawler" / "taxonomy"


def load_json(path: Path, expected_type: type) -> Any:
    with path.open("r", encoding="utf-8") as file:
        value = json.load(file)
    if not isinstance(value, expected_type):
        raise ValueError(f"{path} must contain a {expected_type.__name__}")
    return value


def create_mapping_templates(
    questions: list[dict[str, Any]],
    exam: str,
) -> list[dict[str, Any]]:
    return [
        {
            "question_id": question["question_id"],
            "exam": exam,
            "section_id": None,
            "competency_id": None,
            "domain_id": None,
            "topic_id": None,
            "skill_id": None,
            "cognitive_level_id": None,
            "proficiency_level_id": None,
            "mapping_source": None,
            "mapping_status": "pending",
            "reviewer": None,
            "reviewed_at": None,
        }
        for question in questions
    ]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Audit mappings and duplicates or publish approved questions."
    )
    parser.add_argument("--exam", choices=("TSA", "HSA"), required=True)
    parser.add_argument("--questions", type=Path)
    parser.add_argument("--mappings", type=Path)
    parser.add_argument("--duplicate-reviews", type=Path)
    parser.add_argument("--output-dir", type=Path)
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument("--init-mappings", action="store_true")
    actions.add_argument("--scan-duplicates", action="store_true")
    actions.add_argument("--publish", action="store_true")
    parser.add_argument("--publisher")
    args = parser.parse_args(argv)

    exam_dir = PROCESSED_ROOT / args.exam.lower()
    questions_path = args.questions or exam_dir / f"{args.exam.lower()}_question_bank.json"
    mappings_path = args.mappings or exam_dir / f"{args.exam.lower()}_question_mappings.json"
    reviews_path = args.duplicate_reviews or exam_dir / f"{args.exam.lower()}_duplicate_reviews.json"
    output_dir = args.output_dir or exam_dir

    try:
        taxonomy_report = TaxonomyValidator(
            framework_path=TAXONOMY_ROOT / "competency_framework.json",
            cognitive_levels_path=TAXONOMY_ROOT / "cognitive_levels.json",
            hsa_config_path=PROCESSED_ROOT / "hsa" / "hsa_2026_framework.json",
        ).validate()
        if not taxonomy_report["valid"]:
            raise ValueError(
                f"taxonomy validation failed: {taxonomy_report['errors']}"
            )
        questions = load_json(questions_path, list)

        if args.init_mappings:
            if mappings_path.exists():
                raise FileExistsError(
                    f"Mapping file already exists; refusing to overwrite: {mappings_path}"
                )
            templates = create_mapping_templates(questions, args.exam)
            mappings_path.parent.mkdir(parents=True, exist_ok=True)
            with mappings_path.open("x", encoding="utf-8") as file:
                json.dump(templates, file, ensure_ascii=False, indent=2)
                file.write("\n")
            print(f"[MAPPINGS CREATED] {mappings_path} ({len(templates)} pending)")
            return 0

        duplicate_detector = DuplicateDetector()
        candidates = duplicate_detector.find_candidates(questions)
        if args.scan_duplicates:
            output_dir.mkdir(parents=True, exist_ok=True)
            report_path = output_dir / f"{args.exam.lower()}_duplicate_candidates.json"
            with report_path.open("w", encoding="utf-8") as file:
                json.dump(candidates, file, ensure_ascii=False, indent=2)
                file.write("\n")
            print(f"[DUPLICATE CANDIDATES] {report_path} ({len(candidates)})")
            return 0

        mappings = load_json(mappings_path, list) if mappings_path.is_file() else []
        duplicate_reviews = load_json(reviews_path, list) if reviews_path.is_file() else []
        mapper = QuestionMapper.from_files(
            framework_path=TAXONOMY_ROOT / "competency_framework.json",
            cognitive_levels_path=TAXONOMY_ROOT / "cognitive_levels.json",
            hsa_config_path=PROCESSED_ROOT / "hsa" / "hsa_2026_framework.json",
        )
        question_validation = QuestionValidator().validate(questions)
        mapping_validation = mapper.validate(
            questions,
            mappings,
            require_all=True,
        )
        duplicate_review = duplicate_detector.validate_reviews(
            candidates,
            duplicate_reviews,
        )
        blockers = _publication_blockers(
            questions=questions,
            mappings=mappings,
            taxonomy_status=mapper.framework_status,
            question_validation=question_validation,
            mapping_validation=mapping_validation,
            duplicate_review=duplicate_review,
        )
        report = {
            "exam": args.exam,
            "taxonomy_status": mapper.framework_status,
            "taxonomy_validation": taxonomy_report,
            "question_validation": question_validation,
            "mapping_validation": mapping_validation,
            "duplicate_review": duplicate_review,
            "review_status_counts": _review_status_counts(questions),
            "duplicate_candidates": candidates,
            "publication_blockers": blockers,
            "ready_to_publish": not blockers,
        }

        if args.publish:
            if not args.publisher:
                raise ValueError("--publisher is required with --publish")
            manifest = QuestionBankPublisher(
                mapper=mapper,
                duplicate_detector=duplicate_detector,
            ).publish(
                exam=args.exam,
                questions=questions,
                mappings=mappings,
                duplicate_reviews=duplicate_reviews,
                output_dir=output_dir / "approved",
                publisher=args.publisher,
            )
            report["publication"] = manifest
            report["ready_to_publish"] = True
        print(json.dumps(report, ensure_ascii=True, indent=2))
        return 0
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"[ERROR] {error}", file=sys.stderr)
        return 1


def _review_status_counts(questions: list[dict[str, Any]]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for question in questions:
        quality = question.get("quality", {}) if isinstance(question, dict) else {}
        status = quality.get("human_review_status", "missing")
        counts[status] = counts.get(status, 0) + 1
    return counts


def _publication_blockers(
    questions: list[dict[str, Any]],
    mappings: list[dict[str, Any]],
    taxonomy_status: Any,
    question_validation: dict[str, Any],
    mapping_validation: dict[str, Any],
    duplicate_review: dict[str, Any],
) -> list[str]:
    blockers = []
    if taxonomy_status != "approved":
        blockers.append("competency_framework_not_approved")
    if not question_validation["valid"]:
        blockers.append("question_validation_failed")
    if not mapping_validation["valid"]:
        blockers.append("mapping_validation_failed")
    if any(
        not isinstance(mapping, dict)
        or mapping.get("mapping_status") != "approved"
        for mapping in mappings
    ):
        blockers.append("mapping_review_incomplete")
    if not duplicate_review["valid"]:
        blockers.append("duplicate_review_incomplete")
    for question in questions:
        quality = question.get("quality", {}) if isinstance(question, dict) else {}
        if not isinstance(quality, dict) or quality.get("human_review_status") != "approved":
            blockers.append("question_review_incomplete")
            break
        metadata = question.get("metadata", {})
        difficulty = metadata.get("difficulty") if isinstance(metadata, dict) else None
        if (
            not isinstance(difficulty, dict)
            or not {"planned", "empirical"}.issubset(difficulty)
        ):
            blockers.append("difficulty_metadata_incomplete")
            break
    return list(dict.fromkeys(blockers))


if __name__ == "__main__":
    raise SystemExit(main())