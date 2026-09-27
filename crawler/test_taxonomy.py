import copy
import json
import unittest
from pathlib import Path
from typing import Any

from crawler.validation.taxonomy_validator import TaxonomyValidator


PROJECT_ROOT = Path(__file__).resolve().parent

FRAMEWORK_PATH = PROJECT_ROOT / "taxonomy" / "competency_framework.json"
COGNITIVE_LEVELS_PATH = PROJECT_ROOT / "taxonomy" / "cognitive_levels.json"
HSA_CONFIG_PATH = (
    PROJECT_ROOT
    / "data"
    / "processed"
    / "hsa"
    / "hsa_2026_framework.json"
)


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as file:
        return json.load(file)


class TaxonomyValidatorTests(unittest.TestCase):
    def setUp(self):
        self.validator = TaxonomyValidator(
            framework_path=FRAMEWORK_PATH,
            cognitive_levels_path=COGNITIVE_LEVELS_PATH,
            hsa_config_path=HSA_CONFIG_PATH,
        )

        self.framework = load_json(FRAMEWORK_PATH)
        self.cognitive_levels = load_json(COGNITIVE_LEVELS_PATH)
        self.hsa_config = load_json(HSA_CONFIG_PATH)

    def test_accepts_current_taxonomy_resources(self):
        report = self.validator.validate()

        self.assertTrue(report["valid"], report["errors"])
        self.assertEqual(report["summary"]["exams"], 2)
        self.assertEqual(report["summary"]["cognitive_levels"], 3)

    def test_rejects_duplicate_taxonomy_id(self):
        framework = copy.deepcopy(self.framework)
        hsa = next(
            exam
            for exam in framework["exams"]
            if exam["id"] == "HSA"
        )
        hsa["sections"][0]["competencies"][0]["id"] = "TSA"

        report = self.validator.validate_data(
            framework=framework,
            cognitive_levels=self.cognitive_levels,
            hsa_config=self.hsa_config,
        )

        self.assertFalse(report["valid"])
        self.assertIn(
            "duplicate_taxonomy_id",
            {error["code"] for error in report["errors"]},
        )

    def test_rejects_hsa_config_with_invalid_branch_selection(self):
        hsa_config = copy.deepcopy(self.hsa_config)
        optional = next(
            section
            for section in hsa_config["sections"]
            if section["id"] == "HSA_OPTIONAL"
        )
        optional["selection_mode"] = "all_branches"

        report = self.validator.validate_data(
            framework=self.framework,
            cognitive_levels=self.cognitive_levels,
            hsa_config=hsa_config,
        )

        self.assertFalse(report["valid"])
        self.assertIn(
            "hsa_config_optional_selection_mode_invalid",
            {error["code"] for error in report["errors"]},
        )


if __name__ == "__main__":
    unittest.main()
