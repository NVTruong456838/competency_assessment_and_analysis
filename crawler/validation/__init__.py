__all__ = ["TsaValidator"]


def __getattr__(name):
	if name == "TsaValidator":
		from crawler.validation.tsa_validator import TsaValidator

		return TsaValidator

	raise AttributeError(name)
