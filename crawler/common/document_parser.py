from io import BytesIO
from pathlib import Path
import re
import unicodedata
from urllib.parse import urljoin, urlparse, urlsplit, urlunsplit

import pymupdf
from bs4 import BeautifulSoup
from pypdf import PdfReader


class DocumentParser:
    """
    Convert HTML and PDF content into Reference Document dictionaries.

    This class parses content only. Network access and file persistence remain
    responsibilities of the HTTP client and storage classes.
    """

    DOCUMENT_EXTENSIONS = (
        ".pdf",
        ".doc",
        ".docx",
        ".xls",
        ".xlsx",
    )
    QUESTION_HEADING_PATTERN = re.compile(
        r"^\s*(?:Cau(?:\s+hoi)?|Question)\s*0*(\d+)\b",
        re.IGNORECASE,
    )
    MIN_IMAGE_AREA_RATIO = 0.005

    def inspect_pdf_visual_layout(self, file_path: str) -> dict[int, dict]:
        """Return question-heading coordinates and visual geometry per page."""

        layout = {}
        with pymupdf.open(file_path) as pdf:
            for page_number, page in enumerate(pdf, start=1):
                question_headings = []
                page_text = page.get_text("dict")
                for block in page_text["blocks"]:
                    if block.get("type") != 0:
                        continue
                    for line in block.get("lines", []):
                        line_text = "".join(
                            span.get("text", "")
                            for span in line.get("spans", [])
                        ).strip()
                        normalized_line = self._normalize_question_heading_text(
                            line_text
                        )
                        match = self.QUESTION_HEADING_PATTERN.match(normalized_line)
                        if match:
                            question_headings.append({
                                "question_number": int(match.group(1)),
                                "bbox": tuple(line["bbox"]),
                            })

                page_area = page.rect.get_area()
                image_rects = []
                for xref, *_ in page.get_images(full=True):
                    for image_rect in page.get_image_rects(xref):
                        rect = pymupdf.Rect(image_rect)
                        if rect.get_area() >= page_area * self.MIN_IMAGE_AREA_RATIO:
                            image_rects.append(tuple(rect))

                drawings = page.get_drawings()
                layout[page_number] = {
                    "page_number": page_number,
                    "page_rect": tuple(page.rect),
                    "question_headings": question_headings,
                    "image_rects": image_rects,
                    "drawing_rects": [
                        tuple(drawing["rect"])
                        for drawing in drawings
                    ],
                    "drawing_item_count": sum(
                        len(drawing.get("items", []))
                        for drawing in drawings
                    ),
                    "text_character_count": len(page.get_text("text").strip()),
                }
        return layout

    @staticmethod
    def _normalize_question_heading_text(text: str) -> str:
        return "".join(
            character
            for character in unicodedata.normalize("NFKD", text)
            if not unicodedata.combining(character)
        )

    def render_pdf_region(
        self,
        file_path: str,
        page_number: int,
        rect: tuple[float, float, float, float],
        output_dir: str,
        filename: str,
        scale: float = 2.0,
    ) -> str:
        """Render a bounded region of one 1-based PDF page to PNG."""

        pdf_path = Path(file_path)
        if not pdf_path.is_file():
            raise FileNotFoundError(f"File does not exist: {file_path}")

        destination = Path(output_dir)
        destination.mkdir(parents=True, exist_ok=True)
        with pymupdf.open(pdf_path) as pdf:
            if page_number < 1 or page_number > len(pdf):
                raise ValueError("page_number is outside the PDF")

            page = pdf[page_number - 1]
            clip = pymupdf.Rect(rect) & page.rect
            if clip.is_empty or clip.is_infinite:
                raise ValueError("rect must overlap the PDF page")

            image_path = destination / filename
            page.get_pixmap(
                matrix=pymupdf.Matrix(scale, scale),
                clip=clip,
                alpha=False,
            ).save(image_path)
        return str(image_path)

    def render_pdf_pages(
        self,
        file_path: str,
        page_numbers: set[int] | list[int],
        output_dir: str,
        filename_prefix: str,
        scale: float = 1.5,
    ) -> dict[int, str]:
        """Render selected 1-based PDF pages to PNG for visual review."""

        pdf_path = Path(file_path)
        if not pdf_path.is_file():
            raise FileNotFoundError(f"File does not exist: {file_path}")

        destination = Path(output_dir)
        destination.mkdir(parents=True, exist_ok=True)
        rendered = {}

        with pymupdf.open(pdf_path) as pdf:
            for page_number in sorted(set(page_numbers)):
                if page_number < 1 or page_number > len(pdf):
                    continue

                page = pdf[page_number - 1]
                pixmap = page.get_pixmap(
                    matrix=pymupdf.Matrix(scale, scale),
                    alpha=False,
                )
                filename = f"{filename_prefix}_page_{page_number:03d}.png"
                image_path = destination / filename
                pixmap.save(image_path)
                rendered[page_number] = str(image_path)

        return rendered

    def parse_html(
        self,
        html: str,
        url: str,
        document_type: str | None = None,
        source: str | None = None,
    ) -> dict:
        """Parse HTML into a Reference Document and extract its links."""

        if self._looks_like_url(html) and not self._looks_like_url(url):
            html, url = url, html
            if source is None and document_type is not None:
                source = document_type
                document_type = None

        soup = BeautifulSoup(html, "html.parser")
        title = self.extract_title(soup)
        links = self.extract_links(url, soup)

        for element in soup(["script", "style", "noscript"]):
            element.decompose()

        content = soup.get_text("\n", strip=True)
        normalized_url = self.normalize_url(url)
        resolved_type = document_type or self.classify_document(
            title,
            normalized_url,
        )

        return {
            "source": source or self._get_domain(normalized_url),
            "source_url": normalized_url,
            "document_type": resolved_type,
            "title": title,
            "content_type": "html",
            "content": content,
            "links": links,
            "url": normalized_url,
            "text": content,
        }

    def parse_pdf(
        self,
        content: bytes,
        url: str,
        title: str = "",
        document_type: str | None = None,
        source: str | None = None,
    ) -> dict:
        """Parse PDF bytes and preserve text grouped by page."""

        reader = PdfReader(BytesIO(content))
        pages = []

        for page_number, page in enumerate(reader.pages, start=1):
            try:
                page_text = page.extract_text() or ""
            except Exception:
                page_text = ""

            pages.append({
                "page": page_number,
                "text": page_text,
            })

        full_text = "\n\n".join(
            page["text"]
            for page in pages
        )
        normalized_url = self.normalize_url(url)
        resolved_type = document_type or self.classify_document(
            title,
            normalized_url,
        )

        return {
            "source": source or self._get_domain(normalized_url),
            "source_url": normalized_url,
            "document_type": resolved_type,
            "title": title,
            "content_type": "pdf",
            "page_count": len(reader.pages),
            "content": full_text,
            "pages": pages,
            "url": normalized_url,
            "text": full_text,
            "links": [],
        }

    def parse_file(
        self,
        file_path: str,
        url: str = "",
        title: str = "",
        document_type: str | None = None,
        source: str | None = None,
    ) -> dict:
        """Parse a local PDF file into a Reference Document."""

        path = Path(file_path)

        if not path.exists():
            raise FileNotFoundError(f"File does not exist: {file_path}")

        if path.suffix.lower() != ".pdf":
            raise ValueError("Only local PDF files are supported")

        with path.open("rb") as file:
            content = file.read()

        return self.parse_pdf(
            content=content,
            url=url or path.as_uri(),
            title=title or path.stem,
            document_type=document_type,
            source=source,
        )

    def parse_response(
        self,
        response,
        url: str,
        title: str = "",
        document_type: str | None = None,
        source: str | None = None,
    ) -> dict | None:
        """Parse an HTTP response when its content type is HTML or PDF."""

        content_type = response.headers.get("Content-Type", "").lower()

        if "text/html" in content_type:
            return self.parse_html(
                response.text,
                url,
                document_type=document_type,
                source=source,
            )

        if "application/pdf" in content_type or self.is_document_url(url):
            return self.parse_pdf(
                response.content,
                url,
                title=title,
                document_type=document_type,
                source=source,
            )

        return None

    def extract_title(self, soup: BeautifulSoup) -> str:
        """Extract the HTML title, or an empty string when absent."""

        if soup.title is None:
            return ""

        return soup.title.get_text(" ", strip=True)

    def extract_links(
        self,
        base_url: str,
        soup: BeautifulSoup,
    ) -> list[str]:
        """Extract unique absolute HTTP(S) links from a parsed page."""

        links = []
        seen = set()

        for anchor in soup.find_all("a", href=True):
            link = self.normalize_url(urljoin(base_url, anchor["href"]))

            if urlsplit(link).scheme not in ("http", "https"):
                continue
            if link in seen:
                continue

            seen.add(link)
            links.append(link)

        return links

    def normalize_url(self, url: str) -> str:
        """Remove fragments and normalize scheme, host, and trailing slashes."""

        parsed = urlsplit(url.split("#", 1)[0])
        path = parsed.path or "/"

        if path != "/":
            path = path.rstrip("/")

        return urlunsplit(
            (
                parsed.scheme.lower(),
                parsed.netloc.lower(),
                path,
                parsed.query,
                "",
            )
        )

    def is_document_url(self, url: str) -> bool:
        """Return whether the URL path has a supported document extension."""

        return urlsplit(url).path.lower().endswith(self.DOCUMENT_EXTENSIONS)

    def classify_document(self, title: str, url: str) -> str:
        """Classify a document using keywords in its title and URL."""

        text = f"{title} {url}".lower()

        if "cẩm nang" in text:
            return "handbook"
        if "đề tham khảo" in text or "đề minh họa" in text:
            return "sample_exam"
        if "đề thi" in text:
            return "exam_related"
        if "cấu trúc" in text:
            return "exam_structure"
        if "hướng dẫn" in text:
            return "guideline"

        return "other"

    @staticmethod
    def _looks_like_url(value: str) -> bool:
        parsed = urlparse(value)
        return parsed.scheme in ("http", "https") and bool(parsed.netloc)

    @staticmethod
    def _get_domain(url: str) -> str:
        return urlparse(url).netloc
