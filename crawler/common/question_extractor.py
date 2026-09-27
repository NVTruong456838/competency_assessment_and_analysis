import re
from typing import Any, Iterable


class QuestionExtractor:
    """Extract raw question content without making assessment mappings."""

    QUESTION_START_PATTERN = re.compile(
        r"(?i)^\s*(?:câu(?:\s+hỏi)?|cau(?:\s+hoi)?|question)"
        r"\s*(\d+)\s*(?:[:.)-]\s*|\s+|$)"
    )
    OPTION_PATTERN = re.compile(
        r"(?<![A-Za-zÀ-ỹ0-9])([A-D])\s*[.):]\s*",
        re.IGNORECASE,
    )
    ANSWER_MARKER_PATTERN = re.compile(
        r"(?i)\s*(?:đáp án|dap an|answer)\s*:\s*"
    )
    ANSWER_LIST_PATTERN = re.compile(
        r"(?i)^\s*[A-D](?:\s*(?:,|&|and|và)\s*[A-D])+\s*$"
    )
    UNKNOWN_ANSWER_VALUES = {
        "không xác định",
        "chưa xác định",
        "không rõ",
        "unknown",
        "n/a",
    }
    MULTI_SELECT_PATTERN = re.compile(
        r"(?i)(?:chọn\s+(?:hai|2|nhiều|các)\s+đáp\s+án(?:\s+đúng)?|"
        r"chọn\s+các\s+đáp\s+án\s+đúng|"
        r"có\s+thể\s+chọn\s+nhiều\s+đáp\s+án|"
        r"chọn\s+(?:hai|2|nhiều|các)\s+phương\s+án(?:\s+đúng)?|"
        r"có\s+hai\s+đáp\s+án\s+đúng|"
        r"choose\s+all\s+that\s+apply|"
        r"choose\s+two\s+answers|"
        r"select\s+all\s+that\s+apply|"
        r"chon\s+(?:hai|2|nhieu|cac)\s+dap\s+an(?:\s+dung)?|"
        r"co\s+the\s+chon\s+nhieu\s+dap\s+an)"
    )
    PASSAGE_START_PATTERN = re.compile(
        r"(?i)\b(?:đọc|xem|dựa vào|sử dụng|theo)\s+"
        r"(văn bản|đoạn văn|đoạn trích|bảng|biểu đồ|sơ đồ|ngữ liệu|thông tin)\s+"
        r"(?:sau|dưới đây)\b"
    )
    PASSAGE_QUESTION_RANGE_PATTERN = re.compile(
        r"(?i)^(?:đọc|xem|dựa vào|sử dụng|theo|read|based on|use)\b.*?"
        r"(?:trả lời\s+)?(?:các\s+)?(?:câu hỏi|questions?)\s+"
        r"(?:(?:từ|from)\s+)?\d+\s+(?:đến|to)\s+(\d+)\b"
    )
    FOOTNOTE_PATTERN = re.compile(
        r"(?:^|\s+)\d+\s+(?:tức|nghĩa là)\s+.*$",
        re.IGNORECASE,
    )
    COGNITIVE_LEVEL_PATTERN = re.compile(r"^\s*\((?:M[1-3]|m[1-3])\)\s*")
    VISUAL_REFERENCE_PATTERN = re.compile(
        r"(?i)\b(?:như\s+hình|dựa\s+vào\s+hình|quan\s+sát\s+hình|"
        r"hình\s+(?:\d+|vẽ|minh\s+họa|minh\s+hoạ|bên|sau|trên|dưới)|"
        r"đồ\s+thị\s+(?:sau|dưới|bên|như|cho\s+trong)|"
        r"biểu\s+đồ|bảng(?:\s+số\s+liệu)?|sơ\s+đồ|"
        r"bản\s+đồ|phả\s+hệ|phổ\s+(?:ir|khối)|"
        r"graph|chart|table|diagram|"
        r"(?:as\s+shown\s+in|see|refer\s+to)\s+figure(?:\s+\d+)?)\b"
    )

    def extract_from_document(
        self,
        document: dict[str, Any],
    ) -> list[dict[str, Any]]:
        """Extract questions from parsed HTML/PDF and retain source metadata."""

        pages = document.get("pages")
        if document.get("content_type") == "pdf" and isinstance(pages, list):
            return self.extract_pages(pages, document)

        return self.extract_text(document.get("content", ""), document)

    def extract_text(
        self,
        text: str,
        document: dict[str, Any],
        page_number: int | None = None,
    ) -> list[dict[str, Any]]:
        return self._extract_lines(
            ((page_number, line) for line in text.splitlines()),
            document,
        )

    def extract_pages(
        self,
        pages: list[dict[str, Any]],
        document: dict[str, Any],
    ) -> list[dict[str, Any]]:
        return self._extract_lines(
            (
                (page.get("page"), line)
                for page in pages
                for line in page.get("text", "").splitlines()
            ),
            document,
        )

    def _extract_lines(
        self,
        lines: Iterable[tuple[int | None, str]],
        document: dict[str, Any],
    ) -> list[dict[str, Any]]:
        questions = []
        passage_groups = {}
        current = None
        current_option = None
        active_group_id = None
        group_index = 0

        def finish_question() -> None:
            nonlocal current, current_option
            if current is not None and self._is_complete(current):
                current["source_pages"] = sorted(set(current.pop("_source_pages")))
                current["question_type"] = self._classify_question(current)
                current["quality"] = self._quality(current)
                questions.append(current)
                if current.get("group_id"):
                    passage_groups[current["group_id"]]["questions"].append(current)
            current = None
            current_option = None

        def start_passage(text: str, page_number: int | None) -> None:
            nonlocal active_group_id, group_index, current_option
            finish_question()
            group_index += 1
            active_group_id = (
                f"{document.get('document_id', 'document')}_GROUP_{group_index:03d}"
            )
            range_match = self.PASSAGE_QUESTION_RANGE_PATTERN.match(
                " ".join(text.split())
            )
            passage_groups[active_group_id] = {
                "group_type": self._classify_group(text),
                "chunks": [" ".join(text.split())],
                "questions": [],
                "page_number": page_number,
                "last_question_number": (
                    int(range_match.group(1)) if range_match else None
                ),
            }
            current_option = None

        def add_text_to_question(text: str) -> None:
            nonlocal current_option
            text = " ".join(text.split())
            if not text or current is None:
                return
            if current_option:
                current["options"][current_option] = self._clean_option(
                    f"{current['options'].get(current_option, '')} {text}",
                    current,
                ).strip()
            else:
                current["stem"] = f"{current['stem']} {text}".strip()

        def consume_preamble(text: str, page_number: int | None) -> None:
            text = " ".join(text.split())
            if not text:
                return

            if self.PASSAGE_QUESTION_RANGE_PATTERN.match(text):
                start_passage(text, page_number)
                return

            passage_match = self.PASSAGE_START_PATTERN.search(text)
            if passage_match:
                prefix = text[:passage_match.start()].strip()
                add_text_to_question(prefix)
                start_passage(text[passage_match.start():], page_number)
                return

            if active_group_id and current is None:
                passage_groups[active_group_id]["chunks"].append(text)
                return

            consume_question_content(text)

        def consume_question_content(text: str) -> None:
            nonlocal current_option
            text = " ".join(text.split())
            if not text or current is None:
                return

            passage_match = self.PASSAGE_START_PATTERN.search(text)
            if passage_match:
                prefix = text[:passage_match.start()].strip()
                if prefix:
                    consume_question_fragment(prefix)
                start_passage(text[passage_match.start():], current["page_number"])
                return

            if self.PASSAGE_QUESTION_RANGE_PATTERN.match(text):
                start_passage(text, current["page_number"])
                return

            answer_match = self.ANSWER_MARKER_PATTERN.search(text)
            answer_text = None
            if answer_match:
                answer_text = text[answer_match.end():].strip()
                text = text[:answer_match.start()].rstrip()

            parsed_option = self._parse_options(text, current)
            if parsed_option is not None:
                current_option = parsed_option
            elif text:
                add_text_to_question(text)

            if answer_text:
                current["answer"] = self._normalize_answer(
                    answer_text,
                    current["options"],
                )

        def consume_question_fragment(text: str) -> None:
            nonlocal current_option
            parsed_option = self._parse_options(text, current)
            if parsed_option is not None:
                current_option = parsed_option
            else:
                add_text_to_question(text)

        for page_number, raw_line in lines:
            line = " ".join(raw_line.split())
            if not line:
                continue

            question_match = self.QUESTION_START_PATTERN.match(line)
            if question_match:
                finish_question()
                question_number = int(question_match.group(1))
                if active_group_id:
                    last_question_number = passage_groups[active_group_id][
                        "last_question_number"
                    ]
                    if (
                        last_question_number is not None
                        and question_number > last_question_number
                    ):
                        active_group_id = None
                current = self._new_question(
                    question_match.group(1),
                    document,
                    page_number,
                )
                if active_group_id:
                    current["group_id"] = active_group_id
                    current["group_type"] = passage_groups[active_group_id]["group_type"]
                    group_page = passage_groups[active_group_id]["page_number"]
                    if group_page is not None:
                        current["_source_pages"].append(group_page)
                if page_number is not None:
                    current["_source_pages"].append(page_number)
                consume_question_content(line[question_match.end():])
                continue

            if current is not None and page_number is not None:
                current["_source_pages"].append(page_number)
            consume_preamble(line, page_number)

        finish_question()

        for group in passage_groups.values():
            passage = " ".join(group["chunks"]).strip()
            for question in group["questions"]:
                question["passage"] = passage

        return questions

    def _parse_options(
        self,
        text: str,
        question: dict[str, Any],
    ) -> str | None:
        matches = list(self.OPTION_PATTERN.finditer(text))
        if not matches:
            return None

        labels = [match.group(1).upper() for match in matches]
        prefix = text[:matches[0].start()].strip().strip(" ([")

        if len(set(labels)) != len(labels) or labels != sorted(labels, key="ABCD".index):
            question.setdefault("_extraction_warnings", []).append(
                "unexpected_option_order"
            )

        if question["options"]:
            previous_key = next(reversed(question["options"]))
            previous_index = "ABCD".index(previous_key)
            expected_label = (
                "ABCD"[previous_index + 1]
                if previous_index < 3
                else None
            )
            if expected_label is None or labels[0] != expected_label:
                question.setdefault("_extraction_warnings", []).append(
                    "unexpected_option_order"
                )
            if prefix:
                question["options"][previous_key] = self._clean_option(
                    f"{question['options'][previous_key]} {prefix}",
                    question,
                ).strip()
        else:
            if labels[0] != "A":
                question.setdefault("_extraction_warnings", []).append(
                    "unexpected_option_order"
                )
            if prefix and (len(matches) < 2 or labels[0] != "A"):
                return None

        if prefix and not question["options"] and len(matches) >= 2:
            question["stem"] = prefix

        for index, match in enumerate(matches):
            option_key = match.group(1).upper()
            end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
            value = text[match.end():end].strip()
            question["options"][option_key] = self._clean_option(value, question)

        return matches[-1].group(1).upper()

    def _clean_option(self, text: str, question: dict[str, Any]) -> str:
        footnote = self.FOOTNOTE_PATTERN.search(text)
        if footnote:
            question.setdefault("_extraction_warnings", []).append(
                "footnote_removed_from_option"
            )
            return text[:footnote.start()].rstrip()
        return text

    @staticmethod
    def _new_question(
        number: str,
        document: dict[str, Any],
        page_number: int | None,
    ) -> dict[str, Any]:
        return {
            "question_number": int(number),
            "stem": "",
            "options": {},
            "question_type": "",
            "answer": None,
            "explanation": None,
            "document_id": document.get("document_id", ""),
            "exam": document.get("exam", ""),
            "source_url": document.get("source_url", document.get("url", "")),
            "source": document.get("source", ""),
            "page_number": page_number,
            "_source_pages": [],
        }

    @staticmethod
    def _is_complete(question: dict[str, Any]) -> bool:
        return bool(question.get("stem", "").strip())

    def _classify_question(self, question: dict[str, Any]) -> str:
        stem = question["stem"].casefold()
        option_values = {
            value.strip().casefold()
            for value in question["options"].values()
        }

        if option_values == {"đúng", "sai"} or any(
            phrase in stem
            for phrase in ("đúng hay sai", "đúng hoặc sai", "mỗi phát biểu sau")
        ):
            return "true_false"

        if question["options"] or self._has_objective_choice_hint(stem):
            return "multiple_choice" if self._expects_multiple_answers(stem) else "single_choice"

        if any(phrase in stem for phrase in ("kéo", "thả vào", "vị trí thả")):
            question["response_mode"] = "drag_drop"
            return "short_answer"

        answer = question.get("answer")
        if (
            isinstance(answer, list)
            and len(answer) == 1
            and re.fullmatch(r"[-+]?\d+(?:[.,]\d+)?", answer[0])
        ):
            return "numeric_input"

        if any(phrase in stem for phrase in ("điền", "chỗ trống", "hoàn thành")):
            numeric_markers = (
                "điền kết quả",
                "điền đáp án",
                "giá trị số",
                "số nguyên",
                "làm tròn",
                "bằng bao nhiêu",
            )
            if (
                isinstance(answer, list)
                and len(answer) == 1
                and re.fullmatch(r"[-+]?\d+(?:[.,]\d+)?", answer[0])
            ) or any(marker in stem for marker in numeric_markers):
                return "numeric_input"
            return "short_answer"

        return "short_answer"

    @staticmethod
    def _has_objective_choice_hint(stem: str) -> bool:
        hints = (
            "chọn đáp án",
            "chọn phương án",
            "lựa chọn nào",
            "đáp án nào",
            "phương án nào",
            "trong các đáp án",
        )
        return any(hint in stem.casefold() for hint in hints)

    @classmethod
    def _expects_multiple_answers(cls, stem: str) -> bool:
        normalized = stem.casefold()
        normalized = re.sub(r"\b2\b", "hai", normalized)
        return bool(cls.MULTI_SELECT_PATTERN.search(normalized))

    @classmethod
    def _classify_group(cls, text: str) -> str:
        match = cls.PASSAGE_START_PATTERN.search(text)
        if match and match.group(1).casefold() in {"bảng", "biểu đồ", "sơ đồ"}:
            return "visual_data"
        return "passage"

    @classmethod
    def _normalize_answer(
        cls,
        answer_text: str,
        options: dict[str, str],
    ) -> list[str] | None:
        answer = answer_text.strip().rstrip(".")
        if not answer or answer.casefold() in cls.UNKNOWN_ANSWER_VALUES:
            return None

        if cls.ANSWER_LIST_PATTERN.fullmatch(answer):
            return re.findall(r"[A-D]", answer.upper())
        if re.fullmatch(r"[A-D]", answer, re.IGNORECASE):
            return [answer.upper()]

        normalized_answer = answer.casefold()
        for option_key, option_text in options.items():
            if option_text.strip().casefold() == normalized_answer:
                return [option_key]

        if options:
            return None
        return [answer] if answer else None

    @classmethod
    def _quality(cls, question: dict[str, Any]) -> dict[str, Any]:
        warnings = list(question.pop("_extraction_warnings", []))
        stem = question["stem"].casefold()
        question_type = question["question_type"]
        options = question["options"]

        if question_type in {"single_choice", "multiple_choice"}:
            if len(options) < 2:
                warnings.append("choice_options_incomplete")
            if len(options) == 2 and question_type == "single_choice":
                warnings.append("possible_merged_options")
            if set(options) not in ({"A", "B"}, {"A", "B", "C", "D"}):
                warnings.append("choice_labels_incomplete")
            if question["answer"] is None:
                warnings.append("missing_answer")
            if (
                question_type == "multiple_choice"
                and question["answer"] is not None
                and not isinstance(question["answer"], list)
            ):
                warnings.append("multiple_answer_format_invalid")

        if question_type == "true_false" and not options:
            warnings.append("layout_or_table_content_needs_review")
        if question_type == "true_false" and question["answer"] is None:
            warnings.append("missing_answer")
        if question_type in {"numeric_input", "short_answer"} and question["answer"] is None:
            warnings.append("missing_answer")
        if "đọc văn bản sau" in stem or "trả lời các câu hỏi từ" in stem:
            warnings.append("passage_boundary_needs_review")
        if cls.VISUAL_REFERENCE_PATTERN.search(stem) or cls.VISUAL_REFERENCE_PATTERN.search(
            question.get("passage", "")
        ):
            warnings.append("visual_content_needs_review")

        warnings = list(dict.fromkeys(warnings))
        return {
            "extraction_status": "needs_review" if warnings else "extracted",
            "human_review_status": "pending",
            "warnings": warnings,
        }