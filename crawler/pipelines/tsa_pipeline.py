import hashlib
import argparse
from pathlib import Path
from typing import Any

from crawler.common.document_downloader import DocumentDownloader
from crawler.common.document_parser import DocumentParser
from crawler.common.document_utils import calculate_sha256, utc_now_iso
from crawler.common.http_client import HttpClient
from crawler.common.processed_storage import ProcessedStorage
from crawler.common.question_extractor import QuestionExtractor
from crawler.common.question_normalizer import QuestionNormalizer
from crawler.common.question_visuals import QuestionVisualProcessor
from crawler.common.storage import JsonStorage


class TsaPipeline:
    """
    Process TSA source metadata through download, parse, and storage stages.

    Source metadata is read from crawler/output. Raw documents are stored in
    crawler/data/raw/tsa, while each processed document is stored as JSON in
    crawler/data/processed/tsa.
    """

    SOURCE_NAME = "HUST_TSA"

    def __init__(
        self,
        metadata_dir: str = "crawler/output",
        raw_dir: str = "crawler/data/raw/tsa",
        processed_dir: str = "crawler/data/processed/tsa",
    ):
        self.metadata_storage = JsonStorage(output_dir=metadata_dir)
        self.downloader = DocumentDownloader(output_dir=raw_dir)
        self.http = HttpClient(delay=1.0, timeout=30)
        self.parser = DocumentParser()
        self.question_extractor = QuestionExtractor()
        self.question_normalizer = QuestionNormalizer()
        self.question_visuals = QuestionVisualProcessor(
            parser=self.parser,
            processed_dir=processed_dir,
        )
        self.processed_storage = ProcessedStorage(
            output_dir=processed_dir,
        )
        self.extracted_questions = []

    def load_source_documents(self) -> list[dict[str, Any]]:
        """Load crawled TSA metadata, returning an empty list when absent."""

        documents = self.metadata_storage.load("tsa_documents.json")

        if not documents:
            return []
        if not isinstance(documents, list):
            raise ValueError("tsa_documents.json must contain a JSON list")

        return documents

    def process_documents(
        self,
        documents: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        """Download and process supported documents one by one."""

        processed = []
        document_index = 1

        for document in documents:
            url = document.get("url")

            if not url:
                continue

            if not self.downloader.is_supported(url):
                html_result = self._process_html_document(
                    document,
                    document_index,
                )

                if html_result is not None:
                    processed.append(html_result)
                    document_index += 1

                continue

            print()
            print("=" * 60)
            print(f"Processing: {url}")

            download_info = self.downloader.download(
                url=url,
                index=document_index,
            )
            document_index += 1

            if download_info is None:
                continue

            local_path = Path(download_info["local_path"])
            document_id = f"HUST_TSA_{document_index - 1:06d}"

            try:
                parsed = self._parse_document(
                    document=document,
                    local_path=local_path,
                    url=url,
                )
            except Exception as error:
                print(f"[PARSE ERROR] {url}")
                print(f"              {error}")
                continue

            parsed.update({
                "document_id": document_id,
                "exam": "TSA",
                "local_path": str(local_path),
                "sha256": download_info["sha256"],
                "downloaded_at": download_info["downloaded_at"],
            })
            questions = self.question_extractor.extract_from_document(parsed)
            self.question_visuals.attach_pdf_pages(
                parsed,
                str(local_path),
                document_id,
                questions,
            )
            self.extracted_questions.extend(questions)

            processed_filename = f"{document_id}.json"
            processed_path = self.processed_storage.save(
                parsed,
                processed_filename,
            )

            processed.append({
                "document_id": document_id,
                "source": document.get("source", self.SOURCE_NAME),
                "source_url": url,
                "title": document.get("title", ""),
                "document_type": document.get("document_type", "other"),
                "local_path": str(local_path),
                "processed_path": str(processed_path),
                "sha256": download_info["sha256"],
                "status": "processed",
            })

        return processed

    def _process_html_document(
        self,
        document: dict[str, Any],
        document_index: int,
    ) -> dict[str, Any] | None:
        """Fetch and process a source record that points to an HTML page."""

        url = document["url"]
        response = self.http.get(url)

        if response is None:
            return None

        try:
            parsed = self.parser.parse_response(
                response=response,
                url=url,
                title=document.get("title", ""),
                document_type=document.get("document_type", "other"),
                source=document.get("source", self.SOURCE_NAME),
            )
        except Exception as error:
            print(f"[PARSE ERROR] {url}")
            print(f"              {error}")
            return None

        if parsed is None:
            return None

        document_id = f"HUST_TSA_{document_index:06d}"
        content_hash = hashlib.sha256(response.content).hexdigest()
        parsed.update({
            "document_id": document_id,
            "exam": "TSA",
            "sha256": content_hash,
            "downloaded_at": utc_now_iso(),
        })
        self.extracted_questions.extend(
            self.question_extractor.extract_from_document(parsed)
        )

        processed_path = self.processed_storage.save(
            parsed,
            f"{document_id}.json",
        )

        return {
            "document_id": document_id,
            "source": document.get("source", self.SOURCE_NAME),
            "source_url": url,
            "title": document.get("title", ""),
            "document_type": document.get("document_type", "other"),
            "local_path": None,
            "processed_path": str(processed_path),
            "sha256": content_hash,
            "status": "processed_html",
        }

    def run(self) -> list[dict[str, Any]]:
        """Run the complete TSA pipeline and save its summary metadata."""

        documents = self.load_source_documents()

        if not documents:
            print("[ERROR] Không tìm thấy tài liệu TSA để xử lý")
            return []

        print(f"[INFO] Found {len(documents)} source records")
        processed = self.process_documents(documents)

        self.metadata_storage.save(
            processed,
            "tsa_processed_documents.json",
        )
        self.processed_storage.save(
            self.extracted_questions,
            "tsa_extracted_questions.json",
        )
        self.processed_storage.save(
            self.question_normalizer.normalize_many(self.extracted_questions),
            "tsa_question_bank.json",
        )

        print()
        print("=" * 60)
        print(f"Processed: {len(processed)}")
        print(f"Extracted questions: {len(self.extracted_questions)}")

        return processed

    def run_local_files(
        self,
        raw_dir: str | None = None,
    ) -> list[dict[str, Any]]:
        """Process PDF files already present in the local raw directory."""

        input_dir = Path(raw_dir or self.downloader.output_dir)
        files = sorted(input_dir.glob("*.pdf"))
        self.extracted_questions = []
        processed = []

        if not files:
            print(f"[INFO] No PDF files found in {input_dir}")
            return processed

        print(f"[INFO] Found {len(files)} local PDF files")

        for index, file_path in enumerate(files, start=1):
            absolute_path = file_path.resolve()
            document_id = f"{self.SOURCE_NAME}_LOCAL_{index:06d}"

            try:
                parsed = self.parser.parse_file(
                    file_path=str(absolute_path),
                    url=absolute_path.as_uri(),
                    title=file_path.stem,
                    source=self.SOURCE_NAME,
                )
                file_hash = calculate_sha256(str(file_path))
                parsed.update({
                    "document_id": document_id,
                    "exam": "TSA",
                    "local_path": str(absolute_path),
                    "sha256": file_hash,
                    "downloaded_at": utc_now_iso(),
                })
                questions = self.question_extractor.extract_from_document(parsed)
                self.question_visuals.attach_pdf_pages(
                    parsed,
                    str(absolute_path),
                    document_id,
                    questions,
                )
                self.extracted_questions.extend(questions)
                processed_path = self.processed_storage.save(
                    parsed,
                    f"{document_id}.json",
                )
            except Exception as error:
                print(f"[PROCESS ERROR] {file_path}")
                print(f"               {error}")
                continue

            processed.append({
                "document_id": document_id,
                "source": self.SOURCE_NAME,
                "source_url": absolute_path.as_uri(),
                "title": file_path.stem,
                "document_type": parsed["document_type"],
                "local_path": str(absolute_path),
                "processed_path": str(processed_path),
                "sha256": file_hash,
                "status": "processed_local",
            })

        self.metadata_storage.save(
            processed,
            "tsa_local_processed_documents.json",
        )
        self.processed_storage.save(
            self.extracted_questions,
            "tsa_extracted_questions.json",
        )
        self.processed_storage.save(
            self.question_normalizer.normalize_many(self.extracted_questions),
            "tsa_question_bank.json",
        )

        print(f"[INFO] Local documents processed: {len(processed)}")
        print(f"[INFO] Questions extracted: {len(self.extracted_questions)}")
        return processed

    def _parse_document(
        self,
        document: dict[str, Any],
        local_path: Path,
        url: str,
    ) -> dict[str, Any]:
        """Parse one downloaded document using its file extension."""

        if local_path.suffix.lower() != ".pdf":
            raise ValueError(
                f"Chưa hỗ trợ parse {local_path.suffix.lower()}"
            )

        return self.parser.parse_file(
            file_path=str(local_path),
            url=url,
            title=document.get("title", ""),
            document_type=document.get("document_type", "other"),
            source=document.get("source", self.SOURCE_NAME),
        )


if __name__ == "__main__":
    argument_parser = argparse.ArgumentParser(
        description="Run the TSA crawl or local PDF processing pipeline."
    )
    argument_parser.add_argument(
        "--local",
        action="store_true",
        help="Process PDFs from crawler/data/raw/tsa.",
    )
    arguments = argument_parser.parse_args()
    pipeline = TsaPipeline()

    if arguments.local:
        pipeline.run_local_files()
    else:
        pipeline.run()
