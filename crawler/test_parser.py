import io
import tempfile
import unittest
from pathlib import Path

import pymupdf
from pypdf import PdfWriter

from crawler.common.document_parser import DocumentParser


HTML_FIXTURE = """
<html>
    <head>
        <title>TSA - Tài liệu tham khảo</title>
    </head>
    <body>
        <h1>Đánh giá tư duy TSA</h1>
        <p>Đây là nội dung tham khảo.</p>
        <p>Phần tư duy toán học.</p>
        <script>ignore_this_script()</script>
        <a href="/documents/guide.pdf#page=1">Tài liệu PDF</a>
    </body>
</html>
"""


class DocumentParserTests(unittest.TestCase):
    def setUp(self):
        self.parser = DocumentParser()
        self.url = "https://ts.hust.edu.vn/example/"

    def test_parse_html_reference_document(self):
        document = self.parser.parse_html(
            html=HTML_FIXTURE,
            url=self.url,
            document_type="reference",
        )

        self.assertEqual(document["title"], "TSA - Tài liệu tham khảo")
        self.assertEqual(document["source"], "ts.hust.edu.vn")
        self.assertEqual(document["source_url"], self.url.rstrip("/"))
        self.assertEqual(document["document_type"], "reference")
        self.assertEqual(document["content_type"], "html")
        self.assertIn("Đánh giá tư duy TSA", document["content"])
        self.assertIn("Phần tư duy toán học", document["content"])
        self.assertNotIn("ignore_this_script", document["content"])
        self.assertEqual(
            document["links"],
            ["https://ts.hust.edu.vn/documents/guide.pdf"],
        )

    def test_parse_html_legacy_argument_order(self):
        document = self.parser.parse_html(
            self.url,
            HTML_FIXTURE,
            "HUST_TSA",
        )

        self.assertEqual(document["source"], "HUST_TSA")
        self.assertEqual(document["content_type"], "html")

    def test_parse_pdf_bytes(self):
        stream = io.BytesIO()
        writer = PdfWriter()
        writer.add_blank_page(width=72, height=72)
        writer.write(stream)

        document = self.parser.parse_pdf(
            content=stream.getvalue(),
            url="https://ts.hust.edu.vn/example.pdf",
            title="Tài liệu TSA",
        )

        self.assertEqual(document["content_type"], "pdf")
        self.assertEqual(document["page_count"], 1)
        self.assertEqual(len(document["pages"]), 1)
        self.assertEqual(document["title"], "Tài liệu TSA")

    def test_parse_local_pdf_file(self):
        stream = io.BytesIO()
        writer = PdfWriter()
        writer.add_blank_page(width=72, height=72)
        writer.write(stream)

        with tempfile.TemporaryDirectory() as directory:
            file_path = Path(directory) / "tsa-reference.pdf"
            file_path.write_bytes(stream.getvalue())

            document = self.parser.parse_file(str(file_path))

        self.assertEqual(document["content_type"], "pdf")
        self.assertEqual(document["title"], "tsa-reference")
        self.assertEqual(document["page_count"], 1)

    def test_renders_selected_pdf_page_as_png(self):
        stream = io.BytesIO()
        writer = PdfWriter()
        writer.add_blank_page(width=72, height=72)
        writer.write(stream)

        with tempfile.TemporaryDirectory() as directory:
            pdf_path = Path(directory) / "visual-test.pdf"
            pdf_path.write_bytes(stream.getvalue())
            rendered = self.parser.render_pdf_pages(
                file_path=str(pdf_path),
                page_numbers={1, 2},
                output_dir=str(Path(directory) / "visuals"),
                filename_prefix="TEST_DOC",
            )

            self.assertEqual(set(rendered), {1})
            self.assertTrue(Path(rendered[1]).is_file())
            self.assertEqual(Path(rendered[1]).read_bytes()[:8], b"\x89PNG\r\n\x1a\n")

    def test_inspects_question_coordinates_and_filters_tiny_header_image(self):
        pdf = pymupdf.open()
        page = pdf.new_page(width=200, height=200)
        small_image = pymupdf.Pixmap(
            pymupdf.csRGB,
            pymupdf.IRect(0, 0, 5, 5),
            0,
        )
        large_image = pymupdf.Pixmap(
            pymupdf.csRGB,
            pymupdf.IRect(0, 0, 50, 50),
            0,
        )
        small_image.clear_with(128)
        large_image.clear_with(255)
        page.insert_image(pymupdf.Rect(5, 5, 10, 10), pixmap=small_image)
        page.insert_image(pymupdf.Rect(50, 60, 100, 110), pixmap=large_image)
        page.insert_text(pymupdf.Point(15, 40), "Cau hoi 07")

        with tempfile.TemporaryDirectory() as directory:
            pdf_path = Path(directory) / "layout.pdf"
            pdf.save(pdf_path)
            pdf.close()

            layout = self.parser.inspect_pdf_visual_layout(str(pdf_path))[1]
            normalized_heading = self.parser._normalize_question_heading_text(
                "Câu hỏi 07"
            )
            self.assertEqual(
                int(self.parser.QUESTION_HEADING_PATTERN.match(normalized_heading).group(1)),
                7,
            )
            self.assertEqual(
                [heading["question_number"] for heading in layout["question_headings"]],
                [7],
            )
            self.assertEqual(len(layout["image_rects"]), 1)
            self.assertGreater(layout["text_character_count"], 0)

            crop_path = self.parser.render_pdf_region(
                file_path=str(pdf_path),
                page_number=1,
                rect=(48, 58, 102, 112),
                output_dir=str(Path(directory) / "crops"),
                filename="question_007.png",
            )
            with pymupdf.open(crop_path) as crop:
                self.assertEqual(len(crop), 1)
                self.assertLess(crop[0].rect.width, 200)


if __name__ == "__main__":
    unittest.main(verbosity=2)
