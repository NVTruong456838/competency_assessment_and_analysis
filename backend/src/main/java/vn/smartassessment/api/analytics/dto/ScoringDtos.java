package vn.smartassessment.api.analytics.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ScoringDtos {
    private ScoringDtos() {}

    public record ScoreRequest(UUID requestId) {}

    public record RunSummary(
            long scoringRunId,
            long attemptId,
            long examId,
            String examCode,
            long examVersionId,
            String examVersionCode,
            long testVersionId,
            Long taxonomyReleaseId,
            String taxonomyVersion,
            String inputSha256,
            Map<String, Object> provenance,
            String scoringVersion,
            String analyticsVersion,
            BigDecimal rawScore,
            BigDecimal maxScore,
            BigDecimal accuracy,
            int totalQuestions,
            int answeredQuestions,
            int correctQuestions,
            int incorrectQuestions,
            int skippedQuestions,
            Integer totalTimeSeconds,
            BigDecimal confidence,
            Instant computedAt) {}

    public record QuestionResult(
            long testItemId,
            int order,
            String sectionName,
            Boolean correct,
            boolean skipped,
            BigDecimal score,
            BigDecimal maxScore,
            Integer timeSpentSeconds) {}

    public record SectionResult(
            String sectionName,
            BigDecimal rawScore,
            BigDecimal maxScore,
            BigDecimal accuracy,
            int totalQuestions,
            int answeredQuestions,
            int correctQuestions,
            int incorrectQuestions,
            int skippedQuestions,
            Integer timeSpentSeconds) {}

    public record SkillResult(
            long skillId,
            String skillName,
            BigDecimal accuracy,
            BigDecimal masteryScore,
            int evidenceCount,
            BigDecimal confidence,
            BigDecimal averageTimeSeconds,
            boolean sufficientEvidence) {}

    public record CompetencyResult(
            long competencyId,
            String competencyName,
            BigDecimal accuracy,
            BigDecimal masteryScore,
            int evidenceCount,
            BigDecimal confidence,
            boolean sufficientEvidence) {}

    public record DimensionResult(
            String dimensionType,
            String dimensionValue,
            int totalQuestions,
            int answeredQuestions,
            int correctQuestions,
            BigDecimal accuracy) {}

    public record ScoringResult(
            RunSummary run,
            List<SectionResult> sections,
            List<QuestionResult> questions,
            List<SkillResult> skills,
            List<CompetencyResult> competencies,
            List<DimensionResult> dimensions,
            String interpretation) {}

    public record AnalyticsSummary(
            int scoredAttempts,
            BigDecimal averageAccuracy,
            int correctAnswers,
            int incorrectAnswers,
            int skippedAnswers,
            BigDecimal averageTimeSeconds,
            Long selectedExamId,
            List<AnalyticsExam> availableExams,
            AnalyticsCohort selectedCohort,
            List<AnalyticsCohort> availableCohorts,
            BigDecimal improvement,
            List<AttemptTrendPoint> recentAttempts,
            List<SkillResult> skills,
            List<CompetencyResult> competencies) {}

    public record AttemptTrendPoint(
            long attemptId,
            Instant scoredAt,
            BigDecimal accuracy,
            BigDecimal rawScore,
            BigDecimal maxScore,
            long examId,
            String examCode,
            long examVersionId,
            String examVersionCode,
            long testVersionId,
            Long taxonomyReleaseId,
            String taxonomyVersion,
            String scoringVersion,
            String analyticsVersion) {}

    public record TeacherAnalyticsSummary(
            int scoredAttempts,
            BigDecimal averageAccuracy,
            int correctAnswers,
            int incorrectAnswers,
            int skippedAnswers,
            Long selectedExamId,
            List<AnalyticsExam> availableExams,
            AnalyticsCohort selectedCohort,
            List<AnalyticsCohort> availableCohorts,
            BigDecimal improvement,
            List<AttemptTrendPoint> recentAttempts,
            List<SkillResult> skills,
            List<CompetencyResult> competencies) {}

    public record AnalyticsExam(long examId, String examCode, String examName, Instant latestScoredAt) {}

    public record AnalyticsCohort(
            long examId,
            String examCode,
            String examName,
            long examVersionId,
            String examVersionCode,
            long testVersionId,
            String testTitle,
            Long taxonomyReleaseId,
            String taxonomyVersion,
            String scoringVersion,
            String analyticsVersion,
            Instant latestScoredAt) {}
}
