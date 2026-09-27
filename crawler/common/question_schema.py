from dataclasses import dataclass, field
from typing import Any


@dataclass
class QuestionOption:
    key: str
    text: str


@dataclass
class QuestionProvenance:
    document_id: str
    source: str
    source_url: str
    page_number: int | None = None


@dataclass
class QuestionQuality:
    extraction_status: str = "needs_review"
    human_review_status: str = "pending"
    warnings: list[str] = field(default_factory=list)
    reviewer: str | None = None
    reviewed_at: str | None = None


@dataclass
class QuestionMetadata:
    exam: str | None = None
    section: str | None = None
    competency: str | None = None
    domain: str | None = None
    topic: str | None = None
    skill: str | None = None
    cognitive_level: str | None = None
    proficiency_level: str | None = None
    difficulty: dict[str, Any] = field(
        default_factory=lambda: {"planned": None, "empirical": None}
    )


@dataclass
class NormalizedQuestion:
    question_id: str
    question_number: int
    stem: str
    question_type: str
    options: list[QuestionOption]
    correct_answer: list[str] | None = None
    explanation: str | None = None
    metadata: QuestionMetadata = field(default_factory=QuestionMetadata)
    provenance: QuestionProvenance | None = None
    quality: QuestionQuality | None = None
    passage: str | None = None
    group_id: str | None = None
    group_type: str | None = None
    source_pages: list[int] = field(default_factory=list)
    visual_assets: list[dict[str, Any]] = field(default_factory=list)
    extra: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "question_id": self.question_id,
            "question_number": self.question_number,
            "stem": self.stem,
            "question_type": self.question_type,
            "options": [
                {"key": option.key, "text": option.text}
                for option in self.options
            ],
            "correct_answer": (
                list(self.correct_answer)
                if self.correct_answer is not None
                else None
            ),
            "explanation": self.explanation,
            "metadata": {
                "exam": self.metadata.exam,
                "section": self.metadata.section,
                "competency": self.metadata.competency,
                "domain": self.metadata.domain,
                "topic": self.metadata.topic,
                "skill": self.metadata.skill,
                "cognitive_level": self.metadata.cognitive_level,
                "proficiency_level": self.metadata.proficiency_level,
                "difficulty": self.metadata.difficulty,
            },
            "provenance": (
                {
                    "document_id": self.provenance.document_id,
                    "source": self.provenance.source,
                    "source_url": self.provenance.source_url,
                    "page_number": self.provenance.page_number,
                }
                if self.provenance is not None
                else None
            ),
            "quality": (
                {
                    "extraction_status": self.quality.extraction_status,
                    "human_review_status": self.quality.human_review_status,
                    "warnings": list(self.quality.warnings),
                    "reviewer": self.quality.reviewer,
                    "reviewed_at": self.quality.reviewed_at,
                }
                if self.quality is not None
                else None
            ),
            "passage": self.passage,
            "group_id": self.group_id,
            "group_type": self.group_type,
            "source_pages": list(self.source_pages),
            "visual_assets": [dict(asset) for asset in self.visual_assets],
            "extra": dict(self.extra),
        }