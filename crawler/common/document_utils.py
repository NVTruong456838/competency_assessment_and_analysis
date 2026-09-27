import hashlib
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse


def calculate_sha256(file_path: str) -> str:
    """Return the SHA-256 digest of a file."""

    sha256 = hashlib.sha256()

    with Path(file_path).open("rb") as file:
        while chunk := file.read(1024 * 1024):
            sha256.update(chunk)

    return sha256.hexdigest()


def get_file_extension(url: str) -> str:
    """Return the lowercase extension from a URL path."""

    return Path(urlparse(url).path).suffix.lower()


def utc_now_iso() -> str:
    """Return the current UTC time in ISO 8601 format."""

    return datetime.now(timezone.utc).isoformat()
