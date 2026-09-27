import tempfile
import unittest
from pathlib import Path

import pymupdf

from crawler.common.document_parser import DocumentParser
from crawler.common.question_visuals import QuestionVisualProcessor


class QuestionVisualProcessorTests(unittest.TestCase):
    def test_attaches_page_image_and_marks_low_text_pages(self):
        source_pdf = pymupdf.open()
        page = source_pdf.new_page(width=72, height=72)
        source_image = pymupdf.Pixmap(
            pymupdf.csRGB,
            pymupdf.IRect(0, 0, 10, 10),
            0,
        )
        source_image.clear_with(255)
        page.insert_image(page.rect, pixmap=source_image)

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            pdf_path = root / "sample.pdf"
            source_pdf.save(pdf_path)
            processed_dir = root / "processed"
            question = {
                "question_number": 1,
                "stem": "Dựa vào biểu đồ, chọn đáp án đúng.",
                "page_number": 1,
                "source_pages": [1],
                "quality": {
                    "extraction_status": "needs_review",
                    "warnings": ["visual_content_needs_review"],
                },
            }
            document = {"pages": [{"page": 1, "text": ""}]}
            processor = QuestionVisualProcessor(
                parser=DocumentParser(),
                processed_dir=str(processed_dir),
            )

            processor.attach_pdf_pages(
                document=document,
                pdf_path=str(pdf_path),
                document_id="HSA_TEST",
                questions=[question],
            )

            asset = question["visual_assets"][0]
            asset_path = processed_dir / asset["path"]
            self.assertEqual(asset["page_number"], 1)
            self.assertEqual(asset_path.read_bytes()[:8], b"\x89PNG\r\n\x1a\n")
            self.assertEqual(document["pages_needing_ocr"], [1])
            self.assertEqual(document["visual_page_numbers"], [1])
            self.assertEqual(question["quality"]["extraction_status"], "needs_review")
            self.assertIn(
                "visual_content_needs_review",
                question["quality"]["warnings"],
            )
            source_pdf.close()

    def test_crops_visuals_to_question_region_not_page(self):
        pdf = pymupdf.open()
        page = pdf.new_page(width=300, height=400)
        page.insert_text(pymupdf.Point(20, 35), "Question 1")
        for offset in range(7):
            x = 60 + offset * 12
            page.draw_line(
                pymupdf.Point(x, 65),
                pymupdf.Point(x + 8, 65 + offset * 5),
                color=(0, 0, 0),
                width=1,
            )
        page.insert_text(pymupdf.Point(20, 190), "Question 2")
        page.insert_text(pymupdf.Point(20, 220), "A plain text question.")

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            pdf_path = root / "questions.pdf"
            pdf.save(pdf_path)
            pdf.close()
            processed_dir = root / "processed"
            questions = [
                {
                    "question_number": 1,
                    "stem": "Use the graph to answer.",
                    "page_number": 1,
                    "source_pages": [1],
                    "quality": {"warnings": []},
                },
                {
                    "question_number": 2,
                    "stem": "A plain text question.",
                    "page_number": 1,
                    "source_pages": [1],
                    "quality": {"warnings": []},
                },
            ]
            document = {"pages": [{"page": 1, "text": ""}]}
            processor = QuestionVisualProcessor(
                parser=DocumentParser(),
                processed_dir=str(processed_dir),
            )

            processor.attach_pdf_pages(
                document=document,
                pdf_path=str(pdf_path),
                document_id="CROP_TEST",
                questions=questions,
            )

            self.assertEqual(len(questions[0]["visual_assets"]), 1)
            self.assertEqual(questions[1]["visual_assets"], [])
            self.assertLess(questions[0]["visual_assets"][0]["bbox"][3], 190)
            self.assertEqual(len(document["visual_assets"]), 1)
            self.assertIn(
                "visual_content_needs_review",
                questions[0]["quality"]["warnings"],
            )
            self.assertNotIn(
                "visual_content_needs_review",
                questions[1]["quality"]["warnings"],
            )

    def test_crops_small_vector_graph_tightly_when_question_has_visual_cue(self):
        pdf = pymupdf.open()
        page = pdf.new_page(width=300, height=400)
        page.insert_text(pymupdf.Point(20, 35), "Question 1")
        page.draw_polyline(
            [
                pymupdf.Point(80, 115),
                pymupdf.Point(120, 75),
                pymupdf.Point(160, 125),
            ],
            color=(0, 0, 0),
            width=2,
        )
        page.insert_text(pymupdf.Point(20, 220), "Question 2")

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            pdf_path = root / "vector_graph.pdf"
            pdf.save(pdf_path)
            pdf.close()
            questions = [{
                "question_number": 1,
                "stem": "Use the graph to answer.",
                "page_number": 1,
                "source_pages": [1],
                "quality": {"warnings": []},
            }]
            processor = QuestionVisualProcessor(
                parser=DocumentParser(),
                processed_dir=str(root / "processed"),
            )

            processor.attach_pdf_pages(
                document={"pages": [{"page": 1, "text": ""}]},
                pdf_path=str(pdf_path),
                document_id="VECTOR_TEST",
                questions=questions,
            )

            bbox = questions[0]["visual_assets"][0]["bbox"]
            self.assertLess(bbox[1], 75)
            self.assertGreater(bbox[3], 125)
            self.assertLess(bbox[3], 220)

    def test_merge_rects_rechecks_earlier_regions_after_union(self):
        rectangles = [
            pymupdf.Rect(0, 0, 10, 10),
            pymupdf.Rect(30, 0, 40, 10),
            pymupdf.Rect(15, 0, 25, 10),
        ]

        merged = QuestionVisualProcessor._merge_rects(rectangles)

        self.assertEqual(len(merged), 1)
        self.assertLessEqual(merged[0].x0, -6)
        self.assertGreaterEqual(merged[0].x1, 46)

    def test_does_not_attach_page_art_to_plain_passage_group(self):
        pdf = pymupdf.open()
        pdf.new_page(width=300, height=400)
        pdf.new_page(width=300, height=400)
        context_page = pdf[0]
        question_page = pdf[1]
        for y in range(80, 240, 20):
            context_page.draw_line(
                pymupdf.Point(30, y),
                pymupdf.Point(270, y),
                color=(0, 0, 0),
                width=0.5,
            )
        question_page.insert_text(pymupdf.Point(20, 40), "Question 1")
        question_page.insert_text(pymupdf.Point(20, 70), "Text-only question.")

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            pdf_path = root / "passage.pdf"
            pdf.save(pdf_path)
            pdf.close()
            questions = [{
                "question_number": 1,
                "stem": "Text-only question.",
                "page_number": 2,
                "source_pages": [1, 2],
                "group_id": "DOC_GROUP_001",
                "group_type": "passage",
                "passage": "A paragraph with words and sentences only.",
                "quality": {"warnings": []},
            }]
            processor = QuestionVisualProcessor(
                parser=DocumentParser(),
                processed_dir=str(root / "processed"),
            )

            processor.attach_pdf_pages(
                document={"pages": [
                    {"page": 1, "text": "A text-only passage."},
                    {"page": 2, "text": "Question 1 Text-only question."},
                ]},
                pdf_path=str(pdf_path),
                document_id="GROUP_TEST",
                questions=questions,
            )

            self.assertEqual(questions[0]["visual_assets"], [])


if __name__ == "__main__":
    unittest.main()