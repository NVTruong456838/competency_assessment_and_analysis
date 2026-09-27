from urllib.parse import urlparse
from urllib.robotparser import RobotFileParser


class RobotsChecker:
    """
    Kiểm tra robots.txt của website.
    """

    def __init__(
        self,
        user_agent: str = "SmartAssessmentAI-Research"
    ):
        self.user_agent = user_agent
        self.parsers = {}

    def _get_parser(self, url: str):
        """
        Lấy RobotFileParser tương ứng với domain.
        """

        parsed = urlparse(url)

        base_url = (
            f"{parsed.scheme}://{parsed.netloc}"
        )

        if base_url not in self.parsers:

            robots_url = (
                f"{base_url}/robots.txt"
            )

            parser = RobotFileParser()
            parser.set_url(robots_url)

            try:

                parser.read()

                self.parsers[base_url] = parser

                print(
                    f"[ROBOTS OK] {robots_url}"
                )

            except Exception as e:

                print(
                    f"[WARNING] Cannot read "
                    f"{robots_url}: {e}"
                )

                self.parsers[base_url] = None

        return self.parsers[base_url]

    def can_fetch(self, url: str) -> bool:
        """
        Kiểm tra crawler có được phép truy cập URL hay không.
        """

        parser = self._get_parser(url)

        if parser is None:
            return True

        return parser.can_fetch(
            self.user_agent,
            url
        )