package vn.smartassessment.api.assessment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AssessmentDtos {
    private AssessmentDtos() {}

    public record SectionRule(
            @NotNull Long sectionId,
            @Min(1) int questionCount,
            @Min(1) Integer durationMinutes) {}

    public record SelectionRules(
            List<@NotNull Long> skillIds,
            Map<String, @Min(0) @Max(100) Integer> cognitiveLevelDistribution,
            Map<String, @Min(0) @Max(100) Integer> difficultyDistribution,
            Map<String, @Min(0) @Max(100) Integer> questionTypeDistribution) {}

    public record OptionalBranchSelection(
            @NotNull Long optionalSectionGroupId,
            @NotBlank String branchCode) {}

    public record BlueprintRequest(
            @NotBlank String title,
            String description,
            @NotNull Long examVersionId,
            @NotBlank String testType,
            @Min(1) int durationMinutes,
            @NotEmpty List<@Valid SectionRule> sections,
            @NotNull @Valid SelectionRules selectionRules,
            List<@Valid OptionalBranchSelection> optionalBranchSelections) {}

    public record BlueprintVersion(
            long blueprintId,
            long blueprintVersionId,
            String blueprintCode,
            String title,
            String exam,
            String examVersion,
            int versionNumber,
            String testType,
            int durationMinutes,
            int totalQuestions,
            List<SectionRule> sections,
            SelectionRules selectionRules,
            List<OptionalBranchSelection> optionalBranchSelections,
            String status) {}

    public record BuildRequest(
            @NotNull Long blueprintVersionId,
            @NotNull Long taxonomyReleaseId,
            @NotNull Long seed,
            @NotBlank String title) {}

    public record MissingCoverage(
            long sectionId,
            int requested,
            int selected,
            int eligibleAvailable,
            List<String> constraints) {}

    public record BuildResult(
            Long testId,
            Long testVersionId,
            String status,
            long seed,
            int requestedQuestions,
            int selectedQuestions,
            String contentSha256,
            List<MissingCoverage> missingCoverage) {}

    public record AvailableTest(
            long testVersionId,
            long testId,
            String title,
            String description,
            String exam,
            String testType,
            int durationMinutes,
            int totalQuestions,
            String taxonomyVersion,
            Long activeAttemptId) {}

    public record AttemptSummary(
            long attemptId,
            String status,
            Instant startedAt,
            Instant endsAt,
            long remainingSeconds,
            int totalQuestions,
            int answeredQuestions,
            int markedForReview) {}

    public record ExamQuestion(
            long testItemId,
            int order,
            long questionVersionId,
            String questionType,
            String responseMode,
            String stem,
            String cognitiveLevel,
            BigDecimal estimatedDifficulty,
            String sectionName,
            String passageTitle,
            String passageContent,
            List<Map<String, Object>> options,
            List<Map<String, Object>> visualAssets,
            List<String> selectedOptionKeys,
            String answerText,
            Integer revision,
            boolean markedForReview,
            QuestionGroupStimulus groupStimulus) {
        public ExamQuestion(
                long testItemId, int order, long questionVersionId, String questionType,
                String responseMode, String stem, String cognitiveLevel,
                BigDecimal estimatedDifficulty, String sectionName, String passageTitle,
                String passageContent, List<Map<String, Object>> options,
                List<Map<String, Object>> visualAssets, List<String> selectedOptionKeys,
                String answerText, Integer revision, boolean markedForReview) {
            this(testItemId, order, questionVersionId, questionType, responseMode, stem,
                    cognitiveLevel, estimatedDifficulty, sectionName, passageTitle,
                    passageContent, options, visualAssets, selectedOptionKeys, answerText,
                    revision, markedForReview, null);
        }
    }

    public record QuestionGroupStimulus(
            long groupVersionId,
            String title,
            String stimulusType,
            JsonNode stimulus,
            int itemOrder,
            int itemCount) {}

    public record ExamSession(
            AttemptSummary attempt,
            String title,
            String exam,
            List<ExamQuestion> questions) {}

    public record SaveAnswerRequest(
            List<@NotBlank String> selectedOptionKeys,
            String answerText,
            @NotNull Integer expectedRevision,
            @NotNull UUID requestId) {}

    public record SaveAnswerResult(
            long testItemId,
            int revision,
            boolean markedForReview,
            boolean idempotentReplay) {}

    public record ReviewRequest(
            @NotNull Boolean markedForReview,
            @NotNull Integer expectedRevision,
            @NotNull UUID requestId) {}

    public record SubmitResult(
            long attemptId,
            String status,
            Instant submittedAt,
            boolean autoSubmitted,
            int answeredQuestions,
            int totalQuestions) {}
}
