import hashlib
import json
import os
import re
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from crawler.common.duplicate_detector import DuplicateDetector
from crawler.common.question_mapper import QuestionMapper
from crawler.validation.question_validator import QuestionValidator


class QuestionBankPublisher:
    """Publish immutable, versioned snapshots of fully approved questions."""

    def __init__(
        self,
        mapper: QuestionMapper,
        question_validator: QuestionValidator | None = None,
        duplicate_detector: DuplicateDetector | None = None,
    ):
        self.mapper = mapper
        self.question_validator = question_validator or QuestionValidator()
        self.duplicate_detector = duplicate_detector or DuplicateDetector()

    def publish(
        self,
        exam: str,
        questions: list[dict[str, Any]],
        mappings: list[dict[str, Any]],
        duplicate_reviews: list[dict[str, Any]],
        output_dir: str | Path,
        publisher: str,
    ) -> dict[str, Any]:
        if not isinstance(publisher, str) or not publisher.strip():
            raise ValueError("publisher is required")
        if not isinstance(exam, str) or not re.fullmatch(r"[A-Z0-9_-]+", exam):
            raise ValueError("exam must contain uppercase letters, digits, _ or -")
        if self.mapper.framework_status != "approved":
            raise ValueError("competency framework must be approved before publication")
        if not questions:
            raise ValueError("cannot publish an empty question bank")

        question_report = self.question_validator.validate(questions)
        if not question_report["valid"]:
            raise ValueError(
                f"question validation failed: {question_report['errors']}"
            )

        for question in questions:
            metadata = question.get("metadata", {})
            if metadata.get("exam") != exam:
                raise ValueError(f"question belongs to another exam: {question.get('question_id')}")
            quality = question.get("quality", {})
            if quality.get("human_review_status") != "approved":
                raise ValueError(
                    f"question is not human-approved: {question.get('question_id')}"
                )
            difficulty = metadata.get("difficulty")
            if (
                not isinstance(difficulty, dict)
                or not {"planned", "empirical"}.issubset(difficulty)
            ):
                raise ValueError(
                    "planned and empirical difficulty fields are required before "
                    f"publication: {question.get('question_id')}"
                )

        mapping_report = self.mapper.validate(
            questions,
            mappings,
            require_all=True,
        )
        if not mapping_report["valid"]:
            raise ValueError(f"mapping validation failed: {mapping_report['errors']}")
        if any(mapping.get("mapping_status") != "approved" for mapping in mappings):
            raise ValueError("all mappings must be approved before publication")

        duplicate_candidates = self.duplicate_detector.find_candidates(questions)
        duplicate_report = self.duplicate_detector.validate_reviews(
            duplicate_candidates,
            duplicate_reviews,
        )
        if not duplicate_report["valid"]:
            raise ValueError(
                "duplicate review gate failed: "
                f"{duplicate_report['errors'] + duplicate_report['unresolved']}"
            )

        published_questions = self.mapper.apply_approved_mappings(
            questions,
            mappings,
        )
        destination = Path(output_dir)
        destination.mkdir(parents=True, exist_ok=True)
        version = self._next_version(destination, exam)
        version_name = f"{exam.lower()}_question_bank_approved_v{version:04d}.json"
        manifest_name = f"{exam.lower()}_question_bank_publication_v{version:04d}.json"
        payload = json.dumps(
            published_questions,
            ensure_ascii=False,
            indent=2,
        ) + "\n"
        checksum = hashlib.sha256(payload.encode("utf-8")).hexdigest()
        manifest = {
            "publication_id": f"{exam}-v{version:04d}",
            "exam": exam,
            "status": "published",
            "publisher": publisher.strip(),
            "published_at": datetime.now(timezone.utc).isoformat(),
            "question_count": len(published_questions),
            "question_bank_file": version_name,
            "sha256": checksum,
        }

        self._write_new_file(destination / version_name, payload)
        try:
            self._write_new_file(
                destination / manifest_name,
                json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
            )
        except Exception:
            (destination / version_name).unlink(missing_ok=True)
            raise
        return manifest

    @staticmethod
    def _next_version(destination: Path, exam: str) -> int:
        pattern = re.compile(
            rf"^{re.escape(exam.lower())}_question_bank_approved_v(\d+)\.json$"
        )
        versions = [
            int(match.group(1))
            for path in destination.iterdir()
            if (match := pattern.match(path.name))
        ]
        return max(versions, default=0) + 1

    @staticmethod
    def _write_new_file(path: Path, content: str) -> None:
        if path.exists():
            raise FileExistsError(f"published artifact is immutable: {path}")
        temporary_path = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="w",
                encoding="utf-8",
                dir=path.parent,
                prefix=f".{path.name}.",
                suffix=".tmp",
                delete=False,
            ) as temporary_file:
                temporary_file.write(content)
                temporary_path = Path(temporary_file.name)
            os.link(temporary_path, path)
        finally:
            if temporary_path is not None and temporary_path.exists():
                temporary_path.unlink()