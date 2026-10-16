package vn.smartassessment.api.question;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.ImportBatchRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.QuestionRevisionRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.QuestionReviewRequest;
import vn.smartassessment.api.question.service.QuestionFoundationService;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.jwt.issuer=https://smart-assessment.local"
})
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = "jdbc:postgresql:.+")
@Transactional
class QuestionFoundationPostgresIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private QuestionFoundationService service;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_POSTGRES_URL"));
        registry.add("spring.datasource.username", () ->
                System.getenv().getOrDefault("TEST_POSTGRES_USERNAME", "postgres"));
        registry.add("spring.datasource.password", () ->
                System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""));
    }

    @Test
    void stagesMaterializesAndRevisesQuestionWithImmutableAssetAndPassageHistory() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String reviewerEmail = "phase04-" + suffix + "@example.invalid";
        String examVersionCode = "IT-" + suffix;
        jdbc.update("""
                INSERT INTO users (username, email, password_hash, full_name)
                VALUES (?, ?, 'test-only', 'Integration Reviewer')
                """, "phase04-" + suffix, reviewerEmail);
        jdbc.update("""
                INSERT INTO exams (code, name) VALUES ('TSA', 'Test-only TSA')
                ON CONFLICT (code) DO NOTHING
                """);
        Long examId = jdbc.queryForObject(
                "SELECT exam_id FROM exams WHERE code = 'TSA'", Long.class);
        Long examVersionId = jdbc.queryForObject("""
                INSERT INTO exam_versions (exam_id, version_code, name)
                VALUES (?, ?, 'Integration Test Version')
                RETURNING exam_version_id
                """, Long.class, examId, examVersionCode);
        Long sectionId = jdbc.queryForObject("""
                INSERT INTO sections (exam_version_id, section_code, section_name, order_no)
                VALUES (?, ?, 'Integration Section', 1)
                RETURNING section_id
                """, Long.class, examVersionId, "IT-" + suffix);

        ObjectNode original = question("Question revision 1", "Passage revision 1");
        ImportBatchRequest importRequest = new ImportBatchRequest(
                "phase04-integration-" + suffix, "test-only.json", List.of(original));
        var staged = service.stageQuestions(importRequest, reviewerEmail);
        var repeated = service.stageQuestions(importRequest, reviewerEmail);
        assertThat(repeated.importBatchId()).isEqualTo(staged.importBatchId());
        assertThat(staged.validQuestions()).isEqualTo(1);

        var materialized = service.materializeQuestion(
                staged.items().get(0).importItemId(),
                new vn.smartassessment.api.question.dto.QuestionFoundationDtos.MaterializeRequest(
                        sectionId, "HUMAN"));
        Long firstVersionId = materialized.questionVersionId();
        assertThat(jdbc.queryForObject("""
                SELECT p.content FROM question_versions v
                JOIN passages p ON p.passage_id = v.passage_id
                WHERE v.question_version_id = ?
                """, String.class, firstVersionId)).isEqualTo("Passage revision 1");
        assertThat(jdbc.queryForObject("""
                SELECT rights_status FROM visual_asset WHERE question_version_id = ?
                """, String.class, firstVersionId)).isEqualTo("UNVERIFIED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM question_options WHERE question_version_id = ?
                """, Integer.class, firstVersionId)).isEqualTo(2);

        jdbc.update("UPDATE question_versions SET review_status = 'PENDING_REVIEW' WHERE question_version_id = ?",
                firstVersionId);
        jdbc.update("UPDATE question_versions SET review_status = 'IN_REVIEW' WHERE question_version_id = ?",
                firstVersionId);
        service.reviewQuestion(firstVersionId, new QuestionReviewRequest(
                "NEEDS_REVISION", "Synthetic isolated integration review.",
                List.of("INTEGRATION-TEST"), null, null, null, null), reviewerEmail);

        var revision = service.createQuestionRevision(
                firstVersionId,
                new QuestionRevisionRequest(question("Question revision 2", "Passage revision 2"), "HUMAN"));
        var history = service.questionVersionHistory(materialized.questionId());

        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("is_latest")).isEqualTo(false);
        assertThat(history.get(1).get("supersedes_question_version_id")).isEqualTo(firstVersionId);
        assertThat(history.get(1).get("content_sha256")).isNotEqualTo(history.get(0).get("content_sha256"));
        assertThat(jdbc.queryForObject("""
                SELECT p.content FROM question_versions v
                JOIN passages p ON p.passage_id = v.passage_id
                WHERE v.question_version_id = ?
                """, String.class, revision.questionVersionId())).isEqualTo("Passage revision 2");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM visual_asset WHERE question_version_id = ?
                """, Integer.class, revision.questionVersionId())).isEqualTo(1);
    }

    private ObjectNode question(String stem, String passage) {
        ObjectNode question = mapper.createObjectNode()
                .put("question_id", "integration-question")
                .put("stem", stem)
                .put("question_type", "SINGLE_CHOICE");
        question.putObject("metadata").put("exam", "TSA");
        question.putArray("options")
                .addObject().put("key", "A").put("text", "Option A");
        question.withArray("options").addObject().put("key", "B").put("text", "Option B");
        question.putArray("correct_answer").add("A");
        question.put("passage", passage);
        question.putArray("visual_assets").addObject()
                .put("asset_id", "integration-visual")
                .put("kind", "question_visual_crop")
                .put("path", "visuals/integration.png")
                .put("page_number", 1);
        return question;
    }
}
