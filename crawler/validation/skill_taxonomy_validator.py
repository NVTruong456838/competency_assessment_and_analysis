"""Validate Skill taxonomy structure, mapping readiness, and approval gates."""

import argparse
import csv
import json
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from crawler.validation.taxonomy_validator import TaxonomyValidator


CRAWLER_ROOT = Path(__file__).resolve().parents[1]
TAXONOMY_ROOT = CRAWLER_ROOT / "taxonomy"
REVIEW_ROOT = TAXONOMY_ROOT / "review"
VALIDATION_ROOT = REVIEW_ROOT / "validation"


class SkillTaxonomyValidator:
    """Validate review artifacts without modifying their sources."""

    DEFAULT_FRAMEWORK_PATH = TAXONOMY_ROOT / "competency_framework.json"
    DEFAULT_COGNITIVE_LEVELS_PATH = TAXONOMY_ROOT / "cognitive_levels.json"
    DEFAULT_HSA_CONFIG_PATH = (
        CRAWLER_ROOT / "data" / "processed" / "hsa" / "hsa_2026_framework.json"
    )
    DEFAULT_REVIEW_ROOT = REVIEW_ROOT
    DEFAULT_OUTPUT_DIR = VALIDATION_ROOT

    MAPPING_FILES = {
        "TSA": "tsa_skill_candidate_mapping.csv",
        "HSA": "hsa_skill_candidate_mapping.csv",
    }
    SPECIFICATION_FILES = {
        "TSA": "tsa_skill_specification.csv",
        "HSA": "hsa_skill_specification.csv",
    }
    MAPPING_STATUSES = {
        "matched",
        "needs_new_topic",
        "ambiguous",
        "duplicate_candidate",
        "needs_review",
    }
    REVIEW_STATUSES = {"pending_review", "approved", "rejected"}
    MAPPING_REQUIRED_COLUMNS = {
        "candidate_skill_id",
        "exam",
        "section_id",
        "competency_id",
        "domain_id",
        "topic_id",
        "mapping_status",
        "reason",
        "review_status",
    }
    SPECIFICATION_REQUIRED_COLUMNS = {
        "skill_id",
        "skill_name",
        "exam",
        "section_id",
        "competency_id",
        "domain_id",
        "topic_id",
        "operational_definition",
        "observable_action",
        "in_scope",
        "out_of_scope",
        "assessment_evidence",
        "distinguishing_criteria",
        "mapping_status",
        "review_status",
    }

    def __init__(
        self,
        framework_path: Path = DEFAULT_FRAMEWORK_PATH,
        cognitive_levels_path: Path = DEFAULT_COGNITIVE_LEVELS_PATH,
        hsa_config_path: Path = DEFAULT_HSA_CONFIG_PATH,
        review_root: Path = DEFAULT_REVIEW_ROOT,
        output_dir: Path = DEFAULT_OUTPUT_DIR,
    ):
        self.framework_path = Path(framework_path)
        self.cognitive_levels_path = Path(cognitive_levels_path)
        self.hsa_config_path = Path(hsa_config_path)
        self.review_root = Path(review_root)
        self.output_dir = Path(output_dir)

    def validate(self) -> dict[str, Any]:
        """Load review artifacts and return all three independent gate results."""

        load_failures: list[dict[str, str]] = []
        framework = self._load_json(self.framework_path, load_failures, "framework")
        cognitive_levels = self._load_json(
            self.cognitive_levels_path,
            load_failures,
            "cognitive_levels",
        )
        hsa_config = self._load_json(
            self.hsa_config_path,
            load_failures,
            "hsa_config",
        )

        mapping_rows: dict[str, list[dict[str, str]]] = {}
        specification_rows: dict[str, list[dict[str, str]]] = {}
        for exam in ("TSA", "HSA"):
            mapping_rows[exam] = self._load_csv(
                self.review_root / self.MAPPING_FILES[exam],
                exam,
                "mapping",
                load_failures,
            )
            specification_rows[exam] = self._load_csv(
                self.review_root / self.SPECIFICATION_FILES[exam],
                exam,
                "specification",
                load_failures,
            )

        report = self.validate_data(
            framework=framework,
            cognitive_levels=cognitive_levels,
            hsa_config=hsa_config,
            mapping_rows=mapping_rows,
            specification_rows=specification_rows,
        )
        report["structural_validation"]["failed"].extend(load_failures)
        report["structural_validation"]["passed"] = not (
            report["structural_validation"]["failed"]
        )
        if load_failures:
            report["mapping_readiness"]["blockers"].append(
                self._issue(
                    "mapping_inputs_unavailable",
                    "Mapping readiness cannot be established because one or more input files failed to load.",
                    "review_artifacts",
                )
            )
            report["mapping_readiness"]["passed"] = False
            report["approval_readiness"]["blockers"].append(
                self._issue(
                    "approval_inputs_unavailable",
                    "Approval readiness cannot be established because one or more input files failed to load.",
                    "review_artifacts",
                )
            )
            report["approval_readiness"]["passed"] = False
        return report

    def validate_data(
        self,
        framework: dict[str, Any],
        cognitive_levels: dict[str, Any],
        hsa_config: dict[str, Any],
        mapping_rows: dict[str, list[dict[str, str]]],
        specification_rows: dict[str, list[dict[str, str]]],
        approval_evidence: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Validate in-memory inputs; this method never mutates them."""

        structural = self._empty_gate()
        mapping = self._empty_gate()
        approval = self._empty_gate()
        counts: dict[str, Any] = {
            "candidate_count": 0,
            "by_exam": {},
            "by_mapping_status": {},
            "by_exam_section": {},
            "mapping_valid_count": 0,
            "mapping_blocker_count": 0,
        }

        taxonomy_report = self._validate_base_taxonomy(
            framework,
            cognitive_levels,
            hsa_config,
        )
        for error in taxonomy_report["errors"]:
            structural["failed"].append(
                self._issue(
                    error["code"],
                    error["path"],
                    error["path"],
                )
            )
        if not taxonomy_report["valid"]:
            mapping["blockers"].append(
                self._issue(
                    "canonical_taxonomy_invalid",
                    "The canonical taxonomy or HSA structure failed the existing taxonomy validator.",
                    "framework",
                )
            )

        hierarchy, hierarchy_errors = self._build_hierarchy(framework)
        structural["failed"].extend(hierarchy_errors)

        framework_status = framework.get("status")
        if framework_status != "approved":
            structural["warnings"].append(
                self._issue(
                    "framework_not_approved",
                    f"Framework status is {framework_status!r}; structural validity does not imply publication approval.",
                    "framework.status",
                )
            )
            approval["blockers"].append(
                self._issue(
                    "framework_approval_missing",
                    "The framework is not in approved status.",
                    "framework.status",
                )
            )

        rows_by_exam: dict[str, list[dict[str, str]]] = {}
        specs_by_exam: dict[str, list[dict[str, str]]] = {}
        for exam in ("TSA", "HSA"):
            rows_by_exam[exam] = mapping_rows.get(exam, [])
            specs_by_exam[exam] = specification_rows.get(exam, [])
            self._validate_headers(
                rows_by_exam[exam],
                self.MAPPING_REQUIRED_COLUMNS
                | ({"subject_choice"} if exam == "HSA" else set()),
                f"{exam}_mapping",
                structural,
            )
            specification_columns = set(self.SPECIFICATION_REQUIRED_COLUMNS)
            if exam == "HSA":
                specification_columns.add("subject_choice")
            self._validate_headers(
                specs_by_exam[exam],
                specification_columns,
                f"{exam}_specification",
                structural,
            )

        all_candidate_ids: set[str] = set()
        mapping_by_id: dict[str, dict[str, str]] = {}
        specification_by_id: dict[str, dict[str, str]] = {}
        expected_counts = {"TSA": 48, "HSA": 77}
        section_counts: dict[str, dict[str, Any]] = defaultdict(
            lambda: {
                "candidate_count": 0,
                "mapping_valid_count": 0,
                "mapping_blocker_count": 0,
                "by_mapping_status": {},
            }
        )
        status_counts: Counter[str] = Counter()
        exam_counts: Counter[str] = Counter()

        for exam in ("TSA", "HSA"):
            mapping_by_id[exam] = self._index_rows(
                rows_by_exam[exam],
                "candidate_skill_id",
                f"{exam}_mapping",
                structural,
            )
            specification_by_id[exam] = self._index_rows(
                specs_by_exam[exam],
                "skill_id",
                f"{exam}_specification",
                structural,
            )
            if len(mapping_by_id[exam]) != expected_counts[exam]:
                structural["failed"].append(
                    self._issue(
                        "candidate_count_mismatch",
                        f"Expected {expected_counts[exam]} {exam} mappings; found {len(mapping_by_id[exam])}.",
                        f"{exam}_mapping",
                    )
                )
            if len(specification_by_id[exam]) != expected_counts[exam]:
                structural["failed"].append(
                    self._issue(
                        "specification_count_mismatch",
                        f"Expected {expected_counts[exam]} {exam} specifications; found {len(specification_by_id[exam])}.",
                        f"{exam}_specification",
                    )
                )

            missing_specifications = set(mapping_by_id[exam]) - set(
                specification_by_id[exam]
            )
            missing_mappings = set(specification_by_id[exam]) - set(
                mapping_by_id[exam]
            )
            for skill_id in sorted(missing_specifications):
                structural["failed"].append(
                    self._issue(
                        "candidate_missing_specification",
                        f"Candidate {skill_id} has no matching specification.",
                        skill_id,
                    )
                )
            for skill_id in sorted(missing_mappings):
                structural["failed"].append(
                    self._issue(
                        "specification_missing_candidate",
                        f"Specification {skill_id} has no matching candidate mapping.",
                        skill_id,
                    )
                )

            for skill_id in mapping_by_id[exam]:
                if skill_id in all_candidate_ids:
                    structural["failed"].append(
                        self._issue(
                            "duplicate_candidate_id_across_exams",
                            f"Candidate ID {skill_id} appears in more than one exam.",
                            skill_id,
                        )
                    )
                all_candidate_ids.add(skill_id)
                row = mapping_by_id[exam][skill_id]
                spec = specification_by_id[exam].get(skill_id)
                section_id = row.get("section_id", "")
                section_key = f"{exam}/{section_id or 'UNKNOWN'}"
                section = section_counts[section_key]
                section["candidate_count"] += 1
                exam_counts[exam] += 1

                self._validate_mapping_row(
                    row,
                    exam,
                    skill_id,
                    hierarchy,
                    structural,
                    mapping,
                    section,
                )
                status = row.get("mapping_status", "")
                status_counts[status] += 1
                section["by_mapping_status"][status] = (
                    section["by_mapping_status"].get(status, 0) + 1
                )
                if spec is not None:
                    self._validate_specification_row(
                        row,
                        spec,
                        exam,
                        skill_id,
                        structural,
                        mapping,
                        section,
                    )

                if status == "matched" and self._has_valid_topic_path(
                    row,
                    exam,
                    hierarchy,
                ):
                    section["mapping_valid_count"] += 1
                    counts["mapping_valid_count"] += 1
                else:
                    section["mapping_blocker_count"] += 1
                    counts["mapping_blocker_count"] += 1
                if row.get("review_status") == "pending_review":
                    approval["blockers"].append(
                        self._issue(
                            "candidate_pending_human_review",
                            f"Candidate {skill_id} is still pending human review.",
                            skill_id,
                        )
                    )

        self._add_hsa_branch_checks(
            mapping_by_id.get("HSA", {}),
            hierarchy,
            structural,
            mapping,
        )

        if structural["failed"]:
            mapping["blockers"].append(
                self._issue(
                    "structural_integrity_blocks_mapping",
                    "Mapping readiness requires structurally valid source and specification artifacts.",
                    "structural_validation",
                )
            )
            approval["blockers"].append(
                self._issue(
                    "structural_validation_not_passed",
                    "Approval is blocked until structural validation passes.",
                    "structural_validation",
                )
            )

        for error in structural["failed"]:
            structural["blockers"].append(
                self._issue(
                    "structural_failure_blocks_release",
                    error["message"],
                    error["location"],
                )
            )

        mapping["passed"] = not mapping["failed"] and not mapping["blockers"]
        structural["passed"] = not structural["failed"]

        counts["candidate_count"] = len(all_candidate_ids)
        counts["by_exam"] = {
            exam: exam_counts.get(exam, 0) for exam in ("TSA", "HSA")
        }
        counts["by_mapping_status"] = {
            status: status_counts.get(status, 0)
            for status in sorted(self.MAPPING_STATUSES)
        }
        counts["by_exam_section"] = dict(section_counts)
        mapping["counts"] = {
            "candidate_count": counts["candidate_count"],
            "mapping_valid_count": counts["mapping_valid_count"],
            "mapping_blocker_count": counts["mapping_blocker_count"],
            "by_exam_section": counts["by_exam_section"],
        }

        self._validate_approval_evidence(
            framework,
            mapping,
            approval_evidence,
            approval,
        )
        approval["passed"] = not approval["failed"] and not approval["blockers"]

        if len(all_candidate_ids) != sum(expected_counts.values()):
            structural["failed"].append(
                self._issue(
                    "total_candidate_count_mismatch",
                    f"Expected 125 distinct candidate IDs; found {len(all_candidate_ids)}.",
                    "candidate_ids",
                )
            )
            structural["blockers"].append(
                self._issue(
                    "candidate_coverage_blocks_release",
                    "Candidate coverage is incomplete.",
                    "candidate_ids",
                )
            )
            structural["passed"] = False
            approval["blockers"].append(
                self._issue(
                    "candidate_coverage_incomplete",
                    "Not all expected candidates have been structurally represented.",
                    "candidate_ids",
                )
            )
            approval["passed"] = False

        return {
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "structural_validation": structural,
            "mapping_readiness": mapping,
            "approval_readiness": approval,
            "summary": counts,
            "human_review_decisions": self._human_review_decisions(
                mapping_by_id,
                specification_by_id,
            ),
            "publication_ready": (
                structural["passed"]
                and mapping["passed"]
                and approval["passed"]
            ),
        }

    def write_reports(self, report: dict[str, Any]) -> tuple[Path, Path]:
        """Write generated reports to the validation-only output directory."""

        self.output_dir.mkdir(parents=True, exist_ok=True)
        json_path = self.output_dir / "taxonomy_validation_report.json"
        markdown_path = self.output_dir / "taxonomy_validation_report.md"
        json_path.write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        markdown_path.write_text(
            self._render_markdown(report),
            encoding="utf-8",
        )
        return json_path, markdown_path

    def _validate_base_taxonomy(
        self,
        framework: dict[str, Any],
        cognitive_levels: dict[str, Any],
        hsa_config: dict[str, Any],
    ) -> dict[str, Any]:
        validator = TaxonomyValidator(
            framework_path=self.framework_path,
            cognitive_levels_path=self.cognitive_levels_path,
            hsa_config_path=self.hsa_config_path,
        )
        return validator.validate_data(
            framework=framework,
            cognitive_levels=cognitive_levels,
            hsa_config=hsa_config,
        )

    @staticmethod
    def _load_json(
        path: Path,
        failures: list[dict[str, str]],
        label: str,
    ) -> dict[str, Any]:
        try:
            with path.open("r", encoding="utf-8") as file:
                value = json.load(file)
            if not isinstance(value, dict):
                raise ValueError("Expected a JSON object.")
            return value
        except (OSError, json.JSONDecodeError, ValueError) as error:
            failures.append(
                SkillTaxonomyValidator._issue(
                    f"{label}_load_failed",
                    str(error),
                    str(path),
                )
            )
            return {}

    @staticmethod
    def _load_csv(
        path: Path,
        exam: str,
        artifact: str,
        failures: list[dict[str, str]],
    ) -> list[dict[str, str]]:
        try:
            with path.open("r", encoding="utf-8-sig", newline="") as file:
                reader = csv.DictReader(file, strict=True)
                if not reader.fieldnames:
                    raise csv.Error("CSV is missing a header row.")
                if len(reader.fieldnames) != len(set(reader.fieldnames)):
                    raise csv.Error("CSV contains duplicate header names.")
                rows = list(reader)
                if any(None in row for row in rows):
                    raise csv.Error("CSV row contains more fields than the header.")
                return [
                    {
                        key: (value or "").strip()
                        for key, value in row.items()
                        if key is not None
                    }
                    for row in rows
                ]
        except (OSError, csv.Error, UnicodeDecodeError) as error:
            failures.append(
                SkillTaxonomyValidator._issue(
                    f"{exam.lower()}_{artifact}_csv_invalid",
                    str(error),
                    str(path),
                )
            )
            return []

    @staticmethod
    def _empty_gate() -> dict[str, Any]:
        return {
            "passed": False,
            "failed": [],
            "warnings": [],
            "blockers": [],
        }

    @staticmethod
    def _issue(code: str, message: str, location: str) -> dict[str, str]:
        return {"code": code, "message": message, "location": location}

    @staticmethod
    def _validate_headers(
        rows: list[dict[str, str]],
        required: set[str],
        label: str,
        structural: dict[str, Any],
    ) -> None:
        if not rows:
            structural["failed"].append(
                SkillTaxonomyValidator._issue(
                    "artifact_has_no_rows",
                    f"{label} contains no data rows.",
                    label,
                )
            )
            return
        missing = required - set(rows[0])
        for column in sorted(missing):
            structural["failed"].append(
                SkillTaxonomyValidator._issue(
                    "required_column_missing",
                    f"{label} is missing required column {column!r}.",
                    label,
                )
            )

    @staticmethod
    def _index_rows(
        rows: list[dict[str, str]],
        key: str,
        label: str,
        structural: dict[str, Any],
    ) -> dict[str, dict[str, str]]:
        indexed: dict[str, dict[str, str]] = {}
        for row_number, row in enumerate(rows, start=2):
            value = row.get(key, "").strip()
            if not value:
                structural["failed"].append(
                    SkillTaxonomyValidator._issue(
                        "candidate_id_missing",
                        f"Row {row_number} has no {key}.",
                        f"{label}:{row_number}",
                    )
                )
                continue
            if value in indexed:
                structural["failed"].append(
                    SkillTaxonomyValidator._issue(
                        "duplicate_candidate_id",
                        f"Candidate ID {value} appears more than once in {label}.",
                        f"{label}:{row_number}",
                    )
                )
                continue
            indexed[value] = row
        return indexed

    @staticmethod
    def _build_hierarchy(
        framework: dict[str, Any],
    ) -> tuple[dict[str, dict[str, Any]], list[dict[str, str]]]:
        hierarchy: dict[str, dict[str, Any]] = {
            "exams": {},
            "sections": {},
            "competencies": {},
            "domains": {},
            "topics": {},
            "containers": {},
        }
        errors: list[dict[str, str]] = []
        global_ids: set[str] = set()

        def add_node(
            node: Any,
            kind: str,
            parent: dict[str, str],
            location: str,
            container: bool = False,
        ) -> str:
            if not isinstance(node, dict):
                errors.append(
                    SkillTaxonomyValidator._issue(
                        f"{kind}_must_be_object",
                        f"{kind} node must be a JSON object.",
                        location,
                    )
                )
                return ""
            node_id = node.get("id")
            name = node.get("name")
            if not isinstance(node_id, str) or not node_id.strip():
                errors.append(
                    SkillTaxonomyValidator._issue(
                        f"{kind}_id_missing",
                        f"{kind} node must have a non-empty string ID.",
                        location,
                    )
                )
                return ""
            if not isinstance(name, str) or not name.strip():
                errors.append(
                    SkillTaxonomyValidator._issue(
                        f"{kind}_name_missing",
                        f"{kind} {node_id} must have a non-empty name.",
                        location,
                    )
                )
            if node_id in global_ids:
                errors.append(
                    SkillTaxonomyValidator._issue(
                        "duplicate_taxonomy_id",
                        f"Taxonomy ID {node_id} is duplicated.",
                        location,
                    )
                )
            global_ids.add(node_id)
            entry = {**parent, "name": name or "", "node": node}
            if container:
                hierarchy["containers"][node_id] = entry
            else:
                hierarchy[f"{kind}s"][node_id] = entry
            return node_id

        exams = framework.get("exams")
        if not isinstance(exams, list):
            return hierarchy, [
                SkillTaxonomyValidator._issue(
                    "framework_exams_invalid",
                    "Framework exams must be a list.",
                    "framework.exams",
                )
            ]

        for exam_index, exam in enumerate(exams):
            exam_id = add_node(
                exam,
                "exam",
                {},
                f"framework.exams[{exam_index}]",
            )
            if not exam_id:
                continue
            hierarchy["exams"][exam_id]["exam_id"] = exam_id
            sections = exam.get("sections", [])
            if not isinstance(sections, list):
                errors.append(
                    SkillTaxonomyValidator._issue(
                        "exam_sections_invalid",
                        f"Exam {exam_id} sections must be a list.",
                        f"framework.exams[{exam_index}].sections",
                    )
                )
                continue
            for section_index, section in enumerate(sections):
                section_path = f"framework.exams[{exam_index}].sections[{section_index}]"
                is_container = isinstance(section, dict) and (
                    section.get("section_type") == "selection_container"
                    or section.get("id") == "HSA_OPTIONAL"
                )
                section_id = add_node(
                    section,
                    "section",
                    {"exam_id": exam_id},
                    section_path,
                    container=is_container,
                )
                if not section_id:
                    continue
                if is_container:
                    if section.get("competencies") not in (None, []):
                        errors.append(
                            SkillTaxonomyValidator._issue(
                                "selection_container_has_competencies",
                                "Selection container must not contain competencies.",
                                section_path,
                            )
                        )
                    branches = section.get("branches")
                    if not isinstance(branches, list):
                        errors.append(
                            SkillTaxonomyValidator._issue(
                                "selection_container_branches_invalid",
                                "Selection container branches must be a list.",
                                f"{section_path}.branches",
                            )
                        )
                        continue
                    for branch_index, branch in enumerate(branches):
                        branch_path = f"{section_path}.branches[{branch_index}]"
                        branch_id = add_node(
                            branch,
                            "section",
                            {"exam_id": exam_id, "container_id": section_id},
                            branch_path,
                        )
                        if branch_id:
                            SkillTaxonomyValidator._add_competencies(
                                branch,
                                branch_id,
                                exam_id,
                                hierarchy,
                                errors,
                                branch_path,
                                global_ids,
                            )
                else:
                    SkillTaxonomyValidator._add_competencies(
                        section,
                        section_id,
                        exam_id,
                        hierarchy,
                        errors,
                        section_path,
                        global_ids,
                    )
        return hierarchy, errors

    @staticmethod
    def _add_competencies(
        section: dict[str, Any],
        section_id: str,
        exam_id: str,
        hierarchy: dict[str, dict[str, Any]],
        errors: list[dict[str, str]],
        section_path: str,
        global_ids: set[str],
    ) -> None:
        competencies = section.get("competencies")
        if not isinstance(competencies, list):
            errors.append(
                SkillTaxonomyValidator._issue(
                    "section_competencies_invalid",
                    "Question-bearing section competencies must be a list.",
                    f"{section_path}.competencies",
                )
            )
            return
        for comp_index, competency in enumerate(competencies):
            comp_path = f"{section_path}.competencies[{comp_index}]"
            comp_id = SkillTaxonomyValidator._add_taxonomy_node(
                competency,
                "competency",
                {"exam_id": exam_id, "section_id": section_id},
                comp_path,
                hierarchy,
                errors,
                global_ids,
            )
            if not comp_id:
                continue
            domains = competency.get("domains", [])
            if not isinstance(domains, list):
                errors.append(
                    SkillTaxonomyValidator._issue(
                        "competency_domains_invalid",
                        "Competency domains must be a list.",
                        f"{comp_path}.domains",
                    )
                )
                continue
            for domain_index, domain in enumerate(domains):
                domain_path = f"{comp_path}.domains[{domain_index}]"
                domain_id = SkillTaxonomyValidator._add_taxonomy_node(
                    domain,
                    "domain",
                    {
                        "exam_id": exam_id,
                        "section_id": section_id,
                        "competency_id": comp_id,
                    },
                    domain_path,
                    hierarchy,
                    errors,
                    global_ids,
                )
                if not domain_id:
                    continue
                topics = domain.get("topics", [])
                if not isinstance(topics, list):
                    errors.append(
                        SkillTaxonomyValidator._issue(
                            "domain_topics_invalid",
                            "Domain topics must be a list.",
                            f"{domain_path}.topics",
                        )
                    )
                    continue
                for topic_index, topic in enumerate(topics):
                    SkillTaxonomyValidator._add_taxonomy_node(
                        topic,
                        "topic",
                        {
                            "exam_id": exam_id,
                            "section_id": section_id,
                            "competency_id": comp_id,
                            "domain_id": domain_id,
                        },
                        f"{domain_path}.topics[{topic_index}]",
                        hierarchy,
                        errors,
                        global_ids,
                    )

    @staticmethod
    def _add_taxonomy_node(
        node: Any,
        kind: str,
        parent: dict[str, str],
        location: str,
        hierarchy: dict[str, dict[str, Any]],
        errors: list[dict[str, str]],
        global_ids: set[str],
    ) -> str:
        if not isinstance(node, dict):
            errors.append(
                SkillTaxonomyValidator._issue(
                    f"{kind}_must_be_object",
                    f"{kind} node must be a JSON object.",
                    location,
                )
            )
            return ""
        node_id = node.get("id")
        name = node.get("name")
        if not isinstance(node_id, str) or not node_id.strip():
            errors.append(
                SkillTaxonomyValidator._issue(
                    f"{kind}_id_missing",
                    f"{kind} node must have a non-empty string ID.",
                    location,
                )
            )
            return ""
        if not isinstance(name, str) or not name.strip():
            errors.append(
                SkillTaxonomyValidator._issue(
                    f"{kind}_name_missing",
                    f"{kind} {node_id} must have a non-empty name.",
                    location,
                )
            )
        if node_id in global_ids:
            errors.append(
                SkillTaxonomyValidator._issue(
                    "duplicate_taxonomy_id",
                    f"Taxonomy ID {node_id} is duplicated.",
                    location,
                )
            )
        global_ids.add(node_id)
        collection = {
            "competency": "competencies",
            "domain": "domains",
            "topic": "topics",
        }[kind]
        hierarchy[collection][node_id] = {
            **parent,
            "name": name or "",
            "node": node,
        }
        return node_id

    def _validate_mapping_row(
        self,
        row: dict[str, str],
        exam: str,
        skill_id: str,
        hierarchy: dict[str, dict[str, Any]],
        structural: dict[str, Any],
        mapping: dict[str, Any],
        section_counts: dict[str, Any],
    ) -> None:
        location = skill_id
        for key in (
            "exam",
            "section_id",
            "competency_id",
            "mapping_status",
            "review_status",
            "reason",
        ):
            if not row.get(key, "").strip():
                structural["failed"].append(
                    self._issue(
                        "mapping_required_value_missing",
                        f"Mapping {skill_id} has an empty required value: {key}.",
                        location,
                    )
                )
        if row.get("exam") != exam:
            structural["failed"].append(
                self._issue(
                    "mapping_exam_mismatch",
                    f"Mapping {skill_id} must have exam {exam}.",
                    location,
                )
            )
        status = row.get("mapping_status")
        if status not in self.MAPPING_STATUSES:
            structural["failed"].append(
                self._issue(
                    "mapping_status_invalid",
                    f"Mapping {skill_id} has invalid status {status!r}.",
                    location,
                )
            )
            mapping["blockers"].append(
                self._issue(
                    "mapping_status_unresolved",
                    f"Mapping {skill_id} does not have a recognized mapping state.",
                    location,
                )
            )
        review_status = row.get("review_status")
        if review_status not in self.REVIEW_STATUSES:
            structural["failed"].append(
                self._issue(
                    "review_status_invalid",
                    f"Mapping {skill_id} has invalid review status {review_status!r}.",
                    location,
                )
            )
        if review_status != "pending_review":
            if review_status == "approved":
                mapping["blockers"].append(
                    self._issue(
                        "unsubstantiated_approval_state",
                        f"Mapping {skill_id} is marked approved; approval requires an auditable human decision record.",
                        location,
                    )
                )
            elif review_status == "rejected":
                mapping["blockers"].append(
                    self._issue(
                        "candidate_rejected",
                        f"Mapping {skill_id} is rejected and cannot be treated as ready.",
                        location,
                    )
                )
            else:
                mapping["blockers"].append(
                    self._issue(
                        "review_not_pending",
                        f"Mapping {skill_id} has not completed the review gate.",
                        location,
                    )
                )

        section_id = row.get("section_id", "")
        section = hierarchy["sections"].get(section_id)
        if not section:
            if section_id in hierarchy["containers"]:
                structural["failed"].append(
                    self._issue(
                        "selection_container_used_as_section",
                        "HSA_OPTIONAL is a selection container, not a question-bearing section.",
                        location,
                    )
                )
            else:
                structural["failed"].append(
                    self._issue(
                        "section_id_unknown",
                        f"Mapping {skill_id} references unknown question-bearing section {section_id!r}.",
                        location,
                    )
                )
        elif section.get("exam_id") != exam:
            structural["failed"].append(
                self._issue(
                    "section_exam_mismatch",
                    f"Section {section_id} does not belong to exam {exam}.",
                    location,
                )
            )

        competency = hierarchy["competencies"].get(row.get("competency_id", ""))
        if not competency:
            structural["failed"].append(
                self._issue(
                    "competency_id_unknown",
                    f"Mapping {skill_id} references an unknown Competency.",
                    location,
                )
            )
        elif (
            competency.get("exam_id") != exam
            or competency.get("section_id") != section_id
        ):
            structural["failed"].append(
                self._issue(
                    "competency_parent_path_invalid",
                    f"Competency for {skill_id} does not belong to its Exam and Section.",
                    location,
                )
            )

        for field, index, parent_field in (
            ("domain_id", "domains", "competency_id"),
            ("topic_id", "topics", "domain_id"),
        ):
            node_id = row.get(field, "").strip()
            if not node_id:
                continue
            node = hierarchy[index].get(node_id)
            if not node:
                structural["failed"].append(
                    self._issue(
                        f"{field}_unknown",
                        f"Mapping {skill_id} references unknown {field} {node_id}.",
                        location,
                    )
                )
            elif (
                node.get("exam_id") != exam
                or node.get("section_id") != section_id
                or node.get(parent_field) != row.get(parent_field, "")
            ):
                structural["failed"].append(
                    self._issue(
                        f"{field}_parent_path_invalid",
                        f"Mapping {skill_id} has an invalid {field} parent path.",
                        location,
                    )
                )

        if status == "matched" and not self._has_valid_topic_path(
            row,
            exam,
            hierarchy,
        ):
            mapping["blockers"].append(
                self._issue(
                    "matched_mapping_has_no_valid_topic",
                    f"Mapping {skill_id} is marked matched but has no valid canonical Topic path.",
                    location,
                )
            )
        elif status == "needs_new_topic":
            mapping["blockers"].append(
                self._issue(
                    "topic_not_yet_defined",
                    f"Mapping {skill_id} requires a proposed Topic and review; it is not mapping-ready.",
                    location,
                )
            )
        elif status == "ambiguous":
            mapping["blockers"].append(
                self._issue(
                    "mapping_parent_ambiguous",
                    f"Mapping {skill_id} requires a human decision on its primary Topic path.",
                    location,
                )
            )
        elif status == "needs_review":
            mapping["blockers"].append(
                self._issue(
                    "mapping_requires_expert_review",
                    f"Mapping {skill_id} has an unresolved subject-matter classification.",
                    location,
                )
            )
        elif status == "duplicate_candidate":
            mapping["blockers"].append(
                self._issue(
                    "duplicate_candidate_unresolved",
                    f"Mapping {skill_id} may duplicate another Skill candidate.",
                    location,
                )
            )
    def _validate_specification_row(
        self,
        mapping_row: dict[str, str],
        specification: dict[str, str],
        exam: str,
        skill_id: str,
        structural: dict[str, Any],
        mapping: dict[str, Any],
        section_counts: dict[str, Any],
    ) -> None:
        for key in self.SPECIFICATION_REQUIRED_COLUMNS:
            if key in {"domain_id", "topic_id"}:
                continue
            if not specification.get(key, "").strip():
                structural["failed"].append(
                    self._issue(
                        "specification_required_value_missing",
                        f"Specification {skill_id} has an empty required value: {key}.",
                        skill_id,
                    )
                )
        if exam == "HSA" and "subject_choice" not in specification:
            structural["failed"].append(
                self._issue(
                    "hsa_subject_choice_column_missing",
                    "HSA specification must include subject_choice.",
                    skill_id,
                )
            )
        if exam == "HSA" and specification.get("section_id") == "HSA_ENGLISH":
            metadata = specification.get("out_of_scope", "")
            if "A2-C1" not in metadata or "M1-M3" not in metadata:
                mapping["blockers"].append(
                    self._issue(
                        "hsa_english_proficiency_separation_unverified",
                        f"English candidate {skill_id} must keep A2-C1 proficiency separate from M1-M3.",
                        skill_id,
                    )
                )
        for key in (
            "exam",
            "section_id",
            "competency_id",
            "domain_id",
            "topic_id",
            "mapping_status",
            "review_status",
        ):
            if specification.get(key, "") != mapping_row.get(key, ""):
                structural["failed"].append(
                    self._issue(
                        "specification_mapping_mismatch",
                        f"Specification {skill_id} disagrees with mapping field {key}.",
                        skill_id,
                    )
                )
        if specification.get("review_status") != "pending_review":
            mapping["blockers"].append(
                self._issue(
                    "specification_not_pending_review",
                    f"Specification {skill_id} is not pending review.",
                    skill_id,
                )
            )
        if not mapping_row.get("domain_id") or not mapping_row.get("topic_id"):
            explanation = " ".join(
                specification.get(field, "")
                for field in (
                    "in_scope",
                    "out_of_scope",
                    "assessment_evidence",
                    "distinguishing_criteria",
                )
            )
            if "requires_review" not in explanation.lower():
                structural["failed"].append(
                    self._issue(
                        "unexplained_missing_parent",
                        f"Specification {skill_id} has a missing Domain/Topic without a requires_review explanation.",
                        skill_id,
                    )
                )

    @staticmethod
    def _has_valid_topic_path(
        row: dict[str, str],
        exam: str,
        hierarchy: dict[str, dict[str, Any]],
    ) -> bool:
        topic = hierarchy["topics"].get(row.get("topic_id", ""))
        if not topic:
            return False
        return (
            topic.get("exam_id") == exam
            and topic.get("section_id") == row.get("section_id")
            and topic.get("competency_id") == row.get("competency_id")
            and topic.get("domain_id") == row.get("domain_id")
        )

    @staticmethod
    def _add_hsa_branch_checks(
        mappings: dict[str, dict[str, str]],
        hierarchy: dict[str, dict[str, Any]],
        structural: dict[str, Any],
        mapping: dict[str, Any],
    ) -> None:
        container = hierarchy["containers"].get("HSA_OPTIONAL")
        if not container:
            structural["failed"].append(
                SkillTaxonomyValidator._issue(
                    "hsa_optional_container_missing",
                    "HSA_OPTIONAL selection container is missing.",
                    "framework.HSA_OPTIONAL",
                )
            )
            return
        branch_sections = {
            section_id: section
            for section_id, section in hierarchy["sections"].items()
            if section.get("container_id") == "HSA_OPTIONAL"
        }
        if set(branch_sections) != {"HSA_SCIENCE", "HSA_ENGLISH"}:
            structural["failed"].append(
                SkillTaxonomyValidator._issue(
                    "hsa_optional_branches_invalid",
                    "HSA_OPTIONAL must contain exactly HSA_SCIENCE and HSA_ENGLISH.",
                    "framework.HSA_OPTIONAL.branches",
                )
            )
        for skill_id, row in mappings.items():
            if row.get("section_id") == "HSA_OPTIONAL":
                structural["failed"].append(
                    SkillTaxonomyValidator._issue(
                        "selection_container_used_as_section",
                        "HSA_OPTIONAL cannot contain Skill candidates directly.",
                        skill_id,
                    )
                )
            if row.get("section_id") in {"HSA_SCIENCE", "HSA_ENGLISH"}:
                section = hierarchy["sections"].get(row["section_id"], {})
                if section.get("container_id") != "HSA_OPTIONAL":
                    structural["failed"].append(
                        SkillTaxonomyValidator._issue(
                            "hsa_optional_branch_misparented",
                            f"{row['section_id']} must be nested under HSA_OPTIONAL.",
                            skill_id,
                        )
                    )
            subject = row.get("subject_choice", "").strip()
            if row.get("section_id") == "HSA_SCIENCE" and subject and subject not in {
                "PHYSICS",
                "CHEMISTRY",
                "BIOLOGY",
                "HISTORY",
                "GEOGRAPHY",
            }:
                structural["failed"].append(
                    SkillTaxonomyValidator._issue(
                        "hsa_subject_choice_invalid",
                        f"Science candidate {skill_id} has invalid subject_choice {subject!r}.",
                        skill_id,
                    )
                )
            if row.get("section_id") == "HSA_ENGLISH" and subject:
                structural["failed"].append(
                    SkillTaxonomyValidator._issue(
                        "hsa_english_subject_choice_not_applicable",
                        f"English candidate {skill_id} must not have a Science subject_choice.",
                        skill_id,
                    )
                )
    def _validate_approval_evidence(
        self,
        framework: dict[str, Any],
        mapping: dict[str, Any],
        evidence: dict[str, Any] | None,
        approval: dict[str, Any],
    ) -> None:
        if not evidence:
            approval["blockers"].append(
                self._issue(
                    "human_review_evidence_missing",
                    "No reviewer, timestamp, decision record, or audit trail was provided.",
                    "approval_evidence",
                )
            )
            approval["blockers"].append(
                self._issue(
                    "version_audit_policy_missing",
                    "A versioning and audit policy must be confirmed before release.",
                    "approval_evidence.audit_trail",
                )
            )
        else:
            reviewer = evidence.get("reviewer")
            reviewed_at = evidence.get("reviewed_at")
            decision = evidence.get("decision")
            if not isinstance(reviewer, str) or not reviewer.strip():
                approval["blockers"].append(
                    self._issue(
                        "reviewer_missing",
                        "A human reviewer identity is required.",
                        "approval_evidence.reviewer",
                    )
                )
            if not self._valid_timestamp(reviewed_at):
                approval["blockers"].append(
                    self._issue(
                        "review_timestamp_invalid",
                        "A valid ISO-8601 review timestamp is required.",
                        "approval_evidence.reviewed_at",
                    )
                )
            if decision not in {"approved", "rejected"}:
                approval["blockers"].append(
                    self._issue(
                        "review_decision_missing",
                        "A clear review decision is required.",
                        "approval_evidence.decision",
                    )
                )
            if not evidence.get("audit_trail"):
                approval["blockers"].append(
                    self._issue(
                        "version_audit_policy_missing",
                        "A versioned audit trail/policy is required before release.",
                        "approval_evidence.audit_trail",
                    )
                )
        if framework.get("status") != "approved":
            approval["blockers"].append(
                self._issue(
                    "taxonomy_not_approved",
                    "The taxonomy framework is not approved.",
                    "framework.status",
                )
            )
        if mapping["blockers"] or mapping["failed"]:
            approval["blockers"].append(
                self._issue(
                    "mapping_gate_not_passed",
                    "Approval is blocked until all candidate mappings pass the mapping-readiness gate.",
                    "mapping_readiness",
                )
            )
        approval["warnings"].append(
            self._issue(
                "approval_gate_is_non_mutating",
                "This validator does not approve candidates or change framework status.",
                "approval_readiness",
            )
        )

    @staticmethod
    def _valid_timestamp(value: Any) -> bool:
        if not isinstance(value, str) or not value.strip():
            return False
        try:
            datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return False
        return True

    @staticmethod
    def _human_review_decisions(
        mappings: dict[str, dict[str, dict[str, str]]],
        specifications: dict[str, dict[str, dict[str, str]]],
    ) -> list[dict[str, str]]:
        decisions: list[dict[str, str]] = []
        for exam, rows in mappings.items():
            for skill_id, row in rows.items():
                status = row.get("mapping_status", "")
                spec = specifications.get(exam, {}).get(skill_id, {})
                if status != "matched":
                    decisions.append(
                        {
                            "skill_id": skill_id,
                            "exam": exam,
                            "section_id": row.get("section_id", ""),
                            "decision": status,
                            "required_action": row.get("reason", "Resolve mapping status."),
                        }
                    )
                elif spec.get("review_status") == "pending_review":
                    decisions.append(
                        {
                            "skill_id": skill_id,
                            "exam": exam,
                            "section_id": row.get("section_id", ""),
                            "decision": "pending_review",
                            "required_action": "Record expert construct, evidence, and distinction decision.",
                        }
                    )
        decisions.extend(
            [
                {
                    "skill_id": "",
                    "exam": "TSA",
                    "section_id": "TSA_MATH",
                    "decision": "taxonomy_structure_review",
                    "required_action": "Resolve missing TSA Math Domain/Topic structure before assigning official Topic IDs.",
                },
                {
                    "skill_id": "",
                    "exam": "TSA",
                    "section_id": "TSA_READING",
                    "decision": "stimulus_type_vs_skill",
                    "required_action": "Decide whether the three Text Type concepts are metadata or learner Skills.",
                },
                {
                    "skill_id": "",
                    "exam": "HSA",
                    "section_id": "HSA_SCIENCE",
                    "decision": "primary_path_policy",
                    "required_action": "Define primary-path selection for items combining subject knowledge, data, and reasoning.",
                },
                {
                    "skill_id": "",
                    "exam": "HSA",
                    "section_id": "HSA_ENGLISH",
                    "decision": "proficiency_evidence",
                    "required_action": "Obtain representative English items and validate proficiency separately from M1-M3 and difficulty.",
                },
                {
                    "skill_id": "",
                    "exam": "Cross-exam",
                    "section_id": "",
                    "decision": "versioning_and_approval_policy",
                    "required_action": "Approve version identifiers, decision records, and audit semantics before publication or database integration.",
                },
            ]
        )
        return decisions

    @staticmethod
    def _render_markdown(report: dict[str, Any]) -> str:
        summary = report["summary"]
        lines = [
            "# Taxonomy Validation Report",
            "",
            f"- Generated: `{report['generated_at']}`",
            f"- Publication ready: **{str(report['publication_ready']).upper()}**",
            f"- Candidates: **{summary['candidate_count']}**",
            f"- Mapping-valid candidates: **{summary['mapping_valid_count']}**",
            f"- Mapping blockers: **{summary['mapping_blocker_count']}**",
            "",
            "## Candidate counts",
            "",
            "| Exam | Candidates |",
            "| --- | ---: |",
        ]
        for exam, count in summary["by_exam"].items():
            lines.append(f"| {exam} | {count} |")
        lines.extend(
            [
                "",
                "### By section",
                "",
                "| Exam / Section | Candidates | Mapping valid | Mapping blockers |",
                "| --- | ---: | ---: | ---: |",
            ]
        )
        for section, values in summary["by_exam_section"].items():
            lines.append(
                f"| {section} | {values['candidate_count']} | "
                f"{values['mapping_valid_count']} | "
                f"{values['mapping_blocker_count']} |"
            )
        lines.extend(
            [
                "",
                "| Mapping status | Count |",
                "| --- | ---: |",
            ]
        )
        for status, count in summary["by_mapping_status"].items():
            lines.append(f"| `{status}` | {count} |")
        for gate_name, title in (
            ("structural_validation", "Structural Validation"),
            ("mapping_readiness", "Mapping Readiness"),
            ("approval_readiness", "Approval Readiness"),
        ):
            gate = report[gate_name]
            lines.extend(
                [
                    "",
                    f"## {title}: {'PASS' if gate['passed'] else 'FAIL'}",
                    "",
                    f"- Passed: `{gate['passed']}`",
                    f"- Failed: {len(gate['failed'])}",
                    f"- Warnings: {len(gate['warnings'])}",
                    f"- Blockers: {len(gate['blockers'])}",
                ]
            )
            for group in ("failed", "warnings", "blockers"):
                if gate[group]:
                    lines.extend(["", f"### {group.title()}", ""])
                    displayed = gate[group][:12]
                    for issue in displayed:
                        lines.append(
                            f"- `{issue['code']}` — {issue['message']} (`{issue['location']}`)"
                        )
                    remaining = len(gate[group]) - len(displayed)
                    if remaining:
                        lines.append(
                            f"- {remaining} additional detailed entries are listed in the JSON report."
                        )
        lines.extend(
            [
                "",
                "## Human review decisions",
                "",
                f"Open decisions requiring review: **{len(report['human_review_decisions'])}**",
                "",
                "Detailed decision records are included in the JSON report.",
                "",
                "This report is a non-approving validation result. Structural validity does not imply mapping or approval readiness.",
                "",
            ]
        )
        return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate TSA/HSA Skill taxonomy review artifacts."
    )
    parser.add_argument(
        "--review-root",
        type=Path,
        default=SkillTaxonomyValidator.DEFAULT_REVIEW_ROOT,
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=SkillTaxonomyValidator.DEFAULT_OUTPUT_DIR,
    )
    args = parser.parse_args()

    validator = SkillTaxonomyValidator(
        review_root=args.review_root,
        output_dir=args.output_dir,
    )
    report = validator.validate()
    json_path, markdown_path = validator.write_reports(report)
    print(
        f"Structural: {'PASS' if report['structural_validation']['passed'] else 'FAIL'}; "
        f"Mapping: {'PASS' if report['mapping_readiness']['passed'] else 'BLOCKED'}; "
        f"Approval: {'PASS' if report['approval_readiness']['passed'] else 'BLOCKED'}"
    )
    print(f"JSON report: {json_path}")
    print(f"Markdown report: {markdown_path}")
    return 0 if report["structural_validation"]["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
