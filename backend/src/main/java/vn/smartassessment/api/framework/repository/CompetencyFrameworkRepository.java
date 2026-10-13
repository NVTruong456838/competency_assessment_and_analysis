package vn.smartassessment.api.framework.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import vn.smartassessment.api.framework.dto.CompetencyFrameworkDtos;

@Repository
public class CompetencyFrameworkRepository {
    private static final RowMapper<CompetencyFrameworkDtos.Exam> EXAM_ROW_MAPPER =
            (result, rowNumber) -> new CompetencyFrameworkDtos.Exam(
                    result.getLong("exam_id"),
                    result.getString("code"),
                    result.getString("name"),
                    result.getString("description"),
                    result.getString("status"));

    private static final RowMapper<CompetencyFrameworkDtos.ExamVersion> EXAM_VERSION_ROW_MAPPER =
            (result, rowNumber) -> new CompetencyFrameworkDtos.ExamVersion(
                    result.getLong("exam_version_id"),
                    result.getLong("exam_id"),
                    result.getString("version_code"),
                    result.getObject("year", Short.class),
                    result.getString("name"),
                    result.getString("description"),
                    result.getString("status"),
                    result.getObject("effective_from", java.time.OffsetDateTime.class),
                    result.getObject("effective_to", java.time.OffsetDateTime.class));

    private static final RowMapper<CompetencyFrameworkDtos.Section> SECTION_ROW_MAPPER =
            (result, rowNumber) -> new CompetencyFrameworkDtos.Section(
                    result.getLong("section_id"),
                    result.getLong("exam_version_id"),
                    result.getString("section_code"),
                    result.getString("section_name"),
                    result.getString("description"),
                    result.getInt("order_no"),
                    nullableInteger(result, "question_count"),
                    nullableInteger(result, "duration_minutes"));

    private static final RowMapper<CompetencyFrameworkDtos.Competency> COMPETENCY_ROW_MAPPER =
            (result, rowNumber) -> new CompetencyFrameworkDtos.Competency(
                    result.getLong("competency_id"),
                    result.getString("competency_code"),
                    result.getString("competency_name"),
                    result.getString("description"),
                    result.getString("status"),
                    result.getBigDecimal("weight"),
                    result.getBoolean("is_primary"));

    private static final RowMapper<CompetencyFrameworkDtos.Domain> DOMAIN_ROW_MAPPER =
            (result, rowNumber) -> new CompetencyFrameworkDtos.Domain(
                    result.getLong("domain_id"),
                    result.getLong("competency_id"),
                    result.getString("domain_code"),
                    result.getString("domain_name"),
                    result.getString("description"));

    private static final RowMapper<CompetencyFrameworkDtos.Topic> TOPIC_ROW_MAPPER =
            (result, rowNumber) -> new CompetencyFrameworkDtos.Topic(
                    result.getLong("topic_id"),
                    result.getLong("domain_id"),
                    result.getString("topic_code"),
                    result.getString("topic_name"),
                    result.getString("description"));

    private static final RowMapper<CompetencyFrameworkDtos.Skill> SKILL_ROW_MAPPER =
            (result, rowNumber) -> new CompetencyFrameworkDtos.Skill(
                    result.getLong("skill_id"),
                    result.getLong("topic_id"),
                    result.getString("skill_code"),
                    result.getString("skill_name"),
                    result.getString("description"),
                    result.getString("status"));

    private final JdbcTemplate jdbcTemplate;

    public CompetencyFrameworkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CompetencyFrameworkDtos.Exam> findExams() {
        return jdbcTemplate.query("""
                SELECT exam_id, code, name, description, status
                FROM exams
                ORDER BY code, exam_id
                """, EXAM_ROW_MAPPER);
    }

    public Optional<CompetencyFrameworkDtos.Exam> findExamById(Long examId) {
        return jdbcTemplate.query("""
                SELECT exam_id, code, name, description, status
                FROM exams
                WHERE exam_id = ?
                """, EXAM_ROW_MAPPER, examId).stream().findFirst();
    }

    public boolean existsExam(Long examId) {
        return exists("SELECT EXISTS (SELECT 1 FROM exams WHERE exam_id = ?)", examId);
    }

    public List<CompetencyFrameworkDtos.ExamVersion> findExamVersions(Long examId) {
        return jdbcTemplate.query("""
                SELECT exam_version_id, exam_id, version_code, year, name, description,
                       status, effective_from, effective_to
                FROM exam_versions
                WHERE exam_id = ?
                ORDER BY year DESC NULLS LAST, version_code, exam_version_id
                """, EXAM_VERSION_ROW_MAPPER, examId);
    }

    public boolean existsExamVersion(Long examVersionId) {
        return exists("SELECT EXISTS (SELECT 1 FROM exam_versions WHERE exam_version_id = ?)",
                examVersionId);
    }

    public List<CompetencyFrameworkDtos.Section> findSections(Long examVersionId) {
        return jdbcTemplate.query("""
                SELECT section_id, exam_version_id, section_code, section_name, description,
                       order_no, question_count, duration_minutes
                FROM sections
                WHERE exam_version_id = ?
                ORDER BY order_no, section_id
                """, SECTION_ROW_MAPPER, examVersionId);
    }

    public boolean existsSection(Long sectionId) {
        return exists("SELECT EXISTS (SELECT 1 FROM sections WHERE section_id = ?)", sectionId);
    }

    public List<CompetencyFrameworkDtos.Competency> findCompetenciesBySection(Long sectionId) {
        return jdbcTemplate.query("""
                SELECT competency.competency_id, competency.competency_code,
                       competency.competency_name, competency.description, competency.status,
                       section_competency.weight, section_competency.is_primary
                FROM section_competencies section_competency
                JOIN competencies competency
                  ON competency.competency_id = section_competency.competency_id
                WHERE section_competency.section_id = ?
                ORDER BY competency.competency_code, competency.competency_id
                """, COMPETENCY_ROW_MAPPER, sectionId);
    }

    public boolean existsCompetency(Long competencyId) {
        return exists("SELECT EXISTS (SELECT 1 FROM competencies WHERE competency_id = ?)",
                competencyId);
    }

    public List<CompetencyFrameworkDtos.Domain> findDomains(Long competencyId) {
        return jdbcTemplate.query("""
                SELECT domain_id, competency_id, domain_code, domain_name, description
                FROM domains
                WHERE competency_id = ?
                ORDER BY domain_code, domain_id
                """, DOMAIN_ROW_MAPPER, competencyId);
    }

    public boolean existsDomain(Long domainId) {
        return exists("SELECT EXISTS (SELECT 1 FROM domains WHERE domain_id = ?)", domainId);
    }

    public List<CompetencyFrameworkDtos.Topic> findTopics(Long domainId) {
        return jdbcTemplate.query("""
                SELECT topic_id, domain_id, topic_code, topic_name, description
                FROM topics
                WHERE domain_id = ?
                ORDER BY topic_code, topic_id
                """, TOPIC_ROW_MAPPER, domainId);
    }

    public boolean existsTopic(Long topicId) {
        return exists("SELECT EXISTS (SELECT 1 FROM topics WHERE topic_id = ?)", topicId);
    }

    public List<CompetencyFrameworkDtos.Skill> findSkills(Long topicId) {
        return jdbcTemplate.query("""
                SELECT skill_id, topic_id, skill_code, skill_name, description, status
                FROM skills
                WHERE topic_id = ?
                ORDER BY skill_code, skill_id
                """, SKILL_ROW_MAPPER, topicId);
    }

    private boolean exists(String sql, Long id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, id));
    }

    private static Integer nullableInteger(ResultSet result, String column) throws SQLException {
        return result.getObject(column, Integer.class);
    }
}
