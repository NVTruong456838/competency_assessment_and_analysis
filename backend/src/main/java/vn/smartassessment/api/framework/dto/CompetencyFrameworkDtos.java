package vn.smartassessment.api.framework.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public final class CompetencyFrameworkDtos {
    private CompetencyFrameworkDtos() {}

    public record Exam(
            Long examId,
            String code,
            String name,
            String description,
            String status) {}

    public record ExamVersion(
            Long examVersionId,
            Long examId,
            String versionCode,
            Short year,
            String name,
            String description,
            String status,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo) {}

    public record Section(
            Long sectionId,
            Long examVersionId,
            String sectionCode,
            String sectionName,
            String description,
            Integer orderNo,
            Integer questionCount,
            Integer durationMinutes) {}

    public record Competency(
            Long competencyId,
            String competencyCode,
            String competencyName,
            String description,
            String status,
            BigDecimal weight,
            boolean isPrimary) {}

    public record Domain(
            Long domainId,
            Long competencyId,
            String domainCode,
            String domainName,
            String description) {}

    public record Topic(
            Long topicId,
            Long domainId,
            String topicCode,
            String topicName,
            String description) {}

    public record Skill(
            Long skillId,
            Long topicId,
            String skillCode,
            String skillName,
            String description,
            String status) {}
}
