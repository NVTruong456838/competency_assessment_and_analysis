import re
import unicodedata
from difflib import SequenceMatcher
from typing import Any

from crawler.common.review_audit import is_timezone_aware_timestamp


class DuplicateDetector:
    """Find exact and near duplicate question candidates for human review."""

    DEFAULT_NEAR_DUPLICATE_THRESHOLD = 0.94
    MIN_TEXT_LENGTH_FOR_NEAR_MATCH = 50

    def __init__(self, near_duplicate_threshold: float = DEFAULT_NEAR_DUPLICATE_THRESHOLD):
        if not 0 < near_duplicate_threshold <= 1:
            raise ValueError("near_duplicate_threshold must be in (0, 1]")
        self.near_duplicate_threshold = near_duplicate_threshold

    def find_candidates(
        self,
        questions: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        candidates = []
        prepared = [
            (question, self._normalized_content(question))
            for question in questions
            if isinstance(question, dict)
        ]

        for left_index, (left, left_content) in enumerate(prepared):
            left_id = left.get("question_id")
            if not isinstance(left_id, str) or not left_id:
                continue
            for right, right_content in prepared[left_index + 1:]:
                right_id = right.get("question_id")
                if not isinstance(right_id, str) or not right_id:
                    continue
                if self._exam(left) != self._exam(right):
                    continue

                if left_content == right_content:
                    candidates.append(self._candidate(
                        left_id, right_id, "exact", 1.0,
                    ))
                    continue

                if min(len(left_content), len(right_content)) < self.MIN_TEXT_LENGTH_FOR_NEAR_MATCH:
                    continue
                similarity = SequenceMatcher(
                    None,
                    left_content,
                    right_content,
                    autojunk=False,
                ).ratio()
                if similarity >= self.near_duplicate_threshold:
                    candidates.append(self._candidate(
                        left_id, right_id, "near", round(similarity, 4),
                    ))
        return candidates

    @staticmethod
    def validate_reviews(
        candidates: list[dict[str, Any]],
        reviews: list[dict[str, Any]],
    ) -> dict[str, Any]:
        errors = []
        decisions = {}
        for review in reviews:
            if not isinstance(review, dict):
                errors.append({"error": "duplicate_review_must_be_object"})
                continue
            pair = DuplicateDetector._pair(
                review.get("question_id_a"),
                review.get("question_id_b"),
            )
            if pair is None:
                errors.append({"error": "duplicate_review_ids_required"})
                continue
            if pair in decisions:
                errors.append({
                    "question_ids": list(pair),
                    "error": "duplicate_review_decision_repeated",
                })
                continue
            decision = review.get("decision")
            if not isinstance(decision, str) or decision not in {"not_duplicate", "duplicate"}:
                errors.append({
                    "question_ids": list(pair),
                    "error": "invalid_duplicate_review_decision",
                })
            elif not isinstance(review.get("reviewer"), str) or not review["reviewer"].strip():
                errors.append({
                    "question_ids": list(pair),
                    "error": "duplicate_review_reviewer_required",
                })
            elif not is_timezone_aware_timestamp(review.get("reviewed_at")):
                errors.append({
                    "question_ids": list(pair),
                    "error": "duplicate_review_timestamp_required",
                })
            else:
                decisions[pair] = decision

        unresolved = []
        for candidate in candidates:
            pair = DuplicateDetector._pair(
                candidate.get("question_id_a"),
                candidate.get("question_id_b"),
            )
            if candidate.get("kind") == "exact":
                errors.append({
                    "question_ids": list(pair or ()),
                    "error": "exact_duplicate_blocks_publication",
                })
            elif pair not in decisions:
                unresolved.append({
                    "question_ids": list(pair or ()),
                    "error": "near_duplicate_requires_review",
                })
            elif decisions[pair] == "duplicate":
                errors.append({
                    "question_ids": list(pair or ()),
                    "error": "confirmed_duplicate_blocks_publication",
                })

        return {
            "valid": not errors and not unresolved,
            "errors": errors,
            "unresolved": unresolved,
        }

    @staticmethod
    def _normalized_content(question: dict[str, Any]) -> str:
        parts = [question.get("question_type", ""), question.get("stem", "")]
        options = question.get("options", [])
        if isinstance(options, list):
            parts.extend(
                option.get("text", "")
                for option in options
                if isinstance(option, dict)
            )
        elif isinstance(options, dict):
            parts.extend(options.values())
        text = " ".join(str(part) for part in parts if part is not None)
        decomposed = unicodedata.normalize("NFKD", text.casefold())
        without_marks = "".join(
            character
            for character in decomposed
            if not unicodedata.combining(character)
        )
        return " ".join(re.findall(r"[a-z0-9]+", without_marks))

    @staticmethod
    def _exam(question: dict[str, Any]) -> str | None:
        metadata = question.get("metadata")
        return metadata.get("exam") if isinstance(metadata, dict) else None

    @staticmethod
    def _pair(left_id: Any, right_id: Any) -> tuple[str, str] | None:
        if (
            not isinstance(left_id, str)
            or not left_id
            or not isinstance(right_id, str)
            or not right_id
            or left_id == right_id
        ):
            return None
        return tuple(sorted((left_id, right_id)))

    @staticmethod
    def _candidate(
        left_id: str,
        right_id: str,
        kind: str,
        similarity: float,
    ) -> dict[str, Any]:
        question_id_a, question_id_b = sorted((left_id, right_id))
        return {
            "question_id_a": question_id_a,
            "question_id_b": question_id_b,
            "kind": kind,
            "similarity": similarity,
        }