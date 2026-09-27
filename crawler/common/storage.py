import json
from pathlib import Path


class JsonStorage:
    """
    Lưu dữ liệu crawler dưới dạng JSON.
    """

    def __init__(
        self,
        output_dir: str = "crawler/output"
    ):
        self.output_dir = Path(
            output_dir
        )

        self.output_dir.mkdir(
            parents=True,
            exist_ok=True
        )

    def save(
        self,
        data,
        filename: str
    ):
        """
        Lưu dữ liệu vào file JSON.
        """

        output_path = (
            self.output_dir / filename
        )

        with open(
            output_path,
            "w",
            encoding="utf-8"
        ) as file:

            json.dump(
                data,
                file,
                ensure_ascii=False,
                indent=2
            )

        print(
            f"[SAVED] {output_path}"
        )

        return output_path

    def load(
        self,
        filename: str
    ):
        """
        Đọc dữ liệu từ file JSON.
        """

        input_path = (
            self.output_dir / filename
        )

        if not input_path.exists():
            return None

        with open(
            input_path,
            "r",
            encoding="utf-8"
        ) as file:

            return json.load(file)