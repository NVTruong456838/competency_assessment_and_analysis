import os
import tempfile
from pathlib import Path

from crawler.common.document_utils import (
    calculate_sha256,
    get_file_extension,
    utc_now_iso,
)
from crawler.common.http_client import HttpClient


class DocumentDownloader:
    """
    Download supported documents into a local raw-data directory.

    Network access is delegated to HttpClient so SSL, retries, timeout, and
    rate limiting use the same configuration as the crawlers.
    """

    CONTENT_TYPE_EXTENSIONS = {
        "application/pdf": ".pdf",
        "application/msword": ".doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document": ".docx",
        "application/vnd.ms-excel": ".xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": ".xlsx",
    }

    ALLOWED_EXTENSIONS = frozenset({
        ".pdf",
        ".doc",
        ".docx",
        ".xls",
        ".xlsx",
    })

    def __init__(
        self,
        output_dir: str = "crawler/data/raw/tsa",
        delay: float = 1.0,
        timeout: int = 30,
        overwrite: bool = False,
    ):
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.overwrite = overwrite
        self.http = HttpClient(delay=delay, timeout=timeout)

    def get_extension(self, url: str) -> str:
        """Return the lowercase extension from a URL path."""

        return get_file_extension(url)

    def get_url_extension(self, url: str) -> str:
        """Return the lowercase extension from a URL path."""

        return get_file_extension(url)

    @staticmethod
    def get_content_type(response) -> str:
        """Return a normalized response Content-Type without parameters."""

        headers = getattr(response, "headers", {})
        content_type = headers.get("Content-Type", "")

        if not isinstance(content_type, str):
            return ""

        return content_type.split(";", 1)[0].strip().lower()

    def is_supported(self, url: str) -> bool:
        """Return whether the URL has a supported document extension."""

        return self.get_url_extension(url) in self.ALLOWED_EXTENSIONS

    def detect_extension(self, url: str, response) -> str | None:
        """Detect a document extension from Content-Type, then URL."""

        content_type = self.get_content_type(response)
        extension = self.CONTENT_TYPE_EXTENSIONS.get(content_type)

        if extension is not None:
            return extension

        url_extension = self.get_url_extension(url)

        if url_extension in self.ALLOWED_EXTENSIONS:
            return url_extension

        return None

    def create_filename(self, index: int, extension: str) -> str:
        """Create a deterministic local filename for a detected document."""

        extension = extension if extension else ".bin"
        return f"document_{index:04d}{extension}"

    def download(self, url: str, index: int):
        """
        Download one supported document.

        Returns metadata on success or when the file already exists.
        Returns None when the URL is unsupported, the request fails, or the
        response is empty/non-document content.
        """

        url_extension = self.get_url_extension(url)
        known_output = None

        if url_extension in self.ALLOWED_EXTENSIONS:
            known_output = self.output_dir / self.create_filename(
                index,
                url_extension,
            )

            if known_output.exists() and not self.overwrite:
                print(f"[EXISTS] {known_output}")
                return self._create_metadata(
                    url=url,
                    final_url=url,
                    output_path=known_output,
                    content_type="",
                    status="exists",
                )

        response = self.http.get(url)

        if response is None:
            return None

        content_type = self.get_content_type(response)
        content = response.content

        if "text/html" in content_type:
            print(f"[SKIP] HTML response: {url}")
            return None

        if not content:
            print(f"[SKIP] Empty response: {url}")
            return None

        extension = self.detect_extension(url, response)

        if extension is None:
            print(f"[SKIP] Unsupported content: {url}")
            print(f"       Content-Type: {content_type}")
            return None

        filename = self.create_filename(index, extension)
        output_path = self.output_dir / filename

        if output_path.exists() and not self.overwrite:
            print(f"[EXISTS] {output_path}")
            return self._create_metadata(
                url=url,
                final_url=self._get_final_url(response, url),
                output_path=output_path,
                content_type=content_type,
                status="exists",
            )

        try:
            self._write_atomic(output_path, content)
        except OSError as error:
            print(f"[FILE ERROR] {output_path}")
            print(f"             {error}")
            return None

        print(f"[DOWNLOADED] {output_path}")
        return self._create_metadata(
            url=url,
            final_url=self._get_final_url(response, url),
            output_path=output_path,
            content_type=content_type,
            status="downloaded",
        )

    def download_many(
        self,
        urls: list[str],
        start_index: int = 1,
    ) -> list[dict]:
        """Download multiple URLs and return successful metadata records."""

        downloaded = []

        for index, url in enumerate(urls, start=start_index):
            metadata = self.download(url, index)
            if metadata is not None:
                downloaded.append(metadata)

        return downloaded

    @staticmethod
    def _create_metadata(
        url: str,
        final_url: str,
        output_path: Path,
        content_type: str,
        status: str,
    ) -> dict:
        return {
            "source_url": url,
            "final_url": final_url,
            "local_path": str(output_path),
            "filename": output_path.name,
            "content_type": content_type,
            "file_size": output_path.stat().st_size,
            "sha256": calculate_sha256(str(output_path)),
            "downloaded_at": utc_now_iso(),
            "status": status,
        }

    @staticmethod
    def _get_final_url(response, fallback_url: str) -> str:
        final_url = getattr(response, "url", None)
        return final_url if isinstance(final_url, str) and final_url else fallback_url

    @staticmethod
    def _write_atomic(output_path: Path, content: bytes) -> None:
        """Write bytes through a temporary file in the destination directory."""

        temporary_path = None

        try:
            with tempfile.NamedTemporaryFile(
                mode="wb",
                dir=output_path.parent,
                prefix=f".{output_path.name}.",
                suffix=".tmp",
                delete=False,
            ) as temporary_file:
                temporary_file.write(content)
                temporary_path = Path(temporary_file.name)

            os.replace(temporary_path, output_path)
        finally:
            if temporary_path is not None and temporary_path.exists():
                temporary_path.unlink()
