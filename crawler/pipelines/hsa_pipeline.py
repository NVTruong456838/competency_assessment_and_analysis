import argparse
from pathlib import Path
from typing import Any

from crawler.common.document_downloader import DocumentDownloader
from crawler.common.document_parser import DocumentParser
from crawler.common.document_utils import calculate_sha256, utc_now_iso
from crawler.common.processed_storage import ProcessedStorage
from crawler.common.question_extractor import QuestionExtractor
from crawler.common.question_normalizer import QuestionNormalizer
from crawler.common.question_visuals import QuestionVisualProcessor
from crawler.common.storage import JsonStorage


class HsaPipeline:
    """Download and extract questions from public or local HSA PDFs."""

    SOURCE_NAME = "VNU_HSA"

    def __init__(
        self,
        metadata_dir: str = "crawler/output",
        raw_dir: str = "crawler/data/raw/hsa",
        processed_dir: str = "crawler/data/processed/hsa",
    ):
        self.metadata_storage = JsonStorage(output_dir=metadata_dir)
        self.downloader = DocumentDownloader(output_dir=raw_dir)
        self.parser = DocumentParser()
        self.question_extractor = QuestionExtractor()
        self.question_normalizer = QuestionNormalizer()
        self.question_visuals = QuestionVisualProcessor(
            parser=self.parser,
            processed_dir=processed_dir,
        )
        self.processed_storage = ProcessedStorage(output_dir=processed_dir)
        self.extracted_questions = []

    def load_source_documents(self) -> list[dict[str, Any]]:
        documents = self.metadata_storage.load("hsa_documents.json")
        if not documents:
            return []
        if not isinstance(documents, list):
            raise ValueError("hsa_documents.json must contain a JSON list")
        return documents

    def run(self, rights_cleared: bool = False) -> list[dict[str, Any]]:
        """Download metadata-listed public documents and save TSA-like outputs."""

        if not rights_cleared:
            print(
                "[BLOCKED] Confirm authorization before downloading HSA exam content."
            )
            return []

        documents = self.load_source_documents()
        processed = []
        self.extracted_questions = []

        for index, document in enumerate(documents, start=1):
            url = document.get("url")
            if not url or not self.downloader.is_supported(url):
                continue

            download_info = self.downloader.download(url, index)
            if download_info is None:
                continue

            local_path = Path(download_info["local_path"])
            document_id = f"{self.SOURCE_NAME}_{index:06d}"
            try:
                parsed = self.parser.parse_file(
                    file_path=str(local_path),
                    url=url,
                    title=document.get("title", ""),
                    document_type=document.get("document_type", "sample_exam"),
                    source=document.get("source", self.SOURCE_NAME),
                )
                parsed.update({
                    "document_id": document_id,
                    "exam": "HSA",
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
                processed_path = self.processed_storage.save(
                    parsed,
                    f"{document_id}.json",
                )
            except Exception as error:
                print(f"[PROCESS ERROR] {url}")
                print(f"               {error}")
                continue

            processed.append(self._summary_record(
                document=document,
                document_id=document_id,
                url=url,
                local_path=str(local_path),
                processed_path=str(processed_path),
                sha256=download_info["sha256"],
                status="processed",
            ))

        self._save_outputs(processed, "hsa_processed_documents.json")
        return processed

    def run_local_files(
        self,
        raw_dir: str | None = None,
    ) -> list[dict[str, Any]]:
        """Process locally supplied HSA PDFs without network access."""

        input_dir = Path(raw_dir or self.downloader.output_dir)
        files = sorted(input_dir.glob("*.pdf"))
        self.extracted_questions = []
        processed = []

        if not files:
            print(f"[INFO] No PDF files found in {input_dir}")
            return processed

        for index, file_path in enumerate(files, start=1):
            absolute_path = file_path.resolve()
            document_id = f"{self.SOURCE_NAME}_LOCAL_{index:06d}"
            try:
                parsed = self.parser.parse_file(
                    file_path=str(absolute_path),
                    url=absolute_path.as_uri(),
                    title=file_path.stem,
                    document_type="sample_exam",
                    source=self.SOURCE_NAME,
                )
                file_hash = calculate_sha256(str(absolute_path))
                parsed.update({
                    "document_id": document_id,
                    "exam": "HSA",
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

        self._save_outputs(processed, "hsa_local_processed_documents.json")
        print(f"[INFO] Local HSA documents processed: {len(processed)}")
        print(f"[INFO] Questions extracted: {len(self.extracted_questions)}")
        return processed

    def _save_outputs(
        self,
        processed: list[dict[str, Any]],
        summary_filename: str,
    ) -> None:
        self.metadata_storage.save(processed, summary_filename)
        self.processed_storage.save(
            self.extracted_questions,
            "hsa_extracted_questions.json",
        )
        self.processed_storage.save(
            self.question_normalizer.normalize_many(self.extracted_questions),
            "hsa_question_bank.json",
        )

    @staticmethod
    def _summary_record(
        document: dict[str, Any],
        document_id: str,
        url: str,
        local_path: str,
        processed_path: str,
        sha256: str,
        status: str,
    ) -> dict[str, Any]:
        return {
            "document_id": document_id,
            "source": document.get("source", HsaPipeline.SOURCE_NAME),
            "source_url": url,
            "title": document.get("title", ""),
            "document_type": document.get("document_type", "sample_exam"),
            "local_path": local_path,
            "processed_path": processed_path,
            "sha256": sha256,
            "status": status,
        }


if __name__ == "__main__":
    argument_parser = argparse.ArgumentParser(
        description="Run the HSA public-document or local PDF pipeline."
    )
    argument_parser.add_argument(
        "--local",
        action="store_true",
        help="Process PDFs from crawler/data/raw/hsa.",
    )
    argument_parser.add_argument(
        "--rights-cleared",
        action="store_true",
        help="Confirm authorization to download and process listed exam documents.",
    )
    arguments = argument_parser.parse_args()
    pipeline = HsaPipeline()

    if arguments.local:
        pipeline.run_local_files()
    else:
        pipeline.run(rights_cleared=arguments.rights_cleared)