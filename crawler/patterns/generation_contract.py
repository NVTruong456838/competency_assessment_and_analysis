"""Build a safe generation request from reviewed pattern metadata."""

from __future__ import annotations

from typing import Any


class GenerationContractError(ValueError):
    """Raised when a generation request contains unapproved inputs."""


class GenerationRequestBuilder:
    """Prepare contract data without invoking an LLM or passing source items."""

    SAFE_STRUCTURE_FIELDS = {
        "pattern_type",
        "normalized_question_type",
        "option_count",
        "stem_token_count",
        "stem_numeric_token_count",
        "stem_sentence_count",
        "contains_table_markup",
        "stimulus_type",
    }
    OUTPUT_SCHEMA = {
        "type": "object",
        "required": [
            "question_type", "stem", "options", "correct_answer", "explanation", "metadata"
        ],
        "properties": {
            "question_type": {
                "type": "string",
                "enum": ["SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE", "NUMERIC", "SHORT_ANSWER"],
            },
            "stem": {"type": "string", "minLength": 1},
            "options": {
                "type": "array",
                "items": {
                    "type": "object",
                    "required": ["key", "text"],
                    "properties": {
                        "key": {"type": "string", "minLength": 1},
                        "text": {"type": "string", "minLength": 1},
                    },
                    "additionalProperties": False,
                },
            },
            "correct_answer": {
                "type": "array",
                "minItems": 1,
                "items": {"type": ["string", "number", "boolean"]},
            },
            "explanation": {"type": "string", "minLength": 1},
            "metadata": {
                "type": "object",
                "required": ["cognitive_level", "difficulty"],
                "properties": {
                    "cognitive_level": {"type": "string", "enum": ["M1", "M2", "M3"]},
                    "difficulty": {"type": "string", "enum": ["easy", "medium", "hard"]},
                },
            },
        },
        "additionalProperties": False,
    }

    def build(
        self,
        *,
        skill: dict[str, Any],
        taxonomy_release: dict[str, Any],
        cognitive_level: str,
        difficulty: str,
        patterns: list[dict[str, Any]],
    ) -> dict[str, Any]:
        self._require_identifier(skill, "skill_id")
        self._require_text(skill, "skill_code")
        self._require_text(skill, "skill_name")
        self._require_text(skill, "section")
        self._require_entity(skill, "competency")
        self._require_entity(skill, "topic")
        self._require_text(taxonomy_release, "release_id")
        self._require_identifier(taxonomy_release, "taxonomy_release_id")
        self._require_text(taxonomy_release, "taxonomy_version")
        if taxonomy_release.get("status") != "PUBLISHED":
            raise GenerationContractError("only a published taxonomy release may be used")
        if cognitive_level not in {"M1", "M2", "M3"}:
            raise GenerationContractError("cognitive_level must be M1, M2, or M3")
        if difficulty not in {"easy", "medium", "hard"}:
            raise GenerationContractError("difficulty must be easy, medium, or hard")
        if not patterns:
            raise GenerationContractError("at least one reviewed pattern is required")

        pattern_refs = []
        for pattern in patterns:
            if pattern.get("review_status") != "APPROVED":
                raise GenerationContractError("only approved patterns may be used")
            if pattern.get("rights_status") != "VERIFIED":
                raise GenerationContractError("pattern source rights must be verified")
            if self._identifier(pattern, "skill_id") != self._identifier(skill, "skill_id"):
                raise GenerationContractError("pattern must be mapped to the requested Skill")
            if self._identifier(pattern, "taxonomy_release_id") != self._identifier(
                taxonomy_release, "taxonomy_release_id"
            ):
                raise GenerationContractError(
                    "pattern mapping must use the requested taxonomy release"
                )
            if not pattern.get("version_hash"):
                raise GenerationContractError("pattern version_hash is required")

            structure = pattern.get("structure")
            if not isinstance(structure, dict):
                raise GenerationContractError("pattern structure must be an object")
            self._reject_source_content(structure)
            safe_structure = self._safe_structure(structure)
            pattern_refs.append({
                "pattern_id": self._required_text(pattern, "pattern_id"),
                "version": pattern.get("version"),
                "version_hash": pattern["version_hash"],
                "structure": safe_structure,
                "reasoning_actions": self._verified_actions(pattern),
                "solution_strategies": self._verified_solution_strategies(pattern),
                "distractor_observations": self._verified_distractors(pattern),
            })

        return {
            "contract_version": "1.0",
            "request": {
                "exam": self._required_text(skill, "exam"),
                "section": skill.get("section"),
                "competency": skill["competency"],
                "topic": skill["topic"],
                "taxonomy_release": {
                    "release_id": taxonomy_release["release_id"],
                    "taxonomy_release_id": taxonomy_release["taxonomy_release_id"],
                    "taxonomy_version": taxonomy_release["taxonomy_version"],
                },
                "skill": {
                    "skill_id": self._identifier(skill, "skill_id"),
                    "skill_code": skill["skill_code"],
                    "skill_name": skill["skill_name"],
                    "operational_definition": self._required_text(
                        skill, "operational_definition"
                    ),
                    "observable_action": self._required_text(
                        skill, "observable_action"
                    ),
                },
                "cognitive_level": cognitive_level,
                "difficulty": difficulty,
                "patterns": pattern_refs,
                "content_policy": {
                    "use_source_question_text": False,
                    "generate_novel_content": True,
                    "initial_review_status": "DRAFT",
                    "human_review_required": True,
                },
            },
            "output_schema": self.OUTPUT_SCHEMA,
            "validation_requirements": [
                "Validate the generated object against output_schema.",
                "Require a human reviewer before publication.",
                "Reject copied source question text or answer content.",
            ],
        }

    @classmethod
    def _require_entity(cls, document: dict[str, Any], key: str) -> None:
        entity = document.get(key)
        if not isinstance(entity, dict):
            raise GenerationContractError(f"{key} must be an object")
        cls._require_identifier(entity, "id")
        for field in ("code", "name"):
            cls._require_text(entity, field)

    @classmethod
    def _require_identifier(cls, document: dict[str, Any], key: str) -> None:
        cls._identifier(document, key)

    @staticmethod
    def _identifier(document: dict[str, Any], key: str) -> str:
        value = document.get(key)
        if isinstance(value, bool) or not isinstance(value, (int, str)) or not str(value).strip():
            raise GenerationContractError(f"{key} must be a non-empty identifier")
        return str(value).strip()

    @staticmethod
    def _reject_source_content(node: Any) -> None:
        forbidden = {
            "stem", "question", "question_text", "answer", "correct_answer",
            "option", "options", "explanation", "solution_text",
        }
        if isinstance(node, dict):
            for key, value in node.items():
                if key.casefold() in forbidden:
                    raise GenerationContractError(
                        "pattern structure cannot contain source question or answer content"
                    )
                GenerationRequestBuilder._reject_source_content(value)
        elif isinstance(node, list):
            for value in node:
                GenerationRequestBuilder._reject_source_content(value)

    @classmethod
    def _safe_structure(cls, structure: dict[str, Any]) -> dict[str, Any]:
        unknown = set(structure) - cls.SAFE_STRUCTURE_FIELDS
        if unknown:
            raise GenerationContractError(
                "pattern structure contains fields outside the safe metadata contract"
            )
        allowed_values = {
            "pattern_type": {
                "CHOICE_WITH_OPTIONS", "TRUE_FALSE", "NUMERIC_RESPONSE",
                "TEXT_RESPONSE", "UNCLASSIFIED",
            },
            "normalized_question_type": {
                "single_choice", "multiple_choice", "true_false", "numeric_input",
                "numeric", "short_answer", "grouped_passage", "unclassified",
            },
            "stimulus_type": {
                "NONE", "PASSAGE", "TABLE", "IMAGE", "CHART", "DIAGRAM",
            },
        }
        for field, allowed in allowed_values.items():
            value = structure.get(field)
            if value is not None and (
                not isinstance(value, str) or value not in allowed
            ):
                raise GenerationContractError(f"pattern {field} is not in the safe metadata vocabulary")
        for field in (
            "option_count",
            "stem_token_count",
            "stem_numeric_token_count",
            "stem_sentence_count",
        ):
            value = structure.get(field)
            if value is not None and (
                isinstance(value, bool) or not isinstance(value, int) or value < 0
            ):
                raise GenerationContractError(f"pattern {field} must be a non-negative integer")
        table_markup = structure.get("contains_table_markup")
        if table_markup is not None and not isinstance(table_markup, bool):
            raise GenerationContractError("contains_table_markup must be boolean")
        return dict(structure)

    @staticmethod
    def _require_text(document: dict[str, Any], key: str) -> None:
        if not isinstance(document.get(key), str) or not document[key].strip():
            raise GenerationContractError(f"{key} must be non-empty text")

    @classmethod
    def _required_text(cls, document: dict[str, Any], key: str) -> str:
        cls._require_text(document, key)
        return document[key].strip()

    @staticmethod
    def _verified_actions(pattern: dict[str, Any]) -> list[str]:
        reasoning = pattern.get("reasoning")
        if not isinstance(reasoning, dict) or reasoning.get("verification_status") != "VERIFIED":
            return []
        actions = reasoning.get("actions", [])
        allowed = {"compare", "infer", "calculate", "interpret", "select_evidence"}
        return [
            item["action_code"]
            for item in actions
            if isinstance(item, dict)
            and item.get("verification_status") == "VERIFIED"
            and item.get("action_code") in allowed
        ]

    @staticmethod
    def _verified_solution_strategies(pattern: dict[str, Any]) -> list[str]:
        reasoning = pattern.get("reasoning")
        if not isinstance(reasoning, dict) or reasoning.get("verification_status") != "VERIFIED":
            return []
        strategies = reasoning.get("solution_strategies", [])
        allowed = {"substitute", "eliminate", "use_formula", "read_visual"}
        return [
            item["strategy_code"]
            for item in strategies
            if isinstance(item, dict)
            and item.get("verification_status") == "VERIFIED"
            and item.get("strategy_code") in allowed
        ]

    @staticmethod
    def _verified_distractors(pattern: dict[str, Any]) -> dict[str, Any]:
        distractors = pattern.get("distractor_observations")
        if not isinstance(distractors, dict):
            return {}
        allowed = {
            "observed_distractor_count",
            "numeric_distractor_count",
            "negation_distractor_count",
        }
        return {
            key: value
            for key, value in distractors.items()
            if key in allowed
            and isinstance(value, int)
            and not isinstance(value, bool)
            and value >= 0
        }
