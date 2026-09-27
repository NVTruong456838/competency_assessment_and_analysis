from collections import Counter
from typing import Any

from crawler.common.review_audit import is_timezone_aware_timestamp


class QuestionValidator:
    """Validate normalized question structure before Question Bank storage.

    This validator checks data integrity only. It does not assign competency
    mappings or assess whether an answer is academically correct.
    """

    VALID_QUESTION_TYPES = {
        "single_choice",
        "multiple_choice",
        "true_false",
        "numeric_input",
        "short_answer",
        "grouped_passage",
    }

    VALID_OPTION_KEYS = {"A", "B", "C", "D"}
    VALID_EXTRACTION_STATUSES = {"extracted", "needs_review"}
    VALID_HUMAN_REVIEW_STATUSES = {
        "pending",
        "approved",
        "rejected",
        "needs_revision",
    }

    def validate(
        self,
        questions: list[dict[str, Any]],
    ) -> dict[str, Any]:
        """Validate all records and return errors separately from warnings."""

        if not isinstance(questions, list):
            raise ValueError("questions must be a list")

        errors = []
        warnings = []
        record_has_errors = [False] * len(questions)

        question_id_counts = Counter(
            question.get("question_id")
            for question in questions
            if isinstance(question, dict) and question.get("question_id")
        )

        for index, question in enumerate(questions):
            if not isinstance(question, dict):
                self._add_error(
                    errors,
                    record_has_errors,
                    index,
                    "<unknown>",
                    "record_must_be_object",
                )
                continue

            question_id = question.get("question_id") or "<unknown>"
            for error in self._validate_question(question):
                self._add_error(
                    errors,
                    record_has_errors,
                    index,
                    question_id,
                    error,
                )

            actual_id = question.get("question_id")
            if actual_id and question_id_counts[actual_id] > 1:
                self._add_error(
                    errors,
                    record_has_errors,
                    index,
                    actual_id,
                    "duplicate_question_id",
                )

            quality = question.get("quality")
            if isinstance(quality, dict):
                for warning in quality.get("warnings", []):
                    warnings.append({
                        "question_id": question_id,
                        "warning": warning,
                    })

        invalid_count = sum(record_has_errors)
        total = len(questions)

        return {
            "valid": not errors,
            "total_questions": total,
            "valid_questions": total - invalid_count,
            "invalid_questions": invalid_count,
            "errors": errors,
            "warnings": warnings,
        }

    def _validate_question(self, question: dict[str, Any]) -> list[str]:
        errors = []
        question_id = question.get("question_id")

        if not isinstance(question_id, str) or not question_id.strip():
            errors.append("missing_question_id")

        question_number = question.get("question_number")
        if (
            not isinstance(question_number, int)
            or isinstance(question_number, bool)
            or question_number < 1
        ):
            errors.append("invalid_question_number")

        stem = question.get("stem")
        if not isinstance(stem, str) or not stem.strip():
            errors.append("missing_stem")

        question_type = question.get("question_type")
        if (
            not isinstance(question_type, str)
            or question_type not in self.VALID_QUESTION_TYPES
        ):
            errors.append("invalid_question_type")

        options = question.get("options")
        if not isinstance(options, list):
            errors.append("options_must_be_list")
            options = []

        option_keys = self._validate_options(options, errors)
        self._validate_correct_answer(question, question_type, option_keys, errors)
        self._validate_provenance(question.get("provenance"), errors)
        self._validate_metadata(question.get("metadata"), errors)
        self._validate_quality(question.get("quality"), errors)
        self._validate_group(question, errors)

        return errors

    def _validate_options(
        self,
        options: list[Any],
        errors: list[str],
    ) -> list[str]:
        option_keys = []

        for option in options:
            if not isinstance(option, dict):
                errors.append("invalid_option_object")
                continue

            key = option.get("key")
            text = option.get("text")

            if not isinstance(key, str) or key not in self.VALID_OPTION_KEYS:
                errors.append(f"invalid_option_key:{key}")
            else:
                option_keys.append(key)

            if not isinstance(text, str) or not text.strip():
                errors.append(f"empty_option:{key}")

        if len(option_keys) != len(set(option_keys)):
            errors.append("duplicate_option_key")

        return option_keys

    @staticmethod
    def _validate_correct_answer(
        question: dict[str, Any],
        question_type: Any,
        option_keys: list[str],
        errors: list[str],
    ) -> None:
        answer = question.get("correct_answer")
        objective_types = {
            "single_choice",
            "multiple_choice",
            "true_false",
        }

        if answer is not None and (
            not isinstance(answer, list)
            or any(not isinstance(item, str) or not item.strip() for item in answer)
        ):
            errors.append("correct_answer_must_be_string_list_or_null")
            return

        if question_type not in objective_types:
            return

        if answer is None:
            errors.append("missing_correct_answer")
            return
        if not answer:
            errors.append("empty_correct_answer")
            return

        if question_type in {"single_choice", "true_false"} and len(answer) != 1:
            errors.append("expected_exactly_one_correct_answer")
        if question_type == "multiple_choice" and len(answer) < 2:
            errors.append("multiple_choice_requires_multiple_answers")

        for answer_key in answer:
            if question_type == "true_false" and not option_keys:
                if answer_key.casefold() not in {"đúng", "sai", "true", "false"}:
                    errors.append(f"invalid_true_false_answer:{answer_key}")
                continue
            if answer_key not in option_keys:
                errors.append(f"answer_not_in_options:{answer_key}")

    @staticmethod
    def _validate_provenance(
        provenance: Any,
        errors: list[str],
    ) -> None:
        if not isinstance(provenance, dict):
            errors.append("missing_provenance")
            return

        for field in ("document_id", "source", "source_url"):
            value = provenance.get(field)
            if not isinstance(value, str) or not value.strip():
                errors.append(f"missing_provenance_{field}")

        page_number = provenance.get("page_number")
        if page_number is None:
            errors.append("missing_provenance_page_number")
        elif (
            not isinstance(page_number, int)
            or isinstance(page_number, bool)
            or page_number < 1
        ):
            errors.append("invalid_provenance_page_number")

    @staticmethod
    def _validate_metadata(metadata: Any, errors: list[str]) -> None:
        if not isinstance(metadata, dict):
            errors.append("metadata_must_be_object")
            return

        difficulty = metadata.get("difficulty")
        if difficulty is None:
            return
        if not isinstance(difficulty, dict):
            errors.append("difficulty_must_be_object_or_null")
            return
        if not {"planned", "empirical"}.issubset(difficulty):
            errors.append("difficulty_requires_planned_and_empirical")
            return

        planned = difficulty.get("planned")
        if planned is not None and (
            not isinstance(planned, (str, int, float))
            or isinstance(planned, bool)
        ):
            errors.append("invalid_planned_difficulty")
        empirical = difficulty.get("empirical")
        if empirical is not None and (
            not isinstance(empirical, (int, float))
            or isinstance(empirical, bool)
            or not 0 <= empirical <= 1
        ):
            errors.append("invalid_empirical_difficulty")

    def _validate_quality(self, quality: Any, errors: list[str]) -> None:
        if not isinstance(quality, dict):
            errors.append("missing_quality")
            return

        extraction_status = quality.get("extraction_status")
        if (
            not isinstance(extraction_status, str)
            or extraction_status not in self.VALID_EXTRACTION_STATUSES
        ):
            errors.append("invalid_extraction_status")
        human_review_status = quality.get("human_review_status")
        if (
            not isinstance(human_review_status, str)
            or human_review_status not in self.VALID_HUMAN_REVIEW_STATUSES
        ):
            errors.append("invalid_human_review_status")
        if not isinstance(quality.get("warnings"), list):
            errors.append("quality_warnings_must_be_list")
        if human_review_status != "pending":
            if not isinstance(quality.get("reviewer"), str) or not quality["reviewer"].strip():
                errors.append("reviewer_required_for_decision")
            if not is_timezone_aware_timestamp(quality.get("reviewed_at")):
                errors.append("reviewed_at_required_for_decision")

    @staticmethod
    def _validate_group(question: dict[str, Any], errors: list[str]) -> None:
        group_id = question.get("group_id")
        if not group_id:
            return

        if not isinstance(group_id, str):
            errors.append("invalid_group_id")
        if not isinstance(question.get("group_type"), str) or not question["group_type"].strip():
            errors.append("missing_group_type")
        if not isinstance(question.get("passage"), str) or not question["passage"].strip():
            errors.append("missing_passage")

    @staticmethod
    def _add_error(
        errors: list[dict[str, Any]],
        record_has_errors: list[bool],
        index: int,
        question_id: str,
        error: str,
    ) -> None:
        errors.append({"question_id": question_id, "error": error})
        record_has_errors[index] = True
