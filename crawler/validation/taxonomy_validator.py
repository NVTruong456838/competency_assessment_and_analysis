import json
from pathlib import Path
from typing import Any


class TaxonomyValidator:
    """Validate the internal TSA/HSA taxonomy and HSA 2026 structure config."""

    DEFAULT_FRAMEWORK_PATH = Path(
        "crawler/taxonomy/competency_framework.json"
    )

    DEFAULT_COGNITIVE_LEVELS_PATH = Path(
        "crawler/taxonomy/cognitive_levels.json"
    )

    DEFAULT_HSA_CONFIG_PATH = Path(
        "crawler/data/processed/hsa/hsa_2026_framework.json"
    )

    def __init__(
        self,
        framework_path: Path = DEFAULT_FRAMEWORK_PATH,
        cognitive_levels_path: Path = DEFAULT_COGNITIVE_LEVELS_PATH,
        hsa_config_path: Path = DEFAULT_HSA_CONFIG_PATH,
    ):
        self.framework_path = Path(framework_path)
        self.cognitive_levels_path = Path(cognitive_levels_path)
        self.hsa_config_path = Path(hsa_config_path)

    def validate(self) -> dict[str, Any]:
        """Load configured taxonomy files and return structural validation."""

        framework = self._load_json(
            self.framework_path,
        )

        cognitive_levels = self._load_json(
            self.cognitive_levels_path,
        )

        hsa_config = self._load_json(
            self.hsa_config_path,
        )

        return self.validate_data(
            framework=framework,
            cognitive_levels=cognitive_levels,
            hsa_config=hsa_config,
        )

    def validate_data(
        self,
        framework: dict[str, Any],
        cognitive_levels: dict[str, Any],
        hsa_config: dict[str, Any],
    ) -> dict[str, Any]:
        """Validate in-memory data without changing taxonomy resources."""

        errors: list[dict[str, str]] = []
        summary = {
            "exams": 0,
            "section_or_branch_nodes": 0,
            "competencies": 0,
            "domains": 0,
            "topics": 0,
            "cognitive_levels": 0,
        }

        known_ids: set[str] = set()
        hsa_nodes: dict[str, dict[str, Any]] = {}

        self._validate_framework(
            framework=framework,
            known_ids=known_ids,
            summary=summary,
            hsa_nodes=hsa_nodes,
            errors=errors,
        )

        self._validate_cognitive_levels(
            cognitive_levels=cognitive_levels,
            summary=summary,
            errors=errors,
        )

        self._validate_hsa_config(
            hsa_config=hsa_config,
            hsa_nodes=hsa_nodes,
            errors=errors,
        )

        return {
            "valid": not errors,
            "summary": summary,
            "errors": errors,
        }

    @staticmethod
    def _load_json(path: Path) -> dict[str, Any]:
        if not path.is_file():
            raise FileNotFoundError(f"Taxonomy file not found: {path}")

        with path.open("r", encoding="utf-8") as file:
            data = json.load(file)

        if not isinstance(data, dict):
            raise ValueError(f"Taxonomy JSON must be an object: {path}")

        return data

    def _validate_framework(
        self,
        framework: dict[str, Any],
        known_ids: set[str],
        summary: dict[str, int],
        hsa_nodes: dict[str, dict[str, Any]],
        errors: list[dict[str, str]],
    ) -> None:
        if framework.get("taxonomy") != "internal":
            self._add_error(
                errors,
                "framework_taxonomy_must_be_internal",
                "taxonomy",
            )

        exams = framework.get("exams")
        if not isinstance(exams, list) or not exams:
            self._add_error(
                errors,
                "framework_exams_must_be_non_empty_list",
                "exams",
            )
            return

        for exam_index, exam in enumerate(exams):
            path = f"exams[{exam_index}]"
            if not isinstance(exam, dict):
                self._add_error(
                    errors,
                    "exam_must_be_object",
                    path,
                )
                continue

            exam_id = self._validate_entity(
                entity=exam,
                path=path,
                known_ids=known_ids,
                errors=errors,
            )

            sections = exam.get("sections")
            if not isinstance(sections, list) or not sections:
                self._add_error(
                    errors,
                    "exam_sections_must_be_non_empty_list",
                    f"{path}.sections",
                )
                continue

            summary["exams"] += 1
            is_hsa = exam_id == "HSA"
            self._validate_nodes(
                nodes=sections,
                path=f"{path}.sections",
                known_ids=known_ids,
                summary=summary,
                hsa_nodes=hsa_nodes if is_hsa else None,
                errors=errors,
            )

    def _validate_nodes(
        self,
        nodes: list[Any],
        path: str,
        known_ids: set[str],
        summary: dict[str, int],
        hsa_nodes: dict[str, dict[str, Any]] | None,
        errors: list[dict[str, str]],
    ) -> None:
        for node_index, node in enumerate(nodes):
            node_path = f"{path}[{node_index}]"
            if not isinstance(node, dict):
                self._add_error(
                    errors,
                    "section_or_branch_must_be_object",
                    node_path,
                )
                continue

            node_id = self._validate_entity(
                entity=node,
                path=node_path,
                known_ids=known_ids,
                errors=errors,
            )

            summary["section_or_branch_nodes"] += 1
            if hsa_nodes is not None and node_id:
                hsa_nodes[node_id] = node

            branches = node.get("branches")
            competencies = node.get("competencies")

            if branches is not None:
                if node.get("section_type") != "selection_container":
                    self._add_error(
                        errors,
                        "branch_parent_must_be_selection_container",
                        node_path,
                    )
                if not isinstance(branches, list) or not branches:
                    self._add_error(
                        errors,
                        "branches_must_be_non_empty_list",
                        f"{node_path}.branches",
                    )
                else:
                    self._validate_nodes(
                        nodes=branches,
                        path=f"{node_path}.branches",
                        known_ids=known_ids,
                        summary=summary,
                        hsa_nodes=hsa_nodes,
                        errors=errors,
                    )

                if competencies is not None:
                    self._add_error(
                        errors,
                        "selection_container_must_not_define_competencies",
                        f"{node_path}.competencies",
                    )
                continue

            if not isinstance(competencies, list) or not competencies:
                self._add_error(
                    errors,
                    "section_or_branch_competencies_must_be_non_empty_list",
                    f"{node_path}.competencies",
                )
                continue

            self._validate_competencies(
                competencies=competencies,
                path=f"{node_path}.competencies",
                known_ids=known_ids,
                summary=summary,
                errors=errors,
            )

    def _validate_competencies(
        self,
        competencies: list[Any],
        path: str,
        known_ids: set[str],
        summary: dict[str, int],
        errors: list[dict[str, str]],
    ) -> None:
        for competency_index, competency in enumerate(competencies):
            competency_path = f"{path}[{competency_index}]"
            if not isinstance(competency, dict):
                self._add_error(
                    errors,
                    "competency_must_be_object",
                    competency_path,
                )
                continue

            self._validate_entity(
                entity=competency,
                path=competency_path,
                known_ids=known_ids,
                errors=errors,
            )

            summary["competencies"] += 1
            domains = competency.get("domains")
            if not isinstance(domains, list):
                self._add_error(
                    errors,
                    "competency_domains_must_be_list",
                    f"{competency_path}.domains",
                )
                continue

            for domain_index, domain in enumerate(domains):
                domain_path = f"{competency_path}.domains[{domain_index}]"
                if not isinstance(domain, dict):
                    self._add_error(
                        errors,
                        "domain_must_be_object",
                        domain_path,
                    )
                    continue

                self._validate_entity(
                    entity=domain,
                    path=domain_path,
                    known_ids=known_ids,
                    errors=errors,
                )

                summary["domains"] += 1
                topics = domain.get("topics")
                if not isinstance(topics, list):
                    self._add_error(
                        errors,
                        "domain_topics_must_be_list",
                        f"{domain_path}.topics",
                    )
                    continue

                for topic_index, topic in enumerate(topics):
                    topic_path = f"{domain_path}.topics[{topic_index}]"
                    if not isinstance(topic, dict):
                        self._add_error(
                            errors,
                            "topic_must_be_object",
                            topic_path,
                        )
                        continue

                    self._validate_entity(
                        entity=topic,
                        path=topic_path,
                        known_ids=known_ids,
                        errors=errors,
                    )

                    summary["topics"] += 1

    def _validate_cognitive_levels(
        self,
        cognitive_levels: dict[str, Any],
        summary: dict[str, int],
        errors: list[dict[str, str]],
    ) -> None:
        if cognitive_levels.get("taxonomy") != "internal":
            self._add_error(
                errors,
                "cognitive_taxonomy_must_be_internal",
                "cognitive_levels.taxonomy",
            )

        levels = cognitive_levels.get("levels")
        if not isinstance(levels, list) or not levels:
            self._add_error(
                errors,
                "cognitive_levels_must_be_non_empty_list",
                "cognitive_levels.levels",
            )
            return

        level_ids: set[str] = set()
        for level_index, level in enumerate(levels):
            path = f"cognitive_levels.levels[{level_index}]"
            if not isinstance(level, dict):
                self._add_error(
                    errors,
                    "cognitive_level_must_be_object",
                    path,
                )
                continue

            level_id = level.get("id")
            if not isinstance(level_id, str) or not level_id.strip():
                self._add_error(
                    errors,
                    "cognitive_level_missing_id",
                    path,
                )
            elif level_id in level_ids:
                self._add_error(
                    errors,
                    "duplicate_cognitive_level_id",
                    f"{path}.id",
                )
            else:
                level_ids.add(level_id)

            if not isinstance(level.get("name"), str) or not level["name"].strip():
                self._add_error(
                    errors,
                    "cognitive_level_missing_name",
                    f"{path}.name",
                )

            if (
                not isinstance(level.get("description"), str)
                or not level["description"].strip()
            ):
                self._add_error(
                    errors,
                    "cognitive_level_missing_description",
                    f"{path}.description",
                )

            summary["cognitive_levels"] += 1

    def _validate_hsa_config(
        self,
        hsa_config: dict[str, Any],
        hsa_nodes: dict[str, dict[str, Any]],
        errors: list[dict[str, str]],
    ) -> None:
        exam = hsa_config.get("exam")
        if not isinstance(exam, dict):
            self._add_error(
                errors,
                "hsa_config_exam_must_be_object",
                "hsa_config.exam",
            )
            return

        if exam.get("id") != "HSA":
            self._add_error(
                errors,
                "hsa_config_exam_id_must_be_hsa",
                "hsa_config.exam.id",
            )

        if exam.get("taxonomy") != "internal":
            self._add_error(
                errors,
                "hsa_config_taxonomy_must_be_internal",
                "hsa_config.exam.taxonomy",
            )

        reference = hsa_config.get("taxonomy_reference")
        if not isinstance(reference, str) or not reference.strip():
            self._add_error(
                errors,
                "hsa_config_missing_taxonomy_reference",
                "hsa_config.taxonomy_reference",
            )
        else:
            referenced_path = (
                self.hsa_config_path.parent / reference
            ).resolve()
            if referenced_path != self.framework_path.resolve():
                self._add_error(
                    errors,
                    "hsa_config_taxonomy_reference_mismatch",
                    "hsa_config.taxonomy_reference",
                )

        delivery = hsa_config.get("delivery")
        if not isinstance(delivery, dict):
            self._add_error(
                errors,
                "hsa_config_delivery_must_be_object",
                "hsa_config.delivery",
            )
        else:
            self._validate_positive_integer(
                value=delivery.get("total_score"),
                code="hsa_config_invalid_total_score",
                path="hsa_config.delivery.total_score",
                errors=errors,
            )
            self._validate_positive_integer(
                value=delivery.get("total_duration_minutes"),
                code="hsa_config_invalid_total_duration",
                path="hsa_config.delivery.total_duration_minutes",
                errors=errors,
            )

        sections = hsa_config.get("sections")
        if not isinstance(sections, list) or len(sections) != 3:
            self._add_error(
                errors,
                "hsa_config_must_define_three_sections",
                "hsa_config.sections",
            )
            return

        configured_ids = {
            section.get("id")
            for section in sections
            if isinstance(section, dict)
        }
        expected_ids = {
            "HSA_MATH_DATA",
            "HSA_LITERATURE_LANGUAGE",
            "HSA_OPTIONAL",
        }
        if configured_ids != expected_ids:
            self._add_error(
                errors,
                "hsa_config_section_ids_mismatch",
                "hsa_config.sections",
            )

        if not expected_ids.issubset(hsa_nodes):
            self._add_error(
                errors,
                "hsa_taxonomy_missing_configured_section",
                "hsa_config.sections",
            )

        math_section = self._find_section(sections, "HSA_MATH_DATA")
        language_section = self._find_section(
            sections,
            "HSA_LITERATURE_LANGUAGE",
        )
        optional_section = self._find_section(sections, "HSA_OPTIONAL")

        self._validate_question_type_total(
            section=math_section,
            expected_total=50,
            path="hsa_config.sections[HSA_MATH_DATA]",
            errors=errors,
        )

        self._validate_language_groups(
            section=language_section,
            errors=errors,
        )

        self._validate_optional_branches(
            section=optional_section,
            hsa_nodes=hsa_nodes,
            errors=errors,
        )

    @staticmethod
    def _find_section(
        sections: list[dict[str, Any]],
        section_id: str,
    ) -> dict[str, Any] | None:
        return next(
            (
                section
                for section in sections
                if section.get("id") == section_id
            ),
            None,
        )

    def _validate_question_type_total(
        self,
        section: dict[str, Any] | None,
        expected_total: int,
        path: str,
        errors: list[dict[str, str]],
    ) -> None:
        if not isinstance(section, dict):
            self._add_error(errors, "hsa_config_missing_section", path)
            return

        distribution = section.get("question_type_distribution")
        if not isinstance(distribution, list):
            self._add_error(
                errors,
                "hsa_config_question_type_distribution_must_be_list",
                f"{path}.question_type_distribution",
            )
            return

        total = sum(
            item.get("question_count", 0)
            for item in distribution
            if isinstance(item, dict)
            and isinstance(item.get("question_count"), int)
        )
        if total != expected_total:
            self._add_error(
                errors,
                "hsa_config_question_type_total_mismatch",
                f"{path}.question_type_distribution",
            )

    def _validate_language_groups(
        self,
        section: dict[str, Any] | None,
        errors: list[dict[str, str]],
    ) -> None:
        if not isinstance(section, dict):
            self._add_error(
                errors,
                "hsa_config_missing_section",
                "hsa_config.sections[HSA_LITERATURE_LANGUAGE]",
            )
            return

        rules = section.get("question_group_rules")
        if not isinstance(rules, dict):
            self._add_error(
                errors,
                "hsa_config_language_group_rules_must_be_object",
                "hsa_config.sections[HSA_LITERATURE_LANGUAGE].question_group_rules",
            )
            return

        standalone = rules.get("standalone_question_count")
        group_count = rules.get("group_count")
        questions_per_group = rules.get("questions_per_group")
        if not all(
            isinstance(value, int)
            for value in (standalone, group_count, questions_per_group)
        ):
            self._add_error(
                errors,
                "hsa_config_language_group_rule_values_invalid",
                "hsa_config.sections[HSA_LITERATURE_LANGUAGE].question_group_rules",
            )
            return

        if standalone + group_count * questions_per_group != 50:
            self._add_error(
                errors,
                "hsa_config_language_group_total_mismatch",
                "hsa_config.sections[HSA_LITERATURE_LANGUAGE].question_group_rules",
            )

    def _validate_optional_branches(
        self,
        section: dict[str, Any] | None,
        hsa_nodes: dict[str, dict[str, Any]],
        errors: list[dict[str, str]],
    ) -> None:
        if not isinstance(section, dict):
            self._add_error(
                errors,
                "hsa_config_missing_section",
                "hsa_config.sections[HSA_OPTIONAL]",
            )
            return

        if section.get("selection_mode") != "exactly_one_branch":
            self._add_error(
                errors,
                "hsa_config_optional_selection_mode_invalid",
                "hsa_config.sections[HSA_OPTIONAL].selection_mode",
            )

        branches = section.get("branches")
        if not isinstance(branches, list) or len(branches) != 2:
            self._add_error(
                errors,
                "hsa_config_optional_branches_invalid",
                "hsa_config.sections[HSA_OPTIONAL].branches",
            )
            return

        branch_ids = {
            branch.get("id")
            for branch in branches
            if isinstance(branch, dict)
        }
        if branch_ids != {"HSA_SCIENCE", "HSA_ENGLISH"}:
            self._add_error(
                errors,
                "hsa_config_optional_branch_ids_mismatch",
                "hsa_config.sections[HSA_OPTIONAL].branches",
            )

        if not branch_ids.issubset(hsa_nodes):
            self._add_error(
                errors,
                "hsa_taxonomy_missing_configured_branch",
                "hsa_config.sections[HSA_OPTIONAL].branches",
            )

        science = self._find_section(branches, "HSA_SCIENCE")
        english = self._find_section(branches, "HSA_ENGLISH")

        if not isinstance(science, dict):
            self._add_error(
                errors,
                "hsa_config_missing_science_branch",
                "hsa_config.sections[HSA_OPTIONAL].branches",
            )
        else:
            selection = science.get("subject_selection")
            subjects = (
                selection.get("available_subjects")
                if isinstance(selection, dict)
                else None
            )
            if (
                not isinstance(selection, dict)
                or selection.get("required_subject_count") != 3
                or not isinstance(subjects, list)
                or len(subjects) != 5
            ):
                self._add_error(
                    errors,
                    "hsa_config_science_subject_selection_invalid",
                    "hsa_config.sections[HSA_OPTIONAL].branches[HSA_SCIENCE]",
                )

        if not isinstance(english, dict):
            self._add_error(
                errors,
                "hsa_config_missing_english_branch",
                "hsa_config.sections[HSA_OPTIONAL].branches",
            )
        else:
            components = english.get("components")
            total = sum(
                component.get("question_count", 0)
                for component in components
                if isinstance(component, dict)
                and isinstance(component.get("question_count"), int)
            ) if isinstance(components, list) else 0

            if total != 50:
                self._add_error(
                    errors,
                    "hsa_config_english_component_total_mismatch",
                    "hsa_config.sections[HSA_OPTIONAL].branches[HSA_ENGLISH].components",
                )

    @staticmethod
    def _validate_positive_integer(
        value: Any,
        code: str,
        path: str,
        errors: list[dict[str, str]],
    ) -> None:
        if (
            not isinstance(value, int)
            or isinstance(value, bool)
            or value < 1
        ):
            TaxonomyValidator._add_error(errors, code, path)

    @staticmethod
    def _validate_entity(
        entity: dict[str, Any],
        path: str,
        known_ids: set[str],
        errors: list[dict[str, str]],
    ) -> str | None:
        entity_id = entity.get("id")
        if not isinstance(entity_id, str) or not entity_id.strip():
            TaxonomyValidator._add_error(
                errors,
                "entity_missing_id",
                f"{path}.id",
            )
            entity_id = None
        elif entity_id in known_ids:
            TaxonomyValidator._add_error(
                errors,
                "duplicate_taxonomy_id",
                f"{path}.id",
            )
        else:
            known_ids.add(entity_id)

        name = entity.get("name")
        if not isinstance(name, str) or not name.strip():
            TaxonomyValidator._add_error(
                errors,
                "entity_missing_name",
                f"{path}.name",
            )

        return entity_id

    @staticmethod
    def _add_error(
        errors: list[dict[str, str]],
        code: str,
        path: str,
    ) -> None:
        errors.append({"code": code, "path": path})
