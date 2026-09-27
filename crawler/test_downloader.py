import tempfile
import unittest
import hashlib
from pathlib import Path
from unittest.mock import Mock

from crawler.common.document_downloader import DocumentDownloader


TEST_URL = "https://example.com/sample.pdf?download=1"


class DocumentDownloaderTests(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.downloader = DocumentDownloader(
            output_dir=self.temporary_directory.name,
            delay=0,
        )

    def make_response(self, content=b"%PDF-test", content_type="application/pdf"):
        response = Mock()
        response.content = content
        response.headers = {"Content-Type": content_type}
        return response

    def test_supported_url_with_query_string(self):
        self.assertTrue(self.downloader.is_supported(TEST_URL))
        self.assertEqual(
            self.downloader.get_extension(TEST_URL),
            ".pdf",
        )

    def test_download_saves_document(self):
        self.downloader.http.get = Mock(
            return_value=self.make_response()
        )

        result = self.downloader.download(TEST_URL, index=1)

        expected_path = Path(self.temporary_directory.name) / "document_0001.pdf"
        self.assertEqual(result["local_path"], str(expected_path))
        self.assertEqual(result["status"], "downloaded")
        self.assertEqual(result["file_size"], len(b"%PDF-test"))
        self.assertEqual(
            result["sha256"],
            hashlib.sha256(b"%PDF-test").hexdigest(),
        )
        self.assertEqual(expected_path.read_bytes(), b"%PDF-test")
        self.downloader.http.get.assert_called_once_with(TEST_URL)

    def test_content_type_detects_document_without_url_extension(self):
        response = self.make_response()
        response.url = "https://example.com/files/123"
        self.downloader.http.get = Mock(return_value=response)

        result = self.downloader.download(
            "https://example.com/download?id=123",
            index=2,
        )

        self.assertEqual(result["filename"], "document_0002.pdf")
        self.assertEqual(result["final_url"], response.url)
        self.assertEqual(result["content_type"], "application/pdf")

    def test_existing_file_is_not_overwritten(self):
        existing_path = (
            Path(self.temporary_directory.name) / "document_0001.pdf"
        )
        existing_path.write_bytes(b"original")
        self.downloader.http.get = Mock()

        result = self.downloader.download(TEST_URL, index=1)

        self.assertEqual(result["local_path"], str(existing_path))
        self.assertEqual(result["status"], "exists")
        self.assertEqual(existing_path.read_bytes(), b"original")
        self.downloader.http.get.assert_not_called()

    def test_html_response_is_rejected(self):
        self.downloader.http.get = Mock(
            return_value=self.make_response(
                content=b"<html>not a PDF</html>",
                content_type="text/html; charset=utf-8",
            )
        )

        result = self.downloader.download(TEST_URL, index=1)

        self.assertIsNone(result)
        self.assertEqual(
            list(Path(self.temporary_directory.name).iterdir()),
            [],
        )

    def test_unsupported_url_is_skipped(self):
        self.downloader.http.get = Mock()

        result = self.downloader.download(
            "https://example.com/page.html",
            index=1,
        )

        self.assertIsNone(result)
        self.downloader.http.get.assert_called_once_with(
            "https://example.com/page.html"
        )

    def test_download_many_returns_successful_files(self):
        urls = [
            "https://example.com/one.pdf",
            "https://example.com/page.html",
            "https://example.com/two.docx",
        ]

        def response_for(url):
            if url.endswith(".html"):
                return self.make_response(
                    content=b"<html>not a document</html>",
                    content_type="text/html; charset=utf-8",
                )
            if url.endswith(".docx"):
                return self.make_response(
                    content_type=(
                        "application/vnd.openxmlformats-officedocument."
                        "wordprocessingml.document"
                    )
                )
            return self.make_response()

        self.downloader.http.get = Mock(side_effect=response_for)

        results = self.downloader.download_many(urls)

        self.assertEqual(
            [item["filename"] for item in results],
            ["document_0001.pdf", "document_0003.docx"],
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
