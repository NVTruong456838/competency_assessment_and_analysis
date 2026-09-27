import json
from pathlib import Path
from urllib.parse import urljoin, urlparse

from bs4 import BeautifulSoup

from crawler.common.http_client import HttpClient
from crawler.common.robots import RobotsChecker

ALLOWED_DOMAINS = {
    "ts.hust.edu.vn",
    "hust.edu.vn",
    "idt.vnu.edu.vn",
    "vnu.edu.vn",
}

def is_allowed_url(url: str) -> bool:

    parsed = urlparse(url)
    domain = parsed.netloc.lower()

    return any(
        domain == allowed
        or domain.endswith("." + allowed)
        for allowed in ALLOWED_DOMAINS
    )


def normalize_url(url: str) -> str:

    parsed = urlparse(url.split("#", 1)[0])
    path = parsed.path or "/"

    if path != "/":
        path = path.rstrip("/")

    return parsed._replace(
        scheme=parsed.scheme.lower(),
        netloc=parsed.netloc.lower(),
        path=path,
        fragment=""
    ).geturl()


http = HttpClient(delay=1.0, timeout=20)
robots = RobotsChecker()


def fetch(url: str):

    if not is_allowed_url(url) or not robots.can_fetch(url):
        return None

    return http.get(url)


def extract_links(url: str, response=None):

    if response is None:
        response = fetch(url)

    if response is None:
        return []

    soup = BeautifulSoup(
        response.text,
        "html.parser"
    )

    results = []

    for a in soup.find_all("a", href=True):

        href = normalize_url(urljoin(url, a["href"]))

        if not is_allowed_url(href):
            continue

        text = a.get_text(
            " ",
            strip=True
        )

        results.append({
            "text": text,
            "url": href
        })

    return results


def crawl_source(
    start_url: str,
    max_pages: int = 20
):

    visited = set()
    queue = [start_url]

    documents = []

    while queue and len(visited) < max_pages:

        url = normalize_url(queue.pop(0))

        if url in visited:
            continue

        visited.add(url)

        try:

            response = fetch(url)

            if response is None:
                continue

            content_type = response.headers.get(
                "Content-Type",
                ""
            ).lower()

            if "text/html" not in content_type:
                continue

            soup = BeautifulSoup(
                response.text,
                "html.parser"
            )

            title = ""

            if soup.title:
                title = soup.title.get_text(
                    strip=True
                )

            documents.append({
                "url": url,
                "title": title
            })

            print(f"[OK] {title}")
            print(f"     {url}")

            links = extract_links(url, response)

            for link in links:

                link_url = link["url"]

                if (
                    link_url not in visited
                    and link_url not in queue
                ):
                    queue.append(link_url)

        except Exception as e:

            print(
                f"[ERROR] {url}: {e}"
            )

    return documents


def main():

    sources = [
        "https://ts.hust.edu.vn/",
        "https://idt.vnu.edu.vn/ky-thi-hsa/de-thi-tham-khao",
    ]

    all_documents = []

    for source in sources:

        print("=" * 70)
        print(f"Crawling: {source}")
        print("=" * 70)

        documents = crawl_source(
            source,
            max_pages=20
        )

        all_documents.extend(
            documents
        )

    output_path = (
        Path(__file__).resolve().parent
        / "output"
        / "sources.json"
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with output_path.open("w", encoding="utf-8") as f:
        json.dump(
            all_documents,
            f,
            ensure_ascii=False,
            indent=2
        )

    print()
    print(f"Saved: {len(all_documents)} documents to {output_path}")


if __name__ == "__main__":
    main()