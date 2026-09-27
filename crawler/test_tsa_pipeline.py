import tempfile
import unittest
from pathlib import Path

from crawler.pipelines.tsa_pipeline import TsaPipeline


class TsaPipelineTests(unittest.TestCase):
    def test_empty_local_input_preserves_existing_question_outputs(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            raw_dir = root / "raw"
            processed_dir = root / "processed"
            raw_dir.mkdir()
            processed_dir.mkdir()
            existing_outputs = {
                processed_dir / "tsa_extracted_questions.json": '[{"id":"old"}]',
                processed_dir / "tsa_question_bank.json": '[{"id":"old"}]',
            }
            for path, content in existing_outputs.items():
                path.write_text(content, encoding="utf-8")
            pipeline = TsaPipeline(
                metadata_dir=str(root / "output"),
                raw_dir=str(raw_dir),
                processed_dir=str(processed_dir),
            )

            self.assertEqual(pipeline.run_local_files(), [])

            for path, content in existing_outputs.items():
                self.assertEqual(path.read_text(encoding="utf-8"), content)


if __name__ == "__main__":
    unittest.main()