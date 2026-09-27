import re
from pathlib import Path
from typing import Any

import pymupdf

from crawler.common.document_parser import DocumentParser


class QuestionVisualProcessor:
    """Crop visual content by question geometry and keep OCR review candidates."""

    LOW_TEXT_CHARACTER_LIMIT = 30
    MIN_VECTOR_DRAWINGS = 8
    PAGE_TOP_MARGIN = 55
    PAGE_BOTTOM_MARGIN = 35
    CROP_PADDING = 6
    VISUAL_CUE_PATTERN = re.compile(
        r"(?i)\b(?:như\s+hình|dựa\s+vào\s+hình|quan\s+sát\s+hình|"
        r"hình\s+(?:\d+|vẽ|minh\s+họa|minh\s+hoạ|bên|sau|trên|dưới)|"
        r"đồ\s+thị\s+(?:sau|dưới|bên|như|cho\s+trong)|"
        r"biểu\s+đồ|bảng(?:\s+số\s+liệu)?|sơ\s+đồ|"
        r"bản\s+đồ|phả\s+hệ|phổ\s+(?:ir|khối)|"
        r"as shown|graph|chart|table|diagram|"
        r"(?:as\s+shown\s+in|see|refer\s+to)\s+figure(?:\s+\d+)?)\b"
    )

    def __init__(
        self,
        parser: DocumentParser,
        processed_dir: str,
    ):
        self.parser = parser
        self.processed_dir = Path(processed_dir)
        self.visual_dir = self.processed_dir / "visuals"

    def attach_pdf_pages(
        self,
        document: dict[str, Any],
        pdf_path: str,
        document_id: str,
        questions: list[dict[str, Any]],
    ) -> None:
        """Associate cropped figure/table assets with only relevant questions."""

        layout = self.parser.inspect_pdf_visual_layout(pdf_path)
        document_assets = []
        pages_needing_ocr = set()
        visual_pages = set()
        asset_cache = {}
        safe_document_id = re.sub(r"[^A-Za-z0-9_-]", "_", document_id)

        for question in questions:
            question_assets = []
            source_pages = self._get_source_pages(question)
            visual_text = " ".join((
                question.get("stem", ""),
                question.get("passage", ""),
            ))
            has_visual_cue = bool(self.VISUAL_CUE_PATTERN.search(visual_text))

            for page_number in source_pages:
                page_layout = layout.get(page_number)
                if page_layout is None:
                    continue
                is_group_context_page = (
                    bool(question.get("group_id"))
                    and page_number == min(source_pages)
                    and question.get("page_number") != page_number
                )
                cue_applies_to_page = (
                    has_visual_cue
                    and (
                        page_number == question.get("page_number")
                        or is_group_context_page
                    )
                )
                has_large_image = bool(page_layout["image_rects"])
                if (
                    is_group_context_page
                    and not cue_applies_to_page
                    and not has_large_image
                ):
                    continue

                page_rect = pymupdf.Rect(page_layout["page_rect"])
                question_region = self._question_region(
                    page_layout,
                    question.get("question_number"),
                    question.get("group_id"),
                    question.get("page_number"),
                    is_group_context_page,
                )
                image_rects = self._intersecting_image_rects(
                    page_layout,
                    question_region,
                )
                drawing_rects = self._intersecting_drawing_rects(
                    page_layout,
                    question_region,
                    page_rect.width,
                )
                has_vector_visual = (
                    bool(drawing_rects)
                    and (
                        len(drawing_rects) >= self.MIN_VECTOR_DRAWINGS
                        or cue_applies_to_page
                    )
                )
                visual_rects = list(image_rects)

                if has_vector_visual:
                    visual_rects.append(self._union_rect(drawing_rects))

                if cue_applies_to_page and not visual_rects:
                    visual_rects.append(question_region)

                if visual_rects:
                    visual_pages.add(page_number)
                    for visual_rect in self._merge_rects(visual_rects):
                        visual_rect = self._clamp_rect(visual_rect, page_rect)
                        asset = self._save_asset(
                            pdf_path=pdf_path,
                            document_id=safe_document_id,
                            page_number=page_number,
                            rect=visual_rect,
                            kind="question_visual_crop",
                            asset_cache=asset_cache,
                        )
                        if asset not in document_assets:
                            document_assets.append(asset)
                        if asset not in question_assets:
                            question_assets.append(asset)

            question["visual_assets"] = question_assets
            if question_assets:
                self._mark_visual_review(question)

        for page_number, page_layout in layout.items():
            page_rect = pymupdf.Rect(page_layout["page_rect"])
            has_page_visual = bool(page_layout["image_rects"]) or (
                page_layout["drawing_item_count"] >= self.MIN_VECTOR_DRAWINGS
            )
            if (
                page_layout["text_character_count"] >= self.LOW_TEXT_CHARACTER_LIMIT
                or not has_page_visual
            ):
                continue

            pages_needing_ocr.add(page_number)
            visual_pages.add(page_number)
            page_regions = [
                pymupdf.Rect(rect)
                for rect in page_layout["image_rects"]
            ]
            if not page_regions:
                page_regions = [self._content_region(page_rect)]

            for visual_rect in self._merge_rects(page_regions):
                visual_rect = self._clamp_rect(visual_rect, page_rect)
                asset = self._save_asset(
                    pdf_path=pdf_path,
                    document_id=safe_document_id,
                    page_number=page_number,
                    rect=visual_rect,
                    kind="ocr_review_crop",
                    asset_cache=asset_cache,
                )
                if asset not in document_assets:
                    document_assets.append(asset)

        document["visual_assets"] = document_assets
        document["visual_page_numbers"] = sorted(visual_pages)
        document["pages_needing_ocr"] = sorted(pages_needing_ocr)

    def _question_region(
        self,
        page_layout: dict[str, Any],
        question_number: Any,
        group_id: str | None,
        question_page: int | None,
        is_group_context_page: bool,
    ) -> pymupdf.Rect:
        page_rect = pymupdf.Rect(page_layout["page_rect"])
        headings = page_layout["question_headings"]
        question_index = next((
            index
            for index, heading in enumerate(headings)
            if heading["question_number"] == question_number
        ), None)

        if question_index is not None:
            start_y = pymupdf.Rect(headings[question_index]["bbox"]).y0
            end_y = (
                pymupdf.Rect(headings[question_index + 1]["bbox"]).y0
                if question_index + 1 < len(headings)
                else page_rect.height - self.PAGE_BOTTOM_MARGIN
            )
            return pymupdf.Rect(
                page_rect.x0,
                max(page_rect.y0, start_y - self.CROP_PADDING),
                page_rect.x1,
                max(start_y, end_y),
            )

        if is_group_context_page:
            return self._content_region(page_rect)

        if headings:
            first_heading_y = pymupdf.Rect(headings[0]["bbox"]).y0
            return pymupdf.Rect(
                page_rect.x0,
                page_rect.y0 + self.PAGE_TOP_MARGIN,
                page_rect.x1,
                max(page_rect.y0 + self.PAGE_TOP_MARGIN, first_heading_y),
            )

        return page_rect

    def _intersecting_image_rects(
        self,
        page_layout: dict[str, Any],
        question_region: pymupdf.Rect,
    ) -> list[pymupdf.Rect]:
        return [
            (pymupdf.Rect(rect) & question_region).irect
            for rect in page_layout["image_rects"]
            if pymupdf.Rect(rect).intersects(question_region)
        ]

    def _intersecting_drawing_rects(
        self,
        page_layout: dict[str, Any],
        question_region: pymupdf.Rect,
        page_width: float,
    ) -> list[pymupdf.Rect]:
        drawing_rects = []
        for raw_rect in page_layout["drawing_rects"]:
            rect = pymupdf.Rect(raw_rect)
            if not rect.intersects(question_region):
                continue
            if rect.height <= 2.5 and rect.width >= page_width * 0.6:
                continue
            drawing_rects.append(rect & question_region)
        return drawing_rects

    @staticmethod
    def _union_rect(rects: list[pymupdf.Rect]) -> pymupdf.Rect:
        result = pymupdf.Rect(rects[0])
        for rect in rects[1:]:
            result |= rect
        return result

    @classmethod
    def _merge_rects(
        cls,
        rects: list[pymupdf.Rect],
    ) -> list[pymupdf.Rect]:
        merged = []
        for rect in rects:
            candidate = pymupdf.Rect(rect)
            candidate.x0 -= cls.CROP_PADDING
            candidate.y0 -= cls.CROP_PADDING
            candidate.x1 += cls.CROP_PADDING
            candidate.y1 += cls.CROP_PADDING

            index = 0
            while index < len(merged):
                existing = merged[index]
                nearby = existing + (-cls.CROP_PADDING, -cls.CROP_PADDING,
                                     cls.CROP_PADDING, cls.CROP_PADDING)
                if nearby.intersects(candidate):
                    candidate |= existing
                    merged.pop(index)
                    index = 0
                    continue
                index += 1
            merged.append(candidate)
        return merged

    @staticmethod
    def _clamp_rect(
        rect: pymupdf.Rect,
        page_rect: pymupdf.Rect,
    ) -> pymupdf.Rect:
        return pymupdf.Rect(rect) & page_rect

    def _save_asset(
        self,
        pdf_path: str,
        document_id: str,
        page_number: int,
        rect: pymupdf.Rect,
        kind: str,
        asset_cache: dict[tuple, dict[str, Any]],
    ) -> dict[str, Any]:
        normalized_rect = tuple(round(value, 1) for value in rect)
        cache_key = (page_number, normalized_rect)
        if cache_key in asset_cache:
            return asset_cache[cache_key]

        question_tag = "OCR" if kind == "ocr_review_crop" else "VISUAL"
        crop_index = sum(
            asset["page_number"] == page_number
            for asset in asset_cache.values()
        ) + 1
        filename = (
            f"{document_id}_page_{page_number:03d}_"
            f"{question_tag}_{crop_index:02d}.png"
        )
        image_path = self.parser.render_pdf_region(
            file_path=pdf_path,
            page_number=page_number,
            rect=normalized_rect,
            output_dir=str(self.visual_dir),
            filename=filename,
        )
        asset_id = f"{document_id}_P{page_number:03d}_{question_tag}_{crop_index:02d}"
        asset = {
            "asset_id": asset_id,
            "kind": kind,
            "page_number": page_number,
            "bbox": list(normalized_rect),
            "path": Path(image_path).relative_to(self.processed_dir).as_posix(),
        }
        asset_cache[cache_key] = asset
        return asset

    @classmethod
    def _content_region(cls, page_rect: pymupdf.Rect) -> pymupdf.Rect:
        return pymupdf.Rect(
            page_rect.x0,
            page_rect.y0 + cls.PAGE_TOP_MARGIN,
            page_rect.x1,
            page_rect.y1 - cls.PAGE_BOTTOM_MARGIN,
        )

    @staticmethod
    def _get_source_pages(question: dict[str, Any]) -> list[int]:
        source_pages = question.get("source_pages")
        if not isinstance(source_pages, list):
            source_pages = [question.get("page_number")]
        return sorted({
            page_number
            for page_number in source_pages
            if isinstance(page_number, int) and page_number > 0
        })

    @staticmethod
    def _mark_visual_review(question: dict[str, Any]) -> None:
        quality = question.setdefault("quality", {})
        warnings = quality.setdefault("warnings", [])
        if "visual_content_needs_review" not in warnings:
            warnings.append("visual_content_needs_review")
        quality["extraction_status"] = "needs_review"
