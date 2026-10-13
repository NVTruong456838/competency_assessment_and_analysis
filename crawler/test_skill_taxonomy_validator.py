import copy
import csv
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from crawler.validation.skill_taxonomy_validator import (
    SkillTaxonomyValidator,
)


CRAWLER_ROOT = Path(__file__).resolve().parent
TAXONOMY_ROOT = CRAWLER_ROOT / "taxonomy"
REVIEW_ROOT = TAXONOMY_ROOT / "review"


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as file:
        return json.load(file)


def load_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        return list(csv.DictReader(file, strict=True))


class SkillTaxonomyValidatorTests(unittest.TestCase):
    def setUp(self):
        self.validator = SkillTaxonomyValidator()
        self.framework = load_json(TAXONOMY_ROOT / "competency_framework.json")
        self.cognitive_levels = load_json(
            TAXONOMY_ROOT / "cognitive_levels.json"
        )
        self.hsa_config = load_json(
            CRAWLER_ROOT
            / "data"
            / "processed"
            / "hsa"
            / "hsa_2026_framework.json"
        )
        self.mapping_rows = {
            exam: load_csv(REVIEW_ROOT / filename)
            for exam, filename in self.validator.MAPPING_FILES.items()
        }
        self.specification_rows = {
            exam: load_csv(REVIEW_ROOT / filename)
            for exam, filename in self.validator.SPECIFICATION_FILES.items()
        }

    def validate(self, **overrides):
        values = {
            "framework": self.framework,
            "cognitive_levels": self.cognitive_levels,
            "hsa_config": self.hsa_config,
            "mapping_rows": self.mapping_rows,
            "specification_rows": self.specification_rows,
        }
        values.update(overrides)
        return self.validator.validate_data(**values)

    @staticmethod
    def find_row(rows, key, value):
        return next(row for row in rows if row[key] == value)

    def test_current_artifacts_pass_structure_but_not_release_gates(self):
        report = self.validator.validate()

        self.assertTrue(
            report["structural_validation"]["passed"],
            report["structural_validation"]["failed"],
        )
        self.assertFalse(report["mapping_readiness"]["passed"])
        self.assertFalse(report["approval_readiness"]["passed"])
        self.assertFalse(report["publication_ready"])
        self.assertEqual(report["summary"]["candidate_count"], 125)
        self.assertEqual(report["summary"]["by_exam"], {"TSA": 48, "HSA": 77})
        self.assertEqual(report["summary"]["mapping_valid_count"], 89)
        self.assertEqual(report["summary"]["mapping_blocker_count"], 36)
        self.assertEqual(len(report["mapping_readiness"]["blockers"]), 36)

    def test_csv_with_malformed_quoting_fails_structural_validation(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            review_root = Path(temp_dir)
            for filename in (
                *self.validator.MAPPING_FILES.values(),
                *self.validator.SPECIFICATION_FILES.values(),
            ):
                shutil.copy(REVIEW_ROOT / filename, review_root / filename)
            tsa_mapping = review_root / self.validator.MAPPING_FILES["TSA"]
            content = tsa_mapping.read_text(encoding="utf-8")
            header, first_row, *rest = content.splitlines()
            tsa_mapping.write_text(
                "\n".join([header, '"unterminated field', *rest]) + "\n",
                encoding="utf-8",
            )

            report = SkillTaxonomyValidator(
                review_root=review_root,
                output_dir=review_root / "validation",
            ).validate()

        self.assertFalse(report["structural_validation"]["passed"])
        self.assertIn(
            "tsa_mapping_csv_invalid",
            {
                issue["code"]
                for issue in report["structural_validation"]["failed"]
            },
        )

    def test_duplicate_candidate_id_fails_structure(self):
        rows = copy.deepcopy(self.mapping_rows)
        rows["TSA"].append(copy.deepcopy(rows["TSA"][0]))

        report = self.validate(mapping_rows=rows)

        self.assertIn(
            "duplicate_candidate_id",
            {issue["code"] for issue in report["structural_validation"]["failed"]},
        )

    def test_candidate_missing_specification_fails_structure(self):
        rows = copy.deepcopy(self.specification_rows)
        rows["TSA"] = rows["TSA"][1:]

        report = self.validate(specification_rows=rows)

        self.assertIn(
            "candidate_missing_specification",
            {issue["code"] for issue in report["structural_validation"]["failed"]},
        )

    def test_unknown_topic_id_fails_structure_and_mapping(self):
        mappings = copy.deepcopy(self.mapping_rows)
        specifications = copy.deepcopy(self.specification_rows)
        candidate_id = "SKILL_MATH_EQUATION_SOLVE"
        self.find_row(
            mappings["TSA"], "candidate_skill_id", candidate_id
        )["topic_id"] = "TSA_TOPIC_DOES_NOT_EXIST"
        self.find_row(
            specifications["TSA"], "skill_id", candidate_id
        )["topic_id"] = "TSA_TOPIC_DOES_NOT_EXIST"

        report = self.validate(
            mapping_rows=mappings,
            specification_rows=specifications,
        )

        self.assertIn(
            "topic_id_unknown",
            {issue["code"] for issue in report["structural_validation"]["failed"]},
        )
        self.assertFalse(report["mapping_readiness"]["passed"])

    def test_topic_with_wrong_parent_path_fails_structure(self):
        mappings = copy.deepcopy(self.mapping_rows)
        specifications = copy.deepcopy(self.specification_rows)
        candidate_id = "SKILL_HSA_MATH_EQUATION_INEQUALITY_SOLVE"
        wrong_topic = "HSA_MATH_MEASUREMENT"
        self.find_row(
            mappings["HSA"], "candidate_skill_id", candidate_id
        )["topic_id"] = wrong_topic
        self.find_row(
            specifications["HSA"], "skill_id", candidate_id
        )["topic_id"] = wrong_topic

        report = self.validate(
            mapping_rows=mappings,
            specification_rows=specifications,
        )

        self.assertIn(
            "topic_id_parent_path_invalid",
            {issue["code"] for issue in report["structural_validation"]["failed"]},
        )

    def test_matched_mapping_without_topic_is_blocked(self):
        rows = copy.deepcopy(self.mapping_rows)
        candidate = self.find_row(
            rows["TSA"], "candidate_skill_id", "SKILL_MATH_EQUATION_SOLVE"
        )
        candidate["topic_id"] = ""

        report = self.validate(mapping_rows=rows)

        self.assertIn(
            "matched_mapping_has_no_valid_topic",
            {issue["code"] for issue in report["mapping_readiness"]["blockers"]},
        )

    def test_needs_new_topic_remains_mapping_blocker(self):
        report = self.validate()

        self.assertIn(
            "topic_not_yet_defined",
            {issue["code"] for issue in report["mapping_readiness"]["blockers"]},
        )
        self.assertEqual(
            report["summary"]["by_mapping_status"]["needs_new_topic"],
            32,
        )

    def test_hsa_optional_cannot_be_used_as_question_section(self):
        mappings = copy.deepcopy(self.mapping_rows)
        specifications = copy.deepcopy(self.specification_rows)
        candidate_id = "SKILL_HSA_MATH_NUMBER_SET_REASON"
        self.find_row(
            mappings["HSA"], "candidate_skill_id", candidate_id
        )["section_id"] = "HSA_OPTIONAL"
        self.find_row(
            specifications["HSA"], "skill_id", candidate_id
        )["section_id"] = "HSA_OPTIONAL"

        report = self.validate(
            mapping_rows=mappings,
            specification_rows=specifications,
        )

        self.assertIn(
            "selection_container_used_as_section",
            {issue["code"] for issue in report["structural_validation"]["failed"]},
        )

    def test_invalid_review_status_fails_structure(self):
        mappings = copy.deepcopy(self.mapping_rows)
        specifications = copy.deepcopy(self.specification_rows)
        candidate_id = "SKILL_MATH_EQUATION_SOLVE"
        self.find_row(
            mappings["TSA"], "candidate_skill_id", candidate_id
        )["review_status"] = "pending"
        self.find_row(
            specifications["TSA"], "skill_id", candidate_id
        )["review_status"] = "pending"

        report = self.validate(
            mapping_rows=mappings,
            specification_rows=specifications,
        )

        self.assertIn(
            "review_status_invalid",
            {issue["code"] for issue in report["structural_validation"]["failed"]},
        )

    def test_draft_framework_is_not_approved(self):
        report = self.validate()

        self.assertEqual(self.framework["status"], "draft")
        self.assertFalse(report["approval_readiness"]["passed"])
        self.assertIn(
            "taxonomy_not_approved",
            {issue["code"] for issue in report["approval_readiness"]["blockers"]},
        )

    def test_pending_review_candidates_block_approval(self):
        report = self.validate()

        pending = [
            issue
            for issue in report["approval_readiness"]["blockers"]
            if issue["code"] == "candidate_pending_human_review"
        ]
        self.assertEqual(len(pending), 125)
        self.assertIn(
            "human_review_evidence_missing",
            {issue["code"] for issue in report["approval_readiness"]["blockers"]},
        )

    def test_hsa_english_proficiency_must_remain_separate(self):
        specifications = copy.deepcopy(self.specification_rows)
        row = self.find_row(
            specifications["HSA"],
            "skill_id",
            "SKILL_HSA_ENG_GRAMMAR_USE",
        )
        row["out_of_scope"] = "Cognitive Level (M1-M3) and difficulty are separate."

        report = self.validate(specification_rows=specifications)

        self.assertIn(
            "hsa_english_proficiency_separation_unverified",
            {issue["code"] for issue in report["mapping_readiness"]["blockers"]},
        )

    def test_validation_does_not_mutate_inputs(self):
        framework = copy.deepcopy(self.framework)
        cognitive_levels = copy.deepcopy(self.cognitive_levels)
        hsa_config = copy.deepcopy(self.hsa_config)
        mappings = copy.deepcopy(self.mapping_rows)
        specifications = copy.deepcopy(self.specification_rows)

        self.validate(
            framework=framework,
            cognitive_levels=cognitive_levels,
            hsa_config=hsa_config,
            mapping_rows=mappings,
            specification_rows=specifications,
        )

        self.assertEqual(framework, self.framework)
        self.assertEqual(cognitive_levels, self.cognitive_levels)
        self.assertEqual(hsa_config, self.hsa_config)
        self.assertEqual(mappings, self.mapping_rows)
        self.assertEqual(specifications, self.specification_rows)


if __name__ == "__main__":
    unittest.main()
