"""Derive reviewable structural pattern candidates from normalized questions."""

from __future__ import annotations

import hashlib
import json
import re
from collections import defaultdict
from typing import Any


MINER_VERSION = "0.1.0"
_QUESTION_TYPES = {
    "single_choice",
    "multiple_choice",
    "true_false",
    "numeric_input",
    "numeric",
    "short_answer",
    "grouped_passage",
}
_TOKEN_RE = re.compile(r"\b[\w'-]+\b", re.UNICODE)
_NUMBER_RE = re.compile(r"(?<!\w)[+-]?\d+(?:[.,]\d+)?%?(?!\w)")
_ACTION_CUES = {
    "compare": ("so sánh", "compare", "difference", "khác nhau"),
    "infer": ("suy ra", "suy luận", "infer", "conclude", "kết luận"),
    "calculate": ("tính", "calculate", "compute", "giá trị của"),
    "interpret": ("diễn giải", "interpret", "biểu đồ", "bảng số liệu"),
    "select_evidence": ("bằng chứng", "evidence", "căn cứ", "dựa vào"),
}
_NEGATION_RE = re.compile(r"\b(not|except|không|ngoại trừ|sai)\b", re.IGNORECASE)
_SOLUTION_CUES = {
    "substitute": ("thay vào", "substitute", "plug in"),
    "eliminate": ("loại trừ", "eliminate", "elimination"),
    "use_formula": ("công thức", "formula", "theorem"),
    "read_visual": ("đọc biểu đồ", "read the graph", "from the table"),
}


class QuestionPatternMiner:
    """Group normalized items by observable structure, never by taxonomy."""

    def analyze(self, questions: list[dict[str, Any]]) -> dict[str, Any]:
        if not isinstance(questions, list):
            raise ValueError("questions must be a list")

        groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for index, question in enumerate(questions):
            if not isinstance(question, dict):
                raise ValueError(f"questions[{index}] must be an object")
            groups[self._pattern_key(question)].append(question)

        patterns = [
            self._candidate(key, group)
            for key, group in sorted(groups.items())
        ]
        return {
            "schema_version": "1.0",
            "miner_version": MINER_VERSION,
            "status": "PENDING_REVIEW",
            "patterns": patterns,
        }

    def _pattern_key(self, question: dict[str, Any]) -> str:
        shape = self._shape(question)
        encoded = json.dumps(shape, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(encoded.encode("utf-8")).hexdigest()

    def _candidate(
        self,
        pattern_key: str,
        questions: list[dict[str, Any]],
    ) -> dict[str, Any]:
        first = questions[0]
        stem = first["stem"]
        options = self._options(first)
        shape = self._shape(first)
        action_candidates = self._action_candidates(stem)
        return {
            "candidate_key": pattern_key,
            "version": 1,
            "pattern_type": shape["pattern_type"],
            "structure": shape,
            "reasoning_candidates": {
                "actions": action_candidates,
                "verification_status": "UNVERIFIED",
                "solution_strategies": self._solution_candidates(
                    first.get("explanation")
                ),
            },
            "distractor_observations": self._distractor_observations(
                first, options
            ),
            "competency_evidence": {
                "status": "UNVERIFIED",
                "skill_candidates": [],
            },
            "source_evidence": [
                self._source_evidence(question)
                for question in questions
            ],
            "linked_skills": [],
            "rights_status": "UNVERIFIED",
            "review_status": "PENDING_REVIEW",
        }

    @staticmethod
    def _options(question: dict[str, Any]) -> list[dict[str, Any]]:
        options = question.get("options", [])
        if not isinstance(options, list):
            raise ValueError("question options must be a list")
        return [option for option in options if isinstance(option, dict)]

    def _shape(self, question: dict[str, Any]) -> dict[str, Any]:
        stem = question.get("stem")
        if not isinstance(stem, str) or not stem.strip():
            raise ValueError("question stem must be non-empty text")

        options = self._options(question)
        question_type = question.get("question_type")
        if not isinstance(question_type, str) or not question_type.strip():
            raise ValueError("question_type must be non-empty text")
        normalized_question_type = question_type.strip().lower()
        if normalized_question_type not in _QUESTION_TYPES:
            normalized_question_type = "unclassified"

        return {
            "pattern_type": self._pattern_type(normalized_question_type, len(options)),
            "normalized_question_type": normalized_question_type,
            "option_count": len(options),
            "stem_token_count": len(_TOKEN_RE.findall(stem)),
            "stem_numeric_token_count": len(_NUMBER_RE.findall(stem)),
            "stem_sentence_count": max(1, len(re.findall(r"[.!?]+", stem))),
            "contains_table_markup": any(
                marker in stem for marker in ("|", "\t", "<table", "\\begin{array}")
            ),
            "stimulus_type": self._stimulus_type(question, stem),
        }

    @staticmethod
    def _stimulus_type(question: dict[str, Any], stem: str) -> str:
        stimulus = question.get("stimulus")
        if isinstance(stimulus, dict):
            stimulus_type = stimulus.get("type")
            if isinstance(stimulus_type, str):
                normalized = stimulus_type.strip().upper()
                if normalized in {"PASSAGE", "TABLE", "IMAGE", "CHART", "DIAGRAM"}:
                    return normalized
        if question.get("passage_id") is not None or isinstance(question.get("passage"), str):
            return "PASSAGE"
        if any(marker in stem for marker in ("|", "\t", "<table", "\\begin{array}")):
            return "TABLE"
        assets = question.get("assets")
        if isinstance(assets, list):
            for asset in assets:
                if isinstance(asset, dict) and str(asset.get("type", "")).upper() == "IMAGE":
                    return "IMAGE"
        return "NONE"

    @staticmethod
    def _solution_candidates(explanation: Any) -> list[dict[str, str]]:
        if not isinstance(explanation, str):
            return []
        lowered = explanation.casefold()
        return [
            {
                "strategy_code": strategy,
                "verification_status": "UNVERIFIED",
            }
            for strategy, cues in _SOLUTION_CUES.items()
            if any(cue.casefold() in lowered for cue in cues)
        ]

    @staticmethod
    def _pattern_type(question_type: str, option_count: int) -> str:
        normalized = question_type.strip().lower()
        if normalized in {"single_choice", "multiple_choice"} and option_count:
            return "CHOICE_WITH_OPTIONS"
        if normalized == "true_false":
            return "TRUE_FALSE"
        if normalized in {"numeric_input", "numeric"}:
            return "NUMERIC_RESPONSE"
        if normalized in {"short_answer", "grouped_passage"}:
            return "TEXT_RESPONSE"
        return "UNCLASSIFIED"

    @staticmethod
    def _action_candidates(stem: str) -> list[dict[str, Any]]:
        lowered = stem.casefold()
        return [
            {
                "action_code": action,
                "evidence_type": "lexical_cue",
                "verification_status": "UNVERIFIED",
            }
            for action, cues in _ACTION_CUES.items()
            if any(cue.casefold() in lowered for cue in cues)
        ]

    @staticmethod
    def _distractor_observations(
        question: dict[str, Any],
        options: list[dict[str, Any]],
    ) -> dict[str, Any]:
        answer = question.get("correct_answer")
        correct_keys = set(answer) if isinstance(answer, list) else set()
        distractors = [
            option
            for option in options
            if option.get("key") not in correct_keys
        ]
        texts = [
            option.get("text", "")
            for option in distractors
            if isinstance(option.get("text", ""), str)
        ]
        return {
            "observed_distractor_count": len(distractors),
            "numeric_distractor_count": sum(
                bool(_NUMBER_RE.search(text)) for text in texts
            ),
            "negation_distractor_count": sum(
                bool(_NEGATION_RE.search(text)) for text in texts
            ),
            "semantic_interpretation": "UNVERIFIED",
        }

    @staticmethod
    def _source_evidence(question: dict[str, Any]) -> dict[str, Any]:
        canonical = json.dumps(
            question,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        provenance = question.get("provenance")
        if not isinstance(provenance, dict):
            provenance = {}
        return {
            "normalized_record_sha256": digest,
            "question_id": question.get("question_id"),
            "document_id": provenance.get("document_id"),
            "source": provenance.get("source"),
            "source_url": provenance.get("source_url"),
            "page_number": provenance.get("page_number"),
            "rights_status": "UNVERIFIED",
        }
