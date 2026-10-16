package vn.smartassessment.api.question.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public final class QuestionFoundationDtos {
    private QuestionFoundationDtos() {}

    public record ImportBatchRequest(
            @NotBlank String sourceName,
            String sourceLocator,
            @NotEmpty List<@NotNull JsonNode> questions) {}

    public record ImportBatchResult(
            long importBatchId,
            int totalQuestions,
            int validQuestions,
            int invalidQuestions,
            List<ImportItemResult> items) {}

    public record ImportPreviewItem(
            int index,
            String externalQuestionId,
            String validationStatus,
            List<String> errors) {}

    public record ImportPreviewResult(
            int totalQuestions,
            int validQuestions,
            int invalidQuestions,
            List<ImportPreviewItem> items) {}

    public record TaxonomyCandidateRegistrationRequest(
            @NotBlank String taxonomyId,
            @NotBlank String taxonomyName,
            @NotBlank String catalogType,
            @NotBlank String snapshotLocator) {}

    public record TaxonomyCandidateResult(
            long taxonomyCatalogId,
            long taxonomyReleaseId,
            String releaseId,
            String taxonomyVersion,
            String status,
            String contentSha256) {}

    public record ImportItemResult(
            long importItemId,
            String externalQuestionId,
            String validationStatus,
            List<String> errors) {}

    public record MaterializeRequest(
            @NotNull Long sectionId,
            @NotBlank String authorType) {}

    public record QuestionRevisionRequest(
            @NotNull JsonNode content,
            @NotBlank String authorType) {}

    public record QuestionGroupRequest(
            @NotBlank String title,
            @NotBlank String stimulusType,
            @NotNull JsonNode stimulus,
            @NotNull JsonNode provenance,
            @NotBlank String rightsEvidenceUri,
            @NotEmpty List<@NotNull Long> questionVersionIds) {}

    public record QuestionGroupItemRequest(
            @NotNull Long questionVersionId,
            @NotNull Integer itemOrder) {}

    public record QuestionGroupReviewRequest(
            @NotBlank String status,
            @NotBlank String reason,
            @NotEmpty List<@NotBlank String> evidenceRefs) {}

    public record QuestionGroupResult(
            long questionGroupId,
            long questionGroupVersionId,
            String groupCode,
            int versionNumber,
            String reviewStatus,
            String contentSha256) {}

    public record MaterializedQuestion(
            long questionId,
            long questionVersionId,
            String reviewStatus) {}

    public record QuestionReviewRequest(
            @NotBlank String status,
            @NotBlank String reason,
            @NotEmpty List<@NotBlank String> evidenceRefs,
            Long taxonomyReleaseId,
            String taxonomyVersion,
            String rightsEvidenceUri,
            Long supersedesReviewId) {}

    public record MappingItemRequest(
            @NotNull Long skillId,
            @NotBlank String role,
            boolean primary,
            BigDecimal weight) {}

    public record MappingSetRequest(
            @NotNull Long taxonomyReleaseId,
            @NotEmpty List<@NotNull MappingItemRequest> items) {}

    public record MappingSetReviewRequest(
            @NotBlank String status,
            @NotBlank String reason,
            @NotEmpty List<@NotBlank String> evidenceRefs) {}

    public record PatternCandidateRequest(
            @NotBlank String candidateKey,
            @NotBlank String patternType,
            @NotNull JsonNode structure,
            @NotNull JsonNode reasoning,
            @NotNull JsonNode distractorObservations,
            @NotNull JsonNode sourceEvidence,
            @NotBlank String minerVersion,
            int sourceQuestionCount) {}

    public record PatternCandidateResult(
            long patternId,
            long patternVersionId,
            String patternCode,
            int versionNumber,
            String contentSha256,
            String reviewStatus,
            String rightsStatus) {}

    public record PatternReviewRequest(
            @NotBlank String status,
            @NotBlank String reason,
            @NotEmpty List<@NotBlank String> evidenceRefs,
            String rightsEvidenceUri) {}

    public record PatternSkillMappingRequest(
            @NotNull Long taxonomyReleaseId,
            @NotNull Long skillId,
            @NotBlank String relationshipType,
            BigDecimal confidence) {}

    public record PatternSkillReviewRequest(
            @NotBlank String status,
            @NotBlank String reason,
            @NotEmpty List<@NotBlank String> evidenceRefs) {}
}
