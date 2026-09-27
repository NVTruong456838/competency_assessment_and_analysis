from typing import Any

from crawler.common.question_schema import (
    NormalizedQuestion,
    QuestionMetadata,
    QuestionOption,
    QuestionProvenance,
    QuestionQuality,
)


class QuestionNormalizer:
    """Convert extractor records to the stable Question schema.

    This class normalizes shape and text only. It does not infer competency,
    domain, topic, skill, cognitive level, difficulty, or explanations.
    """

    DEFAULT_EXAM = "TSA"

    KNOWN_FIELDS = {
        "question_number",
        "stem",
        "options",
        "question_type",
        "answer",
        "explanation",
        "document_id",
        "source_url",
        "source",
        "page_number",
        "quality",
        "passage",
        "group_id",
        "group_type",
        "source_pages",
        "visual_assets",
        "exam",
    }

    def normalize(self, raw_question: dict[str, Any]) -> dict[str, Any]:
        """Normalize one extractor record and serialize it to JSON-ready data."""

        document_id = self._clean_text(raw_question.get("document_id"))
        question_number = self._normalize_question_number(
            raw_question.get("question_number")
        )
        options = self._normalize_options(raw_question.get("options", {}))
        raw_quality = raw_question.get("quality")

        if not isinstance(raw_quality, dict):
            raw_quality = {}

        raw_warnings = raw_quality.get("warnings", [])
        warnings = (
            [self._clean_text(warning) for warning in raw_warnings]
            if isinstance(raw_warnings, list)
            else []
        )
        warnings = [warning for warning in warnings if warning]

        raw_page_number = raw_question.get("page_number")
        page_number = self._normalize_page_number(raw_page_number)

        extra = {
            key: value
            for key, value in raw_question.items()
            if key not in self.KNOWN_FIELDS and not key.startswith("_")
        }

        normalized = NormalizedQuestion(
            question_id=self._build_question_id(
                document_id,
                question_number,
            ),
            question_number=question_number,
            stem=self._clean_text(raw_question.get("stem")),
            question_type=self._clean_text(
                raw_question.get("question_type")
            ) or "unknown",
            options=options,
            correct_answer=self._normalize_answer(raw_question.get("answer")),
            explanation=self._optional_text(raw_question.get("explanation")),
            metadata=QuestionMetadata(
                exam=self._clean_text(raw_question.get("exam")) or self.DEFAULT_EXAM,
                proficiency_level=self._optional_text(
                    raw_question.get("proficiency_level")
                ),
                difficulty=self._normalize_difficulty(
                    raw_question.get("difficulty")
                ),
            ),
            provenance=QuestionProvenance(
                document_id=document_id,
                source=self._clean_text(raw_question.get("source")),
                source_url=self._clean_text(raw_question.get("source_url")),
                page_number=page_number,
            ),
            quality=QuestionQuality(
                extraction_status=(
                    self._clean_text(
                        raw_quality.get("extraction_status")
                    ) or "needs_review"
                ),
                human_review_status=(
                    self._clean_text(
                        raw_quality.get("human_review_status")
                    ) or "pending"
                ),
                warnings=warnings,
                reviewer=self._optional_text(raw_quality.get("reviewer")),
                reviewed_at=self._optional_text(raw_quality.get("reviewed_at")),
            ),
            passage=self._optional_text(raw_question.get("passage")),
            group_id=self._optional_text(raw_question.get("group_id")),
            group_type=self._optional_text(raw_question.get("group_type")),
            source_pages=self._normalize_source_pages(
                raw_question.get("source_pages", [page_number] if page_number else [])
            ),
            visual_assets=self._normalize_visual_assets(
                raw_question.get("visual_assets")
            ),
            extra=extra,
        )

        return normalized.to_dict()

    def normalize_many(
        self,
        questions: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        """Normalize a list while preserving its order."""

        return [self.normalize(question) for question in questions]

    @staticmethod
    def _clean_text(value: Any) -> str:
        if value is None:
            return ""
        return " ".join(str(value).split()).strip()

    @classmethod
    def _optional_text(cls, value: Any) -> str | None:
        text = cls._clean_text(value)
        return text or None

    @staticmethod
    def _normalize_question_number(value: Any) -> int:
        try:
            number = int(value)
        except (TypeError, ValueError) as error:
            raise ValueError("question_number must be a positive integer") from error

        if number < 1:
            raise ValueError("question_number must be a positive integer")
        return number

    @staticmethod
    def _normalize_page_number(value: Any) -> int | None:
        if value is None:
            return None

        try:
            page_number = int(value)
        except (TypeError, ValueError):
            return None

        return page_number if page_number > 0 else None

    @classmethod
    def _normalize_source_pages(cls, values: Any) -> list[int]:
        if not isinstance(values, list):
            return []
        pages = {
            page
            for page in (cls._normalize_page_number(value) for value in values)
            if page is not None
        }
        return sorted(pages)

    @staticmethod
    def _normalize_visual_assets(values: Any) -> list[dict[str, Any]]:
        if not isinstance(values, list):
            return []
        return [
            {key: value for key, value in asset.items() if isinstance(key, str)}
            for asset in values
            if isinstance(asset, dict)
        ]

    @classmethod
    def _normalize_options(cls, raw_options: Any) -> list[QuestionOption]:
        if isinstance(raw_options, dict):
            return [
                QuestionOption(key=key, text=cls._clean_text(raw_options[key]))
                for key in "ABCD"
                if key in raw_options
            ]

        if isinstance(raw_options, list):
            options_by_key = {}
            for option in raw_options:
                if not isinstance(option, dict):
                    continue
                key = cls._clean_text(option.get("key")).upper()
                text = cls._clean_text(option.get("text"))
                if key in {"A", "B", "C", "D"} and text:
                    options_by_key[key] = text
            return [
                QuestionOption(key=key, text=options_by_key[key])
                for key in "ABCD"
                if key in options_by_key
            ]

        return []

    @classmethod
    def _normalize_answer(cls, raw_answer: Any) -> list[str] | None:
        if raw_answer is None:
            return None

        if isinstance(raw_answer, str):
            answer = cls._clean_text(raw_answer)
            return [answer] if answer else None

        if isinstance(raw_answer, (list, tuple)):
            answers = [
                cls._clean_text(answer)
                for answer in raw_answer
            ]
            answers = [answer for answer in answers if answer]
            return answers or None

        return None

    @classmethod
    def _normalize_difficulty(cls, value: Any) -> dict[str, Any]:
        if isinstance(value, dict):
            return {
                "planned": value.get("planned"),
                "empirical": value.get("empirical"),
            }
        if isinstance(value, str) and value.strip():
            return {"planned": cls._clean_text(value), "empirical": None}
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return {"planned": value, "empirical": None}
        return {"planned": None, "empirical": None}

    @staticmethod
    def _build_question_id(document_id: str, question_number: int) -> str:
        stable_document_id = document_id or "DOCUMENT"
        return f"{stable_document_id}_Q{question_number:03d}"