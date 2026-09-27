import json
import os
import tempfile
from pathlib import Path
from typing import Any


class ProcessedStorage:
    """
    Store processed crawler data as UTF-8 JSON files.

    The default directory is dedicated to processed TSA documents. Writes are
    atomic so an interrupted process cannot leave a partially written JSON.
    """

    def __init__(
        self,
        output_dir: str = "crawler/data/processed/tsa",
    ):
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)

    def save(
        self,
        data: Any,
        filename: str,
    ) -> Path:
        """Serialize data to a safe JSON filename and return its path."""

        output_path = self._get_path(filename)
        temporary_path = None

        try:
            with tempfile.NamedTemporaryFile(
                mode="w",
                encoding="utf-8",
                dir=self.output_dir,
                prefix=f".{output_path.name}.",
                suffix=".tmp",
                delete=False,
            ) as temporary_file:
                json.dump(
                    data,
                    temporary_file,
                    ensure_ascii=False,
                    indent=2,
                )
                temporary_file.write("\n")
                temporary_path = Path(temporary_file.name)

            os.replace(temporary_path, output_path)
        finally:
            if temporary_path is not None and temporary_path.exists():
                temporary_path.unlink()

        print(f"[PROCESSED SAVED] {output_path}")
        return output_path

    def load(self, filename: str) -> Any | None:
        """Load a processed JSON file, or return None when it is missing."""

        input_path = self._get_path(filename)

        if not input_path.exists():
            return None

        with input_path.open("r", encoding="utf-8") as file:
            return json.load(file)

    def exists(self, filename: str) -> bool:
        """Return whether a processed JSON file exists."""

        return self._get_path(filename).is_file()

    def list_files(self) -> list[Path]:
        """Return processed JSON files sorted by filename."""

        return sorted(self.output_dir.glob("*.json"))

    def _get_path(self, filename: str) -> Path:
        path = Path(filename)

        if path.name != filename or path.suffix.lower() != ".json":
            raise ValueError(
                "filename must be a JSON filename in the storage directory"
            )

        return self.output_dir / path
