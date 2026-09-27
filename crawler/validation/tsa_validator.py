import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any

from pypdf import PdfReader


class TsaValidator:
    """Validate processed TSA HTML and PDF documents."""

    REQUIRED_FIELDS = (
        "document_id",
        "source",
        "source_url",
        "sha256",
        "status",
    )

    def __init__(
        self,
        input_file: str = "crawler/output/tsa_processed_documents.json",
        report_file: str = "crawler/output/tsa_validation_report.json",
        dataset_name: str = "HUST_TSA",
    ):
        self.input_file = Path(input_file)
        self.report_file = Path(report_file)
        self.dataset_name = dataset_name
        self.report_file.parent.mkdir(parents=True, exist_ok=True)

    def load_documents(self) -> list[dict[str, Any]]:
        """Load the processed-document summary list."""

        if not self.input_file.exists():
            raise FileNotFoundError(f"File not found: {self.input_file}")

        with self.input_file.open("r", encoding="utf-8") as file:
            data = json.load(file)

        if not isinstance(data, list):
            raise ValueError("Processed data must be a JSON list")

        return data

    @classmethod
    def validate_required_fields(
        cls,
        document: dict[str, Any],
    ) -> list[str]:
        """Return missing fields required for every processed record."""

        missing = [
            field
            for field in cls.REQUIRED_FIELDS
            if not document.get(field)
        ]

        if not document.get("processed_path"):
            missing.append("processed_path")

        if document.get("status") == "processed":
            if not document.get("local_path"):
                missing.append("local_path")

        return missing

    @staticmethod
    def validate_pdf(file_path: str | None) -> dict[str, Any]:
        """Validate a PDF file and report page/text statistics."""

        result = {
            "valid_pdf": False,
            "page_count": 0,
            "text_char_count": 0,
            "has_text": False,
            "error": None,
        }

        if not file_path:
            result["error"] = "no_local_pdf"
            return result

        path = Path(file_path)

        if not path.is_file():
            result["error"] = "file_not_found"
            return result

        try:
            reader = PdfReader(str(path))
            text_parts = []

            for page in reader.pages:
                try:
                    text_parts.append(page.extract_text() or "")
                except Exception:
                    text_parts.append("")

            full_text = "\n".join(text_parts).strip()
            result.update({
                "valid_pdf": True,
                "page_count": len(reader.pages),
                "text_char_count": len(full_text),
                "has_text": bool(full_text),
            })
        except Exception as error:
            result["error"] = str(error)

        return result

    @staticmethod
    def load_processed_document(
        processed_path: str | None,
    ) -> dict[str, Any] | None:
        """Load the detailed processed artifact referenced by a summary row."""

        if not processed_path:
            return None

        path = Path(processed_path)

        if not path.is_file():
            return None

        try:
            with path.open("r", encoding="utf-8") as file:
                data = json.load(file)
        except (OSError, json.JSONDecodeError):
            return None

        return data if isinstance(data, dict) else None

    @staticmethod
    def calculate_file_sha256(file_path: str | None) -> str | None:
        """Calculate a file hash when a local file is available."""

        if not file_path or not Path(file_path).is_file():
            return None

        digest = hashlib.sha256()
        with Path(file_path).open("rb") as file:
            for chunk in iter(lambda: file.read(1024 * 1024), b""):
                digest.update(chunk)

        return digest.hexdigest()

    def validate_documents(
        self,
        documents: list[dict[str, Any]],
    ) -> list[dict[str, Any]]:
        """Validate each summary row and its processed artifact."""

        url_counter = Counter(
            document.get("source_url", "")
            for document in documents
            if document.get("source_url")
        )
        hash_counter = Counter(
            document.get("sha256", "")
            for document in documents
            if document.get("sha256")
        )
        results = []

        for document in documents:
            processed_path = document.get("processed_path")
            processed = self.load_processed_document(processed_path)
            content_type = (processed or {}).get("content_type", "")
            local_path = document.get("local_path")
            is_pdf = content_type == "pdf" or bool(local_path)
            pdf_result = self.validate_pdf(local_path) if is_pdf else {
                "valid_pdf": False,
                "page_count": 0,
                "text_char_count": len((processed or {}).get("content", "")),
                "has_text": bool((processed or {}).get("content", "").strip()),
                "error": None,
            }
            missing_fields = self.validate_required_fields(document)
            processed_file_exists = bool(
                processed_path and Path(processed_path).is_file()
            )
            raw_file_exists = bool(local_path and Path(local_path).is_file())
            content_valid = bool(
                processed
                and isinstance(processed.get("content"), str)
                and processed.get("content", "").strip()
            )
            hash_matches = (
                True
                if not local_path
                else self.calculate_file_sha256(local_path) == document.get("sha256")
            )
            duplicate_url = url_counter[document.get("source_url", "")] > 1
            duplicate_file = hash_counter[document.get("sha256", "")] > 1

            result = {
                "document_id": document.get("document_id", ""),
                "source_url": document.get("source_url", ""),
                "local_path": local_path,
                "processed_path": processed_path,
                "content_type": content_type,
                "missing_fields": missing_fields,
                "raw_file_exists": raw_file_exists,
                "processed_file_exists": processed_file_exists,
                "hash_matches": hash_matches,
                "valid_pdf": pdf_result["valid_pdf"],
                "page_count": pdf_result["page_count"],
                "text_char_count": pdf_result["text_char_count"],
                "has_text": pdf_result["has_text"],
                "content_valid": content_valid,
                "pdf_error": pdf_result["error"],
                "duplicate_url": duplicate_url,
                "duplicate_file": duplicate_file,
            }

            result["valid"] = bool(
                not missing_fields
                and processed_file_exists
                and content_valid
                and hash_matches
                and (not is_pdf or pdf_result["valid_pdf"])
            )
            results.append(result)

        return results

    @staticmethod
    def create_summary(results: list[dict[str, Any]]) -> dict[str, Any]:
        """Create aggregate quality metrics from validation results."""

        return {
            "total_documents": len(results),
            "valid_documents": sum(result["valid"] for result in results),
            "invalid_documents": sum(not result["valid"] for result in results),
            "valid_pdf_documents": sum(result["valid_pdf"] for result in results),
            "documents_with_text": sum(result["has_text"] for result in results),
            "image_only_or_scanned_pdf": sum(
                result["valid_pdf"] and not result["has_text"]
                for result in results
            ),
            "duplicate_urls": sum(result["duplicate_url"] for result in results),
            "duplicate_files": sum(result["duplicate_file"] for result in results),
            "total_pages": sum(result["page_count"] for result in results),
            "total_text_characters": sum(
                result["text_char_count"]
                for result in results
            ),
        }

    def save_report(
        self,
        summary: dict[str, Any],
        results: list[dict[str, Any]],
    ) -> Path:
        """Save the validation report as UTF-8 JSON."""

        report = {
            "dataset": self.dataset_name,
            "validation": {
                "summary": summary,
                "documents": results,
            },
        }

        with self.report_file.open("w", encoding="utf-8") as file:
            json.dump(report, file, ensure_ascii=False, indent=2)
            file.write("\n")

        print(f"[REPORT SAVED] {self.report_file}")
        return self.report_file

    def run(self) -> dict[str, Any]:
        """Validate all processed documents and save the report."""

        documents = self.load_documents()
        results = self.validate_documents(documents)
        summary = self.create_summary(results)
        self.save_report(summary, results)

        print("=" * 60)
        print(f"{self.dataset_name} DATA VALIDATION")
        print("=" * 60)
        print(f"Total documents       : {summary['total_documents']}")
        print(f"Valid documents       : {summary['valid_documents']}")
        print(f"Invalid documents     : {summary['invalid_documents']}")
        print(f"Valid PDF             : {summary['valid_pdf_documents']}")
        print(f"Documents with text   : {summary['documents_with_text']}")
        print(f"Duplicate URL         : {summary['duplicate_urls']}")
        print(f"Duplicate file        : {summary['duplicate_files']}")

        return {
            "summary": summary,
            "documents": results,
        }


if __name__ == "__main__":
    TsaValidator().run()
