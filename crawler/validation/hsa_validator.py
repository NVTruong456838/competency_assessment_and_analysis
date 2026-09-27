from crawler.validation.tsa_validator import TsaValidator


class HsaValidator(TsaValidator):
    """Validate HSA document outputs using the shared document checks."""

    def __init__(
        self,
        input_file: str = "crawler/output/hsa_local_processed_documents.json",
        report_file: str = "crawler/output/hsa_local_validation_report.json",
    ):
        super().__init__(
            input_file=input_file,
            report_file=report_file,
            dataset_name="VNU_HSA",
        )


if __name__ == "__main__":
    HsaValidator().run()