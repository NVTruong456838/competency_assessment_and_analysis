import copy
import json
from pathlib import Path
from typing import Any

from crawler.common.review_audit import is_timezone_aware_timestamp


class QuestionMapper:
    """Validate separate question-to-taxonomy mapping records."""

    MAPPING_SOURCES = {"human", "rule", "ai_suggestion"}
    MAPPING_STATUSES = {"pending", "approved", "rejected"}
    REFERENCE_TYPES = (
        ("section_id", "section"),
        ("competency_id", "competency"),
        ("domain_id", "domain"),
        ("topic_id", "topic"),
        ("skill_id", "skill"),
    )

    def __init__(
        self,
        framework: dict[str, Any],
        cognitive_levels: dict[str, Any],
        hsa_config: dict[str, Any] | None = None,
    ):
        self.entities: dict[str, dict[str, Any]] = {}
        self.taxonomy_errors: list[str] = []
        self.framework_status = framework.get("status")
        self.cognitive_level_ids = {
            level.get("id")
            for level in cognitive_levels.get("levels", [])
            if isinstance(level, dict) and isinstance(level.get("id"), str)
        }
        self.proficiency_level_ids = self._proficiency_ids(hsa_config or {})
        self._index_framework(framework)

    @classmethod
    def from_files(
        cls,
        framework_path: str | Path | None = None,
        cognitive_levels_path: str | Path | None = None,
        hsa_config_path: str | Path | None = None,
    ) -> "QuestionMapper":
        project_root = Path(__file__).resolve().parents[2]
        framework_path = Path(framework_path or project_root / "crawler/taxonomy/competency_framework.json")
        cognitive_levels_path = Path(cognitive_levels_path or project_root / "crawler/taxonomy/cognitive_levels.json")
        hsa_config_path = Path(hsa_config_path or project_root / "crawler/data/processed/hsa/hsa_2026_framework.json")
        with Path(framework_path).open("r", encoding="utf-8") as file:
            framework = json.load(file)
        with Path(cognitive_levels_path).open("r", encoding="utf-8") as file:
            cognitive_levels = json.load(file)
        with Path(hsa_config_path).open("r", encoding="utf-8") as file:
            hsa_config = json.load(file)
        return cls(framework, cognitive_levels, hsa_config)

    def validate(
        self,
        questions: list[dict[str, Any]],
        mappings: list[dict[str, Any]],
        require_all: bool = False,
    ) -> dict[str, Any]:
        if not isinstance(questions, list) or not isinstance(mappings, list):
            raise ValueError("questions and mappings must be lists")
        errors: list[dict[str, str]] = []
        warnings: list[dict[str, str]] = []
        question_ids = {
            question.get("question_id")
            for question in questions
            if isinstance(question, dict)
            and isinstance(question.get("question_id"), str)
        }
        mappings_by_question: dict[str, dict[str, Any]] = {}
        for taxonomy_error in self.taxonomy_errors:
            self._error(errors, "<taxonomy>", taxonomy_error)

        for mapping in mappings:
            if not isinstance(mapping, dict):
                errors.append({
                    "question_id": "<unknown>",
                    "error": "mapping_must_be_object",
                })
                continue

            question_id = mapping.get("question_id")
            error_count = len(errors)
            if not isinstance(question_id, str) or not question_id.strip():
                self._error(errors, "<unknown>", "missing_question_id")
                continue
            if question_id not in question_ids:
                self._error(errors, question_id, "mapping_question_not_found")
            if question_id in mappings_by_question:
                self._error(errors, question_id, "duplicate_question_mapping")
            else:
                mappings_by_question[question_id] = mapping

            exam_id = mapping.get("exam")
            if (
                not isinstance(exam_id, str)
                or exam_id not in self.entities
                or self.entities[exam_id]["type"] != "exam"
            ):
                self._error(errors, question_id, "unknown_exam_id")
            else:
                question = next((
                    item for item in questions
                    if isinstance(item, dict)
                    and item.get("question_id") == question_id
                ), None)
                question_metadata = (question or {}).get("metadata")
                question_exam = (
                    question_metadata.get("exam")
                    if isinstance(question_metadata, dict)
                    else None
                )
                if question_exam and question_exam != exam_id:
                    self._error(errors, question_id, "mapping_exam_mismatch")

            for field_name, expected_type in self.REFERENCE_TYPES:
                reference_id = mapping.get(field_name)
                if reference_id is None:
                    continue
                if not isinstance(reference_id, str):
                    self._error(errors, question_id, f"invalid_{field_name}")
                    continue
                entity = self.entities.get(reference_id)
                if entity is None or entity["type"] != expected_type:
                    self._error(errors, question_id, f"invalid_{field_name}")
                    continue
                if entity["exam"] != exam_id:
                    self._error(errors, question_id, f"{field_name}_exam_mismatch")

                parent_field = {
                    "competency_id": "section_id",
                    "domain_id": "competency_id",
                    "topic_id": "domain_id",
                    "skill_id": "topic_id",
                }.get(field_name)
                if parent_field:
                    parent_id = mapping.get(parent_field)
                    if parent_id is None:
                        self._error(errors, question_id, f"{parent_field}_required")
                    elif entity["parent_id"] != parent_id:
                        self._error(errors, question_id, f"{field_name}_parent_mismatch")

            cognitive_level_id = mapping.get("cognitive_level_id")
            if (
                cognitive_level_id is not None
                and (
                    not isinstance(cognitive_level_id, str)
                    or cognitive_level_id not in self.cognitive_level_ids
                )
            ):
                self._error(errors, question_id, "invalid_cognitive_level_id")

            proficiency_level_id = mapping.get("proficiency_level_id")
            section_id = mapping.get("section_id")
            if proficiency_level_id is not None:
                if (
                    not isinstance(proficiency_level_id, str)
                    or proficiency_level_id not in self.proficiency_level_ids
                ):
                    self._error(errors, question_id, "invalid_proficiency_level_id")
                if exam_id != "HSA" or section_id != "HSA_ENGLISH":
                    self._error(
                        errors,
                        question_id,
                        "proficiency_level_only_valid_for_hsa_english",
                    )
                if cognitive_level_id is not None:
                    self._error(
                        errors,
                        question_id,
                        "cognitive_and_proficiency_levels_are_mutually_exclusive",
                    )
            if section_id == "HSA_ENGLISH" and cognitive_level_id is not None:
                self._error(
                    errors,
                    question_id,
                    "hsa_english_uses_proficiency_not_cognitive_level",
                )

            mapping_source = mapping.get("mapping_source")
            mapping_status = mapping.get("mapping_status")
            if (
                not isinstance(mapping_source, str)
                or mapping_source not in self.MAPPING_SOURCES
            ) and not (
                mapping_status == "pending" and mapping_source is None
            ):
                self._error(errors, question_id, "invalid_mapping_source")
            if (
                not isinstance(mapping_status, str)
                or mapping_status not in self.MAPPING_STATUSES
            ):
                self._error(errors, question_id, "invalid_mapping_status")
            elif mapping_status != "pending":
                if not isinstance(mapping.get("reviewer"), str) or not mapping["reviewer"].strip():
                    self._error(errors, question_id, "reviewer_required_for_decision")
                if not is_timezone_aware_timestamp(mapping.get("reviewed_at")):
                    self._error(errors, question_id, "reviewed_at_required_for_decision")
            if mapping_status == "approved" and mapping_source != "human":
                self._error(errors, question_id, "approval_requires_human_mapping")
            if mapping_status == "approved" and not mapping.get("section_id"):
                self._error(errors, question_id, "approved_mapping_requires_section")
            if mapping_status == "approved" and not mapping.get("competency_id"):
                self._error(errors, question_id, "approved_mapping_requires_competency")

            if len(errors) == error_count and mapping_status == "pending":
                warnings.append({
                    "question_id": question_id,
                    "warning": "mapping_pending_human_review",
                })

        if require_all:
            for question_id in sorted(question_ids - set(mappings_by_question)):
                self._error(errors, question_id, "question_mapping_missing")

        return {
            "valid": not errors,
            "total_questions": len(question_ids),
            "mapped_questions": len(mappings_by_question),
            "errors": errors,
            "warnings": warnings,
        }

    def apply_approved_mappings(
        self,
        questions: list[dict[str, Any]],
        mappings: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        report = self.validate(questions, mappings, require_all=True)
        if not report["valid"]:
            raise ValueError(f"Invalid question mappings: {report['errors']}")

        mappings_by_question = {
            mapping["question_id"]: mapping
            for mapping in mappings
        }
        result = []
        for question in questions:
            mapping = mappings_by_question[question["question_id"]]
            if mapping.get("mapping_status") != "approved":
                raise ValueError(
                    f"Mapping is not approved: {question['question_id']}"
                )

            mapped_question = copy.deepcopy(question)
            metadata = mapped_question.setdefault("metadata", {})
            for mapping_field, metadata_field in (
                ("section_id", "section"),
                ("competency_id", "competency"),
                ("domain_id", "domain"),
                ("topic_id", "topic"),
                ("skill_id", "skill"),
                ("cognitive_level_id", "cognitive_level"),
                ("proficiency_level_id", "proficiency_level"),
            ):
                metadata[metadata_field] = mapping.get(mapping_field)
            mapped_question["taxonomy_mapping"] = copy.deepcopy(mapping)
            result.append(mapped_question)
        return result

    def _index_framework(self, framework: dict[str, Any]) -> None:
        def add_entity(
            entity: dict[str, Any],
            entity_type: str,
            exam_id: str,
            parent_id: str | None = None,
        ) -> None:
            entity_id = entity.get("id")
            if isinstance(entity_id, str):
                if entity_id in self.entities:
                    self.taxonomy_errors.append("duplicate_taxonomy_id")
                self.entities[entity_id] = {
                    "type": entity_type,
                    "exam": exam_id,
                    "parent_id": parent_id,
                }

        def visit_section_nodes(
            nodes: list[Any],
            exam_id: str,
            parent_id: str | None = None,
        ) -> None:
            for node in nodes:
                if not isinstance(node, dict):
                    continue
                node_id = node.get("id")
                if not isinstance(node_id, str):
                    continue
                add_entity(node, "section", exam_id, parent_id)
                branches = node.get("branches")
                if isinstance(branches, list):
                    visit_section_nodes(branches, exam_id, node_id)
                    continue
                competencies = node.get("competencies", [])
                if not isinstance(competencies, list):
                    continue
                for competency in competencies:
                    if not isinstance(competency, dict):
                        continue
                    competency_id = competency.get("id")
                    add_entity(competency, "competency", exam_id, node_id)
                    domains = competency.get("domains", [])
                    if not isinstance(domains, list):
                        continue
                    for domain in domains:
                        if not isinstance(domain, dict):
                            continue
                        domain_id = domain.get("id")
                        add_entity(domain, "domain", exam_id, competency_id)
                        topics = domain.get("topics", [])
                        if not isinstance(topics, list):
                            continue
                        for topic in topics:
                            if not isinstance(topic, dict):
                                continue
                            topic_id = topic.get("id")
                            add_entity(topic, "topic", exam_id, domain_id)
                            skills = topic.get("skills", [])
                            if not isinstance(skills, list):
                                continue
                            for skill in skills:
                                if isinstance(skill, dict):
                                    add_entity(skill, "skill", exam_id, topic_id)
        for exam in framework.get("exams", []):
            if not isinstance(exam, dict) or not isinstance(exam.get("id"), str):
                continue
            exam_id = exam["id"]
            add_entity(exam, "exam", exam_id)
            sections = exam.get("sections", [])
            if isinstance(sections, list):
                visit_section_nodes(sections, exam_id)

    @staticmethod
    def _proficiency_ids(hsa_config: dict[str, Any]) -> set[str]:
        identifiers = set()

        def visit(nodes: list[Any]) -> None:
            for node in nodes:
                if not isinstance(node, dict):
                    continue
                distribution = node.get("proficiency_distribution")
                if isinstance(distribution, dict):
                    identifiers.update(
                        key for key in distribution if isinstance(key, str)
                    )
                branches = node.get("branches")
                if isinstance(branches, list):
                    visit(branches)

        sections = hsa_config.get("sections", [])
        if isinstance(sections, list):
            visit(sections)
        return identifiers

    @staticmethod
    def _error(
        errors: list[dict[str, str]],
        question_id: str,
        code: str,
    ) -> None:
        errors.append({"question_id": question_id, "error": code})