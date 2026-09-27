import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

from crawler.common.question_extractor import QuestionExtractor
from crawler.pipelines.hsa_pipeline import HsaPipeline
from crawler.sources.vnu_hsa import VnuHsaCrawler


class HsaCrawlerTests(unittest.TestCase):
    def test_finds_document_uri_in_next_payload(self):
        crawler = VnuHsaCrawler()
        crawler.http = Mock(return_value=None)
        crawler.http.get.return_value = SimpleNamespace(
            headers={"Content-Type": "text/html; charset=utf-8"},
            text=(
                '<html><head><title>Đề tham khảo HSA 2025</title></head>'
                '<body><script>self.__next_f.push([1,'
                '"{\\"documents\\":[{\\"uri\\":'
                '\\"/api/media/file/De%20thi%20tham%20khao%20HSA%20nam%20'
                '2025-1.pdf\\"}]}" ])</script></body></html>'
            ),
        )
        crawler.robots = Mock()
        crawler.robots.can_fetch.return_value = True
        crawler.storage = Mock()

        documents = crawler.crawl(max_pages=1)

        self.assertEqual(len(documents), 1)
        self.assertEqual(
            documents[0]["url"],
            "https://idt.vnu.edu.vn/api/media/file/De%20thi%20tham%20khao%20HSA%20nam%202025-1.pdf",
        )
        self.assertEqual(documents[0]["document_type"], "sample_exam")
        crawler.storage.save.assert_called_once_with(documents, "hsa_documents.json")

    def test_does_not_follow_other_sections_or_pagination(self):
        crawler = VnuHsaCrawler()
        crawler.http = Mock()
        crawler.http.get.return_value = SimpleNamespace(
            headers={"Content-Type": "text/html"},
            text=(
                '<a href="/ky-thi-hsa/quy-che-huong-dan">Rules</a>'
                '<a href="/ky-thi-hsa/de-thi-tham-khao?page=-1">Next</a>'
            ),
        )
        crawler.robots = Mock()
        crawler.robots.can_fetch.return_value = True
        crawler.storage = Mock()

        crawler.crawl(max_pages=5)

        self.assertEqual(crawler.http.get.call_count, 1)


class HsaPipelineTests(unittest.TestCase):
    def test_online_download_requires_rights_confirmation(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            pipeline = HsaPipeline(
                metadata_dir=str(root / "output"),
                raw_dir=str(root / "raw"),
                processed_dir=str(root / "processed"),
            )
            pipeline.downloader.download = Mock()

            self.assertEqual(pipeline.run(), [])
            pipeline.downloader.download.assert_not_called()

    def test_empty_local_input_preserves_existing_outputs(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            raw_dir = root / "raw"
            processed_dir = root / "processed"
            output_dir = root / "output"
            raw_dir.mkdir()
            processed_dir.mkdir()
            output_dir.mkdir()
            existing_outputs = {
                processed_dir / "hsa_extracted_questions.json": '[{"id":"old"}]',
                processed_dir / "hsa_question_bank.json": '[{"id":"old"}]',
                output_dir / "hsa_local_processed_documents.json": '[{"id":"old"}]',
            }
            for path, content in existing_outputs.items():
                path.write_text(content, encoding="utf-8")
            pipeline = HsaPipeline(
                metadata_dir=str(output_dir),
                raw_dir=str(raw_dir),
                processed_dir=str(processed_dir),
            )

            self.assertEqual(pipeline.run_local_files(), [])

            for path, content in existing_outputs.items():
                self.assertEqual(path.read_text(encoding="utf-8"), content)

    def test_local_pipeline_writes_tsa_like_outputs(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            raw_dir = root / "raw"
            raw_dir.mkdir()
            (raw_dir / "public_sample.pdf").write_bytes(b"%PDF-test")
            pipeline = HsaPipeline(
                metadata_dir=str(root / "output"),
                raw_dir=str(raw_dir),
                processed_dir=str(root / "processed"),
            )
            pipeline.parser.parse_file = Mock(return_value={
                "document_type": "sample_exam",
                "content_type": "pdf",
                "content": "Câu 1: Câu hỏi mẫu.",
                "pages": [{"page": 1, "text": "Câu 1: Câu hỏi mẫu."}],
            })
            pipeline.question_extractor.extract_from_document = Mock(
                return_value=[{"question_number": 1, "stem": "Câu hỏi mẫu."}]
            )
            pipeline.question_visuals.attach_pdf_pages = Mock(side_effect=(
                lambda document, pdf_path, document_id, questions: questions[0].update({
                    "exam": "HSA",
                    "source_pages": [1],
                    "visual_assets": [{"page_number": 1, "path": "visuals/page_001.png"}],
                })
            ))

            processed = pipeline.run_local_files()

            self.assertEqual(processed[0]["document_id"], "VNU_HSA_LOCAL_000001")
            self.assertEqual(processed[0]["status"], "processed_local")
            summary_path = root / "output" / "hsa_local_processed_documents.json"
            questions_path = root / "processed" / "hsa_extracted_questions.json"
            bank_path = root / "processed" / "hsa_question_bank.json"
            detail_path = root / "processed" / "VNU_HSA_LOCAL_000001.json"
            self.assertTrue(summary_path.is_file())
            self.assertTrue(detail_path.is_file())
            with questions_path.open("r", encoding="utf-8") as file:
                self.assertEqual(json.load(file), [
                    {
                        "question_number": 1,
                        "stem": "Câu hỏi mẫu.",
                        "exam": "HSA",
                        "source_pages": [1],
                        "visual_assets": [{
                            "page_number": 1,
                            "path": "visuals/page_001.png",
                        }],
                    }
                ])
            with bank_path.open("r", encoding="utf-8") as file:
                question_bank = json.load(file)
            self.assertEqual(question_bank[0]["metadata"]["exam"], "HSA")
            self.assertEqual(
                question_bank[0]["visual_assets"][0]["path"],
                "visuals/page_001.png",
            )

    def test_extractor_accepts_hsa_question_headers(self):
        document = {
            "document_id": "VNU_HSA_TEST",
            "content_type": "pdf",
            "pages": [{
                "page": 9,
                "text": (
                    "Câu hỏi 01\nChọn đáp án đúng.\n"
                    "(A) Một\n(B) Hai\n(C) Ba\n(D) Bốn\n"
                    "Câu hỏi 02\nTính kết quả."
                ),
            }],
        }

        questions = QuestionExtractor().extract_from_document(document)

        self.assertEqual([question["question_number"] for question in questions], [1, 2])
        self.assertEqual(questions[0]["options"], {
            "A": "Một",
            "B": "Hai",
            "C": "Ba",
            "D": "Bốn",
        })


if __name__ == "__main__":
    unittest.main()