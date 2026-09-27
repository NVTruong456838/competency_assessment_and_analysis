from urllib.parse import urljoin, urlparse, urlunparse

from bs4 import BeautifulSoup

from crawler.common.http_client import HttpClient
from crawler.common.robots import RobotsChecker
from crawler.common.storage import JsonStorage


class HustTsaCrawler:
    """
    Crawler tài liệu TSA từ website chính thức
    của Đại học Bách khoa Hà Nội.
    """

    BASE_URL = "https://ts.hust.edu.vn"

    ALLOWED_DOMAIN = "ts.hust.edu.vn"

    START_URLS = [
        BASE_URL + "/",
    ]

    def __init__(self):

        self.http = HttpClient(
            delay=1.0,
            timeout=20
        )

        self.robots = RobotsChecker(
            user_agent="SmartAssessmentAI-Research"
        )

        self.storage = JsonStorage(
            output_dir="crawler/output"
        )

        self.visited = set()
        self.documents = []

    def is_allowed_domain(
        self,
        url: str
    ) -> bool:
        """
        Chỉ cho phép URL thuộc website TSA HUST.
        """

        domain = urlparse(
            url
        ).netloc.lower()

        return (
            domain == self.ALLOWED_DOMAIN
        )

    def normalize_url(
        self,
        url: str
    ) -> str:
        """
        Chuẩn hóa URL cơ bản.
        """

        parsed = urlparse(url.split("#", 1)[0])
        path = parsed.path or "/"

        if path != "/":
            path = path.rstrip("/")

        return urlunparse(
            (
                parsed.scheme.lower(),
                parsed.netloc.lower(),
                path,
                parsed.params,
                parsed.query,
                ""
            )
        )

    def is_document_link(
        self,
        url: str
    ) -> bool:
        """
        Kiểm tra URL có phải link tài liệu
        hay không.
        """

        lower_url = urlparse(url).path.lower()

        document_extensions = (
            ".pdf",
            ".doc",
            ".docx",
            ".xls",
            ".xlsx"
        )

        return lower_url.endswith(
            document_extensions
        )

    def classify_document(
        self,
        title: str,
        url: str
    ) -> str:
        """
        Phân loại tài liệu dựa trên title/URL.
        """

        text = (
            f"{title} {url}"
        ).lower()

        if "cẩm nang" in text:
            return "handbook"

        if "đề tham khảo" in text:
            return "sample_exam"

        if "đề minh họa" in text:
            return "sample_exam"

        if "đề thi" in text:
            return "exam_related"

        if "cấu trúc" in text:
            return "exam_structure"

        if "hướng dẫn" in text:
            return "guideline"

        return "other"

    def document_exists(
        self,
        url: str
    ) -> bool:
        """
        Kiểm tra URL đã tồn tại trong dataset chưa.
        """

        return any(
            item["url"] == url
            for item in self.documents
        )

    def extract_page(
        self,
        url: str
    ):
        """
        Đọc một trang HTML và lấy metadata + links.
        """

        if url in self.visited:
            return []

        self.visited.add(url)

        if not self.is_allowed_domain(url):
            return []

        if not self.robots.can_fetch(url):

            print(
                f"[ROBOTS BLOCKED] {url}"
            )

            return []

        response = self.http.get(
            url
        )

        if response is None:
            return []

        content_type = response.headers.get(
            "Content-Type",
            ""
        ).lower()

        if "text/html" not in content_type:
            return []

        soup = BeautifulSoup(
            response.text,
            "html.parser"
        )

        title = ""

        if soup.title:

            title = soup.title.get_text(
                " ",
                strip=True
            )

        page_data = {
            "url": url,
            "title": title,
            "document_type": self.classify_document(
                title,
                url
            ),
            "source": "HUST_TSA"
        }

        if not self.document_exists(url):

            self.documents.append(
                page_data
            )

        print(
            f"[PAGE] {title}"
        )

        print(
            f"       {url}"
        )

        links = []

        for a in soup.find_all(
            "a",
            href=True
        ):

            href = urljoin(
                url,
                a["href"]
            )

            href = self.normalize_url(
                href
            )

            if not href:
                continue

            if not self.is_allowed_domain(
                href
            ):
                continue

            if not self.is_document_link(href):
                links.append(href)

            if self.is_document_link(href):

                link_title = a.get_text(
                    " ",
                    strip=True
                )

                document = {
                    "url": href,
                    "title": link_title,
                    "document_type": self.classify_document(
                        link_title,
                        href
                    ),
                    "source": "HUST_TSA"
                }

                if not self.document_exists(
                    href
                ):

                    self.documents.append(
                        document
                    )

        return links

    def crawl(
        self,
        max_pages: int = 20
    ):
        """
        Crawl từ các URL bắt đầu.
        """

        queue = list(
            self.START_URLS
        )

        while (
            queue
            and len(self.visited) < max_pages
        ):

            url = queue.pop(0)

            if url in self.visited:
                continue

            links = self.extract_page(
                url
            )

            for link in links:

                if (
                    link not in self.visited
                    and link not in queue
                ):

                    queue.append(
                        link
                    )

        return self.documents


if __name__ == "__main__":

    crawler = HustTsaCrawler()

    documents = crawler.crawl(
        max_pages=20
    )

    print()
    print("=" * 60)

    print(
        f"Tổng số tài liệu/trang: "
        f"{len(documents)}"
    )

    crawler.storage.save(
        documents,
        "tsa_documents.json"
    )

    print()
    print(
        "Crawler TSA hoàn thành."
    )