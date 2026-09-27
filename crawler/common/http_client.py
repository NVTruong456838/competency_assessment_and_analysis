import time

import truststore
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry


truststore.inject_into_ssl()


class HttpClient:
    """
    HTTP client dùng chung cho crawler TSA/HSA.

    Chức năng:
    - Gửi HTTP GET
    - Retry khi request thất bại
    - Timeout
    - Rate limiting
    - User-Agent
    - Xử lý lỗi HTTP/network
    """

    def __init__(
        self,
        delay: float = 1.0,
        timeout: int = 20,
        retries: int = 3
    ):
        self.delay = delay
        self.timeout = timeout

        self.session = requests.Session()

        self.session.headers.update({
            "User-Agent": (
                "SmartAssessmentAI-Research/1.0 "
                "(academic research)"
            )
        })

        retry_strategy = Retry(
            total=retries,
            backoff_factor=1,
            status_forcelist=[
                429,
                500,
                502,
                503,
                504
            ],
            allowed_methods=[
                "GET"
            ],
            raise_on_status=False
        )

        adapter = HTTPAdapter(
            max_retries=retry_strategy
        )

        self.session.mount(
            "http://",
            adapter
        )

        self.session.mount(
            "https://",
            adapter
        )

    def get(self, url: str):
        """
        Gửi GET request tới URL.

        Returns:
            requests.Response nếu thành công.
            None nếu request thất bại.
        """

        try:
            print(f"[GET] {url}")

            response = self.session.get(
                url,
                timeout=self.timeout
            )

            response.raise_for_status()

            time.sleep(self.delay)

            return response

        except requests.exceptions.SSLError as e:

            print(f"[SSL ERROR] {url}")
            print(f"             {e}")

        except requests.exceptions.Timeout as e:

            print(f"[TIMEOUT] {url}")
            print(f"           {e}")

        except requests.exceptions.ConnectionError as e:

            print(f"[CONNECTION ERROR] {url}")
            print(f"                    {e}")

        except requests.exceptions.HTTPError as e:

            print(f"[HTTP ERROR] {url}")
            print(f"              {e}")

        except requests.exceptions.RequestException as e:

            print(f"[REQUEST ERROR] {url}")
            print(f"                 {e}")

        return None