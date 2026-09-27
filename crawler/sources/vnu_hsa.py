import re
from pathlib import PurePosixPath
from urllib.parse import unquote, urljoin, urlsplit

from bs4 import BeautifulSoup

from crawler.common.document_parser import DocumentParser
from crawler.common.http_client import HttpClient
from crawler.common.robots import RobotsChecker
from crawler.common.storage import JsonStorage


class VnuHsaCrawler:
	"""Discover publicly linked HSA exam documents on the official site."""

	BASE_URL = "https://idt.vnu.edu.vn"
	START_URLS = [f"{BASE_URL}/ky-thi-hsa/de-thi-tham-khao"]
	SOURCE_NAME = "VNU_HSA"
	PAGE_PREFIX = "/ky-thi-hsa/de-thi-tham-khao"
	EMBEDDED_DOCUMENT_PATTERN = re.compile(
		r"\\?[\"']uri\\?[\"']\s*:\s*\\?[\"']"
		r"([^\"']+?\.pdf(?:\?[^\"']*)?)\\?[\"']",
		re.IGNORECASE,
	)
	EXAM_KEYWORDS = (
		"đề thi",
		"de thi",
		"đề tham khảo",
		"de tham khao",
		"đề minh họa",
		"de minh hoa",
		"sample exam",
	)

	def __init__(self):
		self.http = HttpClient(delay=1.0, timeout=30)
		self.robots = RobotsChecker(user_agent="SmartAssessmentAI-Research")
		self.parser = DocumentParser()
		self.storage = JsonStorage(output_dir="crawler/output")

	def crawl(self, max_pages: int = 30) -> list[dict]:
		"""Crawl the HSA reference pages and save public exam attachments."""

		queue = list(self.START_URLS)
		visited = set()
		documents = {}

		while queue and len(visited) < max_pages:
			url = self.parser.normalize_url(queue.pop(0))
			if url in visited:
				continue
			visited.add(url)

			if not self._is_allowed_page(url) or not self.robots.can_fetch(url):
				continue

			response = self.http.get(url)
			if response is None:
				continue
			if "text/html" not in response.headers.get("Content-Type", "").lower():
				continue

			soup = BeautifulSoup(response.text, "html.parser")
			page_title = self.parser.extract_title(soup)
			page_links = self.parser.extract_links(url, soup)

			for link in page_links:
				normalized = self.parser.normalize_url(link)
				if urlsplit(normalized).path.lower().endswith(".pdf"):
					self._add_exam_document(documents, normalized, page_title)
				elif self._is_allowed_page(normalized) and normalized not in visited:
					if normalized not in queue:
						queue.append(normalized)

			for match in self.EMBEDDED_DOCUMENT_PATTERN.finditer(response.text):
				document_url = self.parser.normalize_url(urljoin(url, match.group(1)))
				if urlsplit(document_url).path.lower().endswith(".pdf"):
					self._add_exam_document(documents, document_url, page_title)

		result = list(documents.values())
		self.storage.save(result, "hsa_documents.json")
		print(f"[INFO] Public HSA exam documents found: {len(result)}")
		return result

	def _add_exam_document(
		self,
		documents: dict[str, dict],
		url: str,
		page_title: str,
	) -> None:
		filename = unquote(PurePosixPath(urlsplit(url).path).name)
		searchable_text = f"{page_title} {filename} {url}".casefold()
		if not any(keyword in searchable_text for keyword in self.EXAM_KEYWORDS):
			return

		documents[url] = {
			"url": url,
			"title": filename or page_title,
			"document_type": "sample_exam",
			"source": self.SOURCE_NAME,
		}

	def _is_allowed_page(self, url: str) -> bool:
		parsed = urlsplit(url)
		return (
			parsed.scheme in ("http", "https")
			and parsed.netloc.lower() == "idt.vnu.edu.vn"
			and parsed.path.startswith(self.PAGE_PREFIX)
			and not parsed.query
		)


if __name__ == "__main__":
	VnuHsaCrawler().crawl()
