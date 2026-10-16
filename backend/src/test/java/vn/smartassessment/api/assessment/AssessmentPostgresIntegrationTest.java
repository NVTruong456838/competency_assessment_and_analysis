package vn.smartassessment.api.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.BlueprintRequest;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.BuildRequest;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.OptionalBranchSelection;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.SectionRule;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.SelectionRules;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.SaveAnswerRequest;
import vn.smartassessment.api.assessment.service.AssessmentService;
import vn.smartassessment.api.analytics.dto.ScoringDtos;
import vn.smartassessment.api.analytics.service.ScoringService;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.ImportBatchRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.MappingItemRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.MappingSetRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.MappingSetReviewRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.MaterializeRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.QuestionReviewRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.QuestionGroupRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.QuestionGroupItemRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.QuestionGroupReviewRequest;
import vn.smartassessment.api.question.service.QuestionFoundationService;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.jwt.issuer=https://smart-assessment.local"
})
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = "jdbc:postgresql:.+")
@Transactional
class AssessmentPostgresIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private QuestionFoundationService questionService;

    @Autowired
    private AssessmentService assessmentService;

    @Autowired
    private ScoringService scoringService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_POSTGRES_URL"));
        registry.add("spring.datasource.username", () ->
                System.getenv().getOrDefault("TEST_POSTGRES_USERNAME", "postgres"));
        registry.add("spring.datasource.password", () ->
                System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""));
    }

    @Test
    void testVersionsCannotBePublishedWithoutReleaseEvidence() {
        Fixture fixture = createDraftTestFixture();

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE test_versions
                SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                    published_by = ?, content_sha256 = ?
                WHERE test_version_id = ?
                """, fixture.userId(), "a".repeat(64), fixture.testVersionId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("New published test versions require a taxonomy release");
    }

    @Test
    void submittedAttemptsRejectAnswerWrites() {
        Fixture fixture = createDraftTestFixture();
        Long attemptId = jdbc.queryForObject("""
                INSERT INTO attempts
                    (test_version_id, user_id, attempt_number, status, submitted_at, ends_at)
                VALUES (?, ?, 1, 'SUBMITTED', CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP + INTERVAL '10 minutes')
                RETURNING attempt_id
                """, Long.class, fixture.testVersionId(), fixture.userId());

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO attempt_answers
                    (attempt_id, test_item_id, test_version_id, question_version_id, answer_text)
                VALUES (?, ?, ?, ?, 'test-only')
                """, attemptId, fixture.testItemId(), fixture.testVersionId(), fixture.questionVersionId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Answers cannot be changed");
    }

    @Test
    void submittedAttemptScoringIsVersionedIdempotentPrivateAndImmutable() {
        Fixture fixture = createDraftTestFixture();
        long attemptId = createActiveAttempt(fixture, false);
        assessmentService.saveAnswer(
                attemptId, fixture.testItemId(), fixture.userId(),
                new SaveAnswerRequest(List.of("A"), null, 0, UUID.randomUUID()));
        assessmentService.submitAttempt(attemptId, fixture.userId());
        UUID requestId = UUID.randomUUID();

        ScoringDtos.ScoringResult first = scoringService.scoreAttempt(
                attemptId, fixture.userId(), requestId);
        ScoringDtos.ScoringResult retry = scoringService.scoreAttempt(
                attemptId, fixture.userId(), requestId);

        assertThat(first.run().scoringRunId()).isEqualTo(retry.run().scoringRunId());
        assertThat(first.run().scoringVersion()).isEqualTo("objective-exact-v1");
        assertThat(first.run().correctQuestions()).isEqualTo(1);
        assertThat(first.run().rawScore()).isEqualByComparingTo("1");
        assertThat(first.questions()).singleElement()
                .satisfies(result -> {
                    assertThat(result.correct()).isTrue();
                    assertThat(result.timeSpentSeconds()).isNull();
                });
        assertThat(first.sections()).singleElement()
                .satisfies(section -> assertThat(section.timeSpentSeconds()).isNull());
        ScoringDtos.AnalyticsSummary analytics = scoringService.studentAnalytics(fixture.userId());
        assertThat(analytics.recentAttempts()).singleElement().satisfies(point -> {
            assertThat(point.attemptId()).isEqualTo(attemptId);
            assertThat(point.rawScore()).isEqualByComparingTo("1");
            assertThat(point.maxScore()).isEqualByComparingTo("1");
        });
        assertThatThrownBy(() -> scoringService.studentResult(attemptId, fixture.userId() + 100_000))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Scoring result was not found");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE scoring_runs SET accuracy = 0 WHERE scoring_run_id = ?",
                first.run().scoringRunId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Scoring run history is append-only");
    }

    @Test
    void studentAnalyticsSeparatesExamAndExactVersionCohorts() {
        Fixture tsaVersionOne = createDraftTestFixture("TSA");
        Fixture tsaVersionTwo = createDraftTestFixture("TSA");
        Fixture hsa = createDraftTestFixture("HSA");
        long studentId = tsaVersionOne.userId();

        long firstTsaAttempt = scoreFixtureAttempt(tsaVersionOne, studentId, true);
        long secondTsaAttempt = scoreFixtureAttempt(tsaVersionOne, studentId, false);
        scoringService.scoreAttempt(secondTsaAttempt, studentId, UUID.randomUUID());
        long otherTsaVersionAttempt = scoreFixtureAttempt(tsaVersionTwo, studentId, true);
        long hsaAttempt = scoreFixtureAttempt(hsa, studentId, true);

        ScoringDtos.AnalyticsSummary all = scoringService.studentAnalytics(studentId);
        assertThat(all.scoredAttempts()).isEqualTo(1);
        assertThat(all.selectedCohort().examCode()).isEqualTo("HSA");
        assertThat(all.selectedCohort().testVersionId()).isEqualTo(hsa.testVersionId());
        assertThat(all.availableExams()).extracting(ScoringDtos.AnalyticsExam::examCode)
                .contains("TSA", "HSA");
        assertThat(all.recentAttempts()).singleElement().satisfies(point -> {
            assertThat(point.attemptId()).isEqualTo(hsaAttempt);
            assertThat(point.examCode()).isEqualTo("HSA");
            assertThat(point.testVersionId()).isEqualTo(hsa.testVersionId());
            assertThat(point.scoringVersion()).isEqualTo(all.selectedCohort().scoringVersion());
            assertThat(point.analyticsVersion()).isEqualTo(all.selectedCohort().analyticsVersion());
        });

        ScoringDtos.AnalyticsSummary tsa = scoringService.studentAnalytics(
                studentId, tsaVersionOneExamId(tsaVersionOne), null, tsaVersionOne.testVersionId(), null);
        assertThat(tsa.scoredAttempts()).isEqualTo(2);
        assertThat(tsa.selectedCohort().examCode()).isEqualTo("TSA");
        assertThat(tsa.selectedCohort().testVersionId()).isEqualTo(tsaVersionOne.testVersionId());
        assertThat(tsa.improvement()).isEqualByComparingTo("-1.0000");
        assertThat(tsa.recentAttempts()).extracting(ScoringDtos.AttemptTrendPoint::attemptId)
                .containsExactly(firstTsaAttempt, secondTsaAttempt);
        assertThat(otherTsaVersionAttempt).isNotIn(
                tsa.recentAttempts().stream().map(ScoringDtos.AttemptTrendPoint::attemptId).toList());
    }

    @Test
    void unmappedQuestionsRemainInExamScoreAndTimeUsesOnlyRecordedEvidence() {
        Fixture fixture = createDraftTestFixture();
        long attemptId = createActiveAttempt(fixture, false);
        assessmentService.saveAnswer(
                attemptId, fixture.testItemId(), fixture.userId(),
                new SaveAnswerRequest(List.of("A"), null, 0, UUID.randomUUID()));
        jdbc.update("""
                UPDATE attempt_answers SET time_spent_seconds = 17
                WHERE attempt_id = ? AND test_item_id = ?
                """, attemptId, fixture.testItemId());
        assessmentService.submitAttempt(attemptId, fixture.userId());

        ScoringDtos.ScoringResult result = scoringService.scoreAttempt(
                attemptId, fixture.userId(), UUID.randomUUID());

        assertThat(result.run().rawScore()).isEqualByComparingTo("1");
        assertThat(result.run().maxScore()).isEqualByComparingTo("1");
        assertThat(result.run().accuracy()).isEqualByComparingTo("1");
        assertThat(result.skills()).isEmpty();
        assertThat(result.questions()).singleElement()
                .satisfies(question -> {
                    assertThat(question.correct()).isTrue();
                    assertThat(question.timeSpentSeconds()).isEqualTo(17);
                });
        assertThat(result.sections()).singleElement()
                .satisfies(section -> assertThat(section.timeSpentSeconds()).isEqualTo(17));
        String unmappedQuestionVersionId = jdbc.queryForObject("""
                SELECT provenance_json->'unmappedQuestionVersionIds'->>0
                FROM scoring_runs WHERE scoring_run_id = ?
                """, String.class, result.run().scoringRunId());
        assertThat(unmappedQuestionVersionId).isEqualTo(String.valueOf(fixture.questionVersionId()));
    }

    @Test
    void weightedMultiSkillAnalyticsNormalizeWeightsAcrossQuestionEvidence() {
        PublishedBankFixture fixture = createPublishedBankFixture(3, false, 0, "PASSAGE", true);
        SelectionRules selection = new SelectionRules(
                List.of(fixture.skillId()), Map.of(), Map.of(), Map.of());
        var blueprint = assessmentService.createBlueprint(
                blueprintRequest(fixture, "DIAGNOSTIC", 3, selection), fixture.email());
        var build = assessmentService.buildTest(
                new BuildRequest(blueprint.blueprintVersionId(), fixture.taxonomyReleaseId(),
                        71024L, "Weighted multi-skill fixture"), fixture.email());
        long attemptId = createActiveAttempt(build.testVersionId(), fixture.userId(), false);

        List<Map<String, Object>> weightedItems = jdbc.queryForList("""
                SELECT ti.test_item_id, ti.question_version_id, MAX(m.weight) AS skill_a_weight
                FROM test_items ti
                JOIN question_mapping_set_items m ON m.mapping_set_id = ti.mapping_set_id
                WHERE ti.test_version_id = ? AND m.skill_id = ?
                GROUP BY ti.test_item_id, ti.question_version_id
                ORDER BY MIN(ti.item_order)
                """, build.testVersionId(), fixture.skillId());
        assertThat(weightedItems).hasSize(3);
        for (Map<String, Object> item : weightedItems) {
            long testItemId = ((Number) item.get("test_item_id")).longValue();
            BigDecimal skillAWeight = (BigDecimal) item.get("skill_a_weight");
            String selectedAnswer = skillAWeight.signum() == 0
                    || skillAWeight.compareTo(new BigDecimal("0.8")) == 0 ? "A" : "B";
            assessmentService.saveAnswer(
                    attemptId, testItemId, fixture.userId(),
                    new SaveAnswerRequest(List.of(selectedAnswer), null, 0, UUID.randomUUID()));
        }
        assessmentService.submitAttempt(attemptId, fixture.userId());

        ScoringDtos.ScoringResult result = scoringService.scoreAttempt(
                attemptId, fixture.userId(), UUID.randomUUID());

        assertThat(result.run().accuracy()).isEqualByComparingTo("0.6667");
        assertThat(result.skills()).hasSize(2);
        assertThat(result.skills()).filteredOn(skill -> skill.skillId() == fixture.skillId())
                .singleElement().satisfies(skill -> {
                    assertThat(skill.accuracy()).isEqualByComparingTo("0.8");
                    assertThat(skill.evidenceCount()).isEqualTo(2);
                });
        Map<String, Object> zeroWeightItem = weightedItems.stream()
                .filter(item -> ((BigDecimal) item.get("skill_a_weight")).signum() == 0)
                .findFirst().orElseThrow();
        String zeroWeightQuestionVersionId = jdbc.queryForObject("""
                SELECT provenance_json->'ineligibleMappingQuestionVersionIds'->>0
                FROM scoring_runs WHERE scoring_run_id = ?
                """, String.class, result.run().scoringRunId());
        assertThat(zeroWeightQuestionVersionId).isEqualTo(
                String.valueOf(((Number) zeroWeightItem.get("question_version_id")).longValue()));
        assertThat(result.skills()).filteredOn(skill -> skill.skillId() == fixture.secondarySkillId())
                .singleElement().satisfies(skill -> {
                    assertThat(skill.accuracy()).isEqualByComparingTo("0.2");
                    assertThat(skill.evidenceCount()).isEqualTo(2);
                });
    }

    @Test
    void ineligibleDraftMappingIsExcludedAndRecordedInProvenance() {
        PublishedBankFixture fixture = createPublishedBankFixture(2, false);
        SelectionRules selection = new SelectionRules(
                List.of(fixture.skillId()), Map.of(), Map.of(), Map.of());
        var blueprint = assessmentService.createBlueprint(
                blueprintRequest(fixture, "DIAGNOSTIC", 2, selection), fixture.email());
        var build = assessmentService.buildTest(
                new BuildRequest(blueprint.blueprintVersionId(), fixture.taxonomyReleaseId(),
                        71025L, "Ineligible mapping fixture"), fixture.email());
        List<Map<String, Object>> testItems = jdbc.queryForList("""
                SELECT test_item_id, question_version_id
                FROM test_items WHERE test_version_id = ? ORDER BY item_order
                """, build.testVersionId());
        assertThat(testItems).hasSize(2);

        long draftMappingSetId = jdbc.queryForObject("""
                INSERT INTO question_mapping_sets
                    (question_version_id, taxonomy_release_id, mapping_revision, status)
                SELECT ?, ?, COALESCE(MAX(mapping_revision), 0) + 1, 'DRAFT'
                FROM question_mapping_sets WHERE question_version_id = ?
                RETURNING mapping_set_id
                """, Long.class,
                ((Number) testItems.get(1).get("question_version_id")).longValue(),
                fixture.taxonomyReleaseId(),
                ((Number) testItems.get(1).get("question_version_id")).longValue());
        jdbc.update("""
                UPDATE test_items SET mapping_set_id = ?
                WHERE test_item_id = ?
                """, draftMappingSetId, ((Number) testItems.get(1).get("test_item_id")).longValue());
        jdbc.update("""
                UPDATE test_items SET mapping_set_id = NULL, taxonomy_release_id = NULL
                WHERE test_item_id = ?
                """, ((Number) testItems.get(0).get("test_item_id")).longValue());

        long attemptId = createActiveAttempt(build.testVersionId(), fixture.userId(), false);
        for (Map<String, Object> item : testItems) {
            assessmentService.saveAnswer(
                    attemptId,
                    ((Number) item.get("test_item_id")).longValue(),
                    fixture.userId(),
                    new SaveAnswerRequest(List.of("A"), null, 0, UUID.randomUUID()));
        }
        assessmentService.submitAttempt(attemptId, fixture.userId());
        ScoringDtos.ScoringResult result = scoringService.scoreAttempt(
                attemptId, fixture.userId(), UUID.randomUUID());

        assertThat(result.run().correctQuestions()).isEqualTo(2);
        assertThat(result.skills()).isEmpty();
        List<String> excludedQuestionVersionIds = jdbc.queryForList("""
                SELECT provenance_json->'unmappedQuestionVersionIds'->>0
                FROM scoring_runs WHERE scoring_run_id = ?
                UNION ALL
                SELECT provenance_json->'ineligibleMappingQuestionVersionIds'->>0
                FROM scoring_runs WHERE scoring_run_id = ?
                """, String.class, result.run().scoringRunId(), result.run().scoringRunId());
        assertThat(excludedQuestionVersionIds).containsExactlyInAnyOrder(
                String.valueOf(((Number) testItems.get(0).get("question_version_id")).longValue()),
                String.valueOf(((Number) testItems.get(1).get("question_version_id")).longValue()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentScoringRetriesAreIdempotentAndFailedAttemptCanRetry() throws Exception {
        Fixture fixture = createDraftTestFixture();
        long attemptId = createActiveAttempt(fixture, false);
        UUID requestId = UUID.randomUUID();

        assertThatThrownBy(() -> scoringService.scoreAttempt(attemptId, fixture.userId(), requestId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM scoring_runs WHERE attempt_id = ?
                """, Integer.class, attemptId)).isZero();

        assessmentService.saveAnswer(
                attemptId, fixture.testItemId(), fixture.userId(),
                new SaveAnswerRequest(List.of("A"), null, 0, UUID.randomUUID()));
        assessmentService.submitAttempt(attemptId, fixture.userId());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> scoreAtBarrier(
                    ready, start, attemptId, fixture.userId(), requestId));
            var second = executor.submit(() -> scoreAtBarrier(
                    ready, start, attemptId, fixture.userId(), requestId));
            ready.await();
            start.countDown();

            long firstRunId = first.get().run().scoringRunId();
            long secondRunId = second.get().run().scoringRunId();
            assertThat(firstRunId).isEqualTo(secondRunId);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM scoring_runs
                    WHERE attempt_id = ? AND request_id = ?
                    """, Integer.class, attemptId, requestId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM scoring_question_results WHERE scoring_run_id = ?
                    """, Integer.class, firstRunId)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void questionGroupsKeepOrderedDraftMembershipAndValidateStructuredStimulus() {
        Fixture fixture = createDraftTestFixture();
        String actor = jdbc.queryForObject(
                "SELECT email FROM users WHERE user_id = ?", String.class, fixture.userId());
        String suffix = UUID.randomUUID().toString().replace("-", "");
        ObjectNode secondQuestion = mapper.createObjectNode()
                .put("question_id", "phase05-group-" + suffix)
                .put("stem", "Synthetic second grouped question")
                .put("question_type", "SINGLE_CHOICE");
        secondQuestion.putObject("metadata").put("exam", "TSA");
        secondQuestion.putArray("options").addObject().put("key", "A").put("text", "Option A");
        secondQuestion.withArray("options").addObject().put("key", "B").put("text", "Option B");
        secondQuestion.putArray("correct_answer").add("A");
        var imported = questionService.stageQuestions(
                new ImportBatchRequest("phase05-group-" + suffix, "test-only.json", List.of(secondQuestion)),
                actor);
        long secondVersionId = questionService.materializeQuestion(
                imported.items().get(0).importItemId(),
                new MaterializeRequest(sectionIdFor(fixture), "HUMAN")).questionVersionId();
        ObjectNode stimulus = mapper.createObjectNode()
                .put("title", "Shared synthetic passage")
                .put("content", "Synthetic passage content shared by both questions.");
        var group = questionService.createQuestionGroup(new QuestionGroupRequest(
                "Synthetic grouped stimulus", "PASSAGE", stimulus, mapper.createObjectNode(),
                "test-fixture://phase05/group-rights",
                List.of(fixture.questionVersionId(), secondVersionId)), actor);

        assertThat(group.reviewStatus()).isEqualTo("DRAFT");
        Map<String, Object> snapshot = questionService.questionGroupVersion(group.questionGroupVersionId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) snapshot.get("items");
        assertThat(items).extracting(item -> item.get("question_version_id"))
                .containsExactly(fixture.questionVersionId(), secondVersionId);
        assertThat(items).extracting(item -> item.get("item_order")).containsExactly(1, 2);

        Long thirdVersionId = materializeDraftGroupMember(actor, suffix, sectionIdFor(fixture));
        questionService.addQuestionGroupItem(group.questionGroupVersionId(),
                new QuestionGroupItemRequest(thirdVersionId, 3));
        questionService.removeQuestionGroupItem(group.questionGroupVersionId(), thirdVersionId);
        ObjectNode invalidTable = mapper.createObjectNode();
        invalidTable.putArray("columns").addObject().put("key", "x").put("label", "X");
        invalidTable.putArray("rows").add(mapper.createArrayNode());
        assertThatThrownBy(() -> questionService.createQuestionGroup(new QuestionGroupRequest(
                "Invalid table", "TABLE",
                invalidTable,
                mapper.createObjectNode(), "test-fixture://phase05/group-rights",
                List.of(fixture.questionVersionId(), secondVersionId)), actor))
                .isInstanceOf(ResponseStatusException.class);

        ObjectNode table = mapper.createObjectNode();
        table.putArray("columns")
                .addObject().put("key", "year").put("label", "Year");
        table.withArray("columns").addObject().put("key", "value").put("label", "Value");
        table.putArray("rows").add(mapper.createArrayNode().add(2024).add(17));
        long tableGroup = questionService.createQuestionGroup(new QuestionGroupRequest(
                "Synthetic data table", "TABLE", table, mapper.createObjectNode(),
                "test-fixture://phase05/group-rights",
                List.of(materializeDraftGroupMember(actor, suffix + "-table-a", sectionIdFor(fixture)),
                        materializeDraftGroupMember(actor, suffix + "-table-b", sectionIdFor(fixture)))),
                actor).questionGroupVersionId();
        assertThat(questionService.questionGroupVersion(tableGroup).get("stimulus_type")).isEqualTo("TABLE");

        ObjectNode chart = mapper.createObjectNode();
        chart.putArray("labels").add("A").add("B");
        chart.putArray("series").addObject()
                .put("name", "Synthetic series").putArray("values").add(2).add(3);
        long chartGroup = questionService.createQuestionGroup(new QuestionGroupRequest(
                "Synthetic data chart", "CHART", chart, mapper.createObjectNode(),
                "test-fixture://phase05/group-rights",
                List.of(materializeDraftGroupMember(actor, suffix + "-chart-a", sectionIdFor(fixture)),
                        materializeDraftGroupMember(actor, suffix + "-chart-b", sectionIdFor(fixture)))),
                actor).questionGroupVersionId();
        assertThat(questionService.questionGroupVersion(chartGroup).get("stimulus_type")).isEqualTo("CHART");
    }

    @Test
    void questionGroupPublicationRequiresApprovedPublishedMembers() {
        Fixture fixture = createDraftTestFixture();
        String actor = jdbc.queryForObject(
                "SELECT email FROM users WHERE user_id = ?", String.class, fixture.userId());
        Long secondVersionId = materializeDraftGroupMember(
                actor, UUID.randomUUID().toString().replace("-", ""), sectionIdFor(fixture));
        ObjectNode stimulus = mapper.createObjectNode().put("title", "Shared").put("content", "Test only.");
        var group = questionService.createQuestionGroup(new QuestionGroupRequest(
                "Immutable group", "PASSAGE", stimulus, mapper.createObjectNode(),
                "test-fixture://phase05/group-rights",
                List.of(fixture.questionVersionId(), secondVersionId)), actor);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE question_group_versions
                SET review_status = 'PUBLISHED', content_sha256 = ?, published_at = CURRENT_TIMESTAMP,
                    published_by = ?
                WHERE question_group_version_id = ?
                """, "a".repeat(64), fixture.userId(), group.questionGroupVersionId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(
                        "Question Group publication requires at least two published, rights-approved members");
    }

    @Test
    void answerAutosaveIsIdempotentAndRejectsStaleRevisions() {
        Fixture fixture = createDraftTestFixture();
        long attemptId = createActiveAttempt(fixture, false);
        UUID requestId = UUID.randomUUID();
        SaveAnswerRequest firstRequest = new SaveAnswerRequest(List.of("A"), null, 0, requestId);

        var saved = assessmentService.saveAnswer(attemptId, fixture.testItemId(), fixture.userId(), firstRequest);
        var replay = assessmentService.saveAnswer(attemptId, fixture.testItemId(), fixture.userId(), firstRequest);

        assertThat(saved.revision()).isEqualTo(1);
        assertThat(replay.revision()).isEqualTo(1);
        assertThat(replay.idempotentReplay()).isTrue();
        assertThatThrownBy(() -> assessmentService.saveAnswer(
                attemptId,
                fixture.testItemId(),
                fixture.userId(),
                new SaveAnswerRequest(List.of("B"), null, 0, UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbc.queryForObject("""
                SELECT revision FROM attempt_answers WHERE attempt_id = ? AND test_item_id = ?
                """, Integer.class, attemptId, fixture.testItemId())).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentAnswerSavesSerializeAndRejectOneStaleRevision() throws Exception {
        Fixture fixture = createDraftTestFixture();
        long attemptId = createActiveAttempt(fixture, false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> saveAtSameRevision(
                    ready, start, attemptId, fixture, "A"));
            var second = executor.submit(() -> saveAtSameRevision(
                    ready, start, attemptId, fixture, "B"));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject("""
                    SELECT revision FROM attempt_answers WHERE attempt_id = ? AND test_item_id = ?
                    """, Integer.class, attemptId, fixture.testItemId())).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void attemptAccessIsScopedToItsOwnerAndRepeatedSubmissionIsIdempotent() {
        Fixture fixture = createDraftTestFixture();
        long attemptId = createActiveAttempt(fixture, false);

        assertThatThrownBy(() -> assessmentService.resumeAttempt(attemptId, fixture.userId() + 1))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        var submitted = assessmentService.submitAttempt(attemptId, fixture.userId());
        var replay = assessmentService.submitAttempt(attemptId, fixture.userId());
        assertThat(replay).isEqualTo(submitted);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM attempt_events
                WHERE attempt_id = ? AND event_type = 'SUBMITTED'
                """, Integer.class, attemptId)).isEqualTo(1);
    }

    @Test
    void attemptCanResumeAndExpiredAttemptsAreAutoSubmitted() {
        Fixture fixture = createDraftTestFixture();
        long activeAttemptId = createActiveAttempt(fixture, false);

        var resumed = assessmentService.resumeAttempt(activeAttemptId, fixture.userId());
        assertThat(resumed.attempt().status()).isEqualTo("IN_PROGRESS");
        assertThat(resumed.questions()).hasSize(1);
        assertThat(resumed.questions().get(0).stem()).isEqualTo("Synthetic integration question");

        long submittedAfterDeadlineId = createActiveAttempt(fixture, true);
        var submittedAfterDeadline = assessmentService.submitAttempt(
                submittedAfterDeadlineId, fixture.userId());
        assertThat(submittedAfterDeadline.status()).isEqualTo("AUTO_SUBMITTED");
        assertThat(submittedAfterDeadline.autoSubmitted()).isTrue();

        long expiredAttemptId = createActiveAttempt(fixture, true);
        assessmentService.autoSubmitExpiredAttempts();

        assertThat(jdbc.queryForObject("""
                SELECT status FROM attempts WHERE attempt_id = ?
                """, String.class, expiredAttemptId)).isEqualTo("AUTO_SUBMITTED");
        assertThat(jdbc.queryForObject("""
                SELECT auto_submitted FROM attempts WHERE attempt_id = ?
                """, Boolean.class, expiredAttemptId)).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void testBuilderHonorsQuestionBankGatesCoverageAndBlueprintRules() {
        PublishedBankFixture fixture = createPublishedBankFixture(3, true);
        SelectionRules distributions = new SelectionRules(
                List.of(fixture.skillId()),
                Map.of("M1", 34, "M2", 33, "M3", 33),
                Map.of("EASY", 34, "MEDIUM", 33, "HARD", 33),
                Map.of("SINGLE_CHOICE", 100));
        List<String> testTypes = List.of("DIAGNOSTIC", "FULL_MOCK", "MINI_MOCK");
        List<Long> testVersionIds = new java.util.ArrayList<>();

        for (String testType : testTypes) {
            var blueprint = assessmentService.createBlueprint(
                    blueprintRequest(fixture, testType, 3, distributions), fixture.email());
            var build = assessmentService.buildTest(
                    new BuildRequest(blueprint.blueprintVersionId(), fixture.taxonomyReleaseId(),
                            71023L, "Synthetic " + testType), fixture.email());

            assertThat(build.status()).as("Builder result: %s", build).isEqualTo("DRAFT");
            assertThat(build.seed()).isEqualTo(71023L);
            assertThat(build.requestedQuestions()).isEqualTo(3);
            assertThat(build.selectedQuestions()).isEqualTo(3);
            assertThat(build.contentSha256()).hasSize(64);
            testVersionIds.add(build.testVersionId());
            assertThat(jdbc.queryForObject("""
                    SELECT builder_seed FROM test_versions WHERE test_version_id = ?
                    """, Long.class, build.testVersionId())).isEqualTo(71023L);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT ti.question_version_id)
                    FROM test_items ti
                    JOIN question_versions qv ON qv.question_version_id = ti.question_version_id
                    WHERE ti.test_version_id = ?
                      AND ti.taxonomy_release_id = ?
                      AND qv.review_status = 'APPROVED'
                    """, Integer.class, build.testVersionId(), fixture.taxonomyReleaseId())).isEqualTo(3);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM test_items ti
                    JOIN question_mapping_sets ms ON ms.mapping_set_id = ti.mapping_set_id
                    WHERE ti.test_version_id = ?
                      AND ms.status = 'PUBLISHED'
                      AND ms.taxonomy_release_id = ti.taxonomy_release_id
                    """, Integer.class, build.testVersionId())).isEqualTo(3);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM test_items ti
                    JOIN question_versions qv ON qv.question_version_id = ti.question_version_id
                    WHERE ti.test_version_id = ? AND qv.cognitive_level = 'M1'
                    """, Integer.class, build.testVersionId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM test_items ti
                    JOIN question_versions qv ON qv.question_version_id = ti.question_version_id
                    WHERE ti.test_version_id = ? AND qv.cognitive_level = 'M2'
                    """, Integer.class, build.testVersionId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM test_items ti
                    JOIN question_versions qv ON qv.question_version_id = ti.question_version_id
                    WHERE ti.test_version_id = ? AND qv.cognitive_level = 'M3'
                    """, Integer.class, build.testVersionId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM test_items ti
                    JOIN question_versions qv ON qv.question_version_id = ti.question_version_id
                    WHERE ti.test_version_id = ? AND qv.estimated_difficulty < 0.34
                    """, Integer.class, build.testVersionId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM test_items ti
                    JOIN question_versions qv ON qv.question_version_id = ti.question_version_id
                    WHERE ti.test_version_id = ?
                      AND qv.estimated_difficulty >= 0.34
                      AND qv.estimated_difficulty < 0.67
                    """, Integer.class, build.testVersionId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM test_items ti
                    JOIN question_versions qv ON qv.question_version_id = ti.question_version_id
                    WHERE ti.test_version_id = ? AND qv.estimated_difficulty >= 0.67
                    """, Integer.class, build.testVersionId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM test_items ti
                    JOIN question_versions qv ON qv.question_version_id = ti.question_version_id
                    WHERE ti.test_version_id = ?
                      AND qv.stem = 'Synthetic draft question excluded from builder'
                    """, Integer.class, build.testVersionId())).isZero();
        }

        var underfilledBlueprint = assessmentService.createBlueprint(
                blueprintRequest(fixture, "DIAGNOSTIC", 4, new SelectionRules(
                        List.of(fixture.skillId()), Map.of(), Map.of(), Map.of())), fixture.email());
        var underfilled = assessmentService.buildTest(
                new BuildRequest(underfilledBlueprint.blueprintVersionId(),
                        fixture.taxonomyReleaseId(), 71024L, "Insufficient synthetic bank"), fixture.email());
        assertThat(underfilled.status()).isEqualTo("INSUFFICIENT_QUESTIONS");
        assertThat(underfilled.selectedQuestions()).isEqualTo(3);
        assertThat(underfilled.missingCoverage()).singleElement()
                .satisfies(missing -> {
                    assertThat(missing.requested()).isEqualTo(4);
                    assertThat(missing.eligibleAvailable()).isEqualTo(3);
                });
        assertThatThrownBy(() -> assessmentService.createBlueprint(
                blueprintRequest(fixture, "DIAGNOSTIC", 1, new SelectionRules(
                        List.of(), Map.of("M1", 90), Map.of(), Map.of())), fixture.email()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        long publishedVersionId = testVersionIds.get(0);
        assessmentService.publishTest(publishedVersionId, fixture.email());
        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE test_versions SET duration_minutes = duration_minutes + 1
                WHERE test_version_id = ?""", publishedVersionId),
                "Published Test Versions cannot be changed");
        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE test_items SET item_order = item_order + 1
                WHERE test_version_id = ?""", publishedVersionId),
                "Published Test Versions and their items are immutable");
        Long publishedQuestionVersionId = jdbc.queryForObject("""
                SELECT question_version_id FROM test_items
                WHERE test_version_id = ? ORDER BY item_order LIMIT 1
                """, Long.class, publishedVersionId);
        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE question_versions SET stem = 'Attempted mutation'
                WHERE question_version_id = ?""", publishedQuestionVersionId),
                "Question Version content and provenance are immutable after creation");

        var attempt = assessmentService.startAttempt(publishedVersionId, fixture.userId());
        assertThat(jdbc.queryForObject("""
                SELECT test_version_id FROM attempts WHERE attempt_id = ?
                """, Long.class, attempt.attempt().attemptId())).isEqualTo(publishedVersionId);
        assertThat(mapper.valueToTree(attempt).toString())
                .doesNotContain("correct_answer", "explanation", "answer_key");
    }

    @Test
    void testBuilderSelectsPublishedQuestionGroupsAtomicallyAndInOrder() {
        PublishedBankFixture fixture = createPublishedBankFixture(3, false, 3);
        SelectionRules rules = new SelectionRules(List.of(fixture.skillId()), Map.of(), Map.of(), Map.of());
        var blueprint = assessmentService.createBlueprint(
                blueprintRequest(fixture, "MINI_MOCK", 3, rules), fixture.email());
        var build = assessmentService.buildTest(new BuildRequest(
                blueprint.blueprintVersionId(), fixture.taxonomyReleaseId(), 908172L,
                "Synthetic complete group"), fixture.email());

        assertThat(build.status()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM test_items
                WHERE test_version_id = ? AND question_group_version_id = ?
                """, Integer.class, build.testVersionId(), fixture.groupVersionId())).isEqualTo(3);
        assertThat(jdbc.queryForList("""
                SELECT gi.item_order
                FROM test_items ti
                JOIN question_group_version_items gi
                  ON gi.question_group_version_id = ti.question_group_version_id
                 AND gi.question_version_id = ti.question_version_id
                WHERE ti.test_version_id = ?
                ORDER BY ti.item_order
                """, Integer.class, build.testVersionId())).containsExactly(1, 2, 3);

        var partialBlueprint = assessmentService.createBlueprint(
                blueprintRequest(fixture, "MINI_MOCK", 2, rules), fixture.email());
        var partialBuild = assessmentService.buildTest(new BuildRequest(
                partialBlueprint.blueprintVersionId(), fixture.taxonomyReleaseId(), 908173L,
                "Synthetic partial group"), fixture.email());
        assertThat(partialBuild.status()).isEqualTo("INSUFFICIENT_QUESTIONS");
        assertThat(partialBuild.selectedQuestions()).isZero();
        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE question_group_versions
                SET title = 'Attempted published group mutation'
                WHERE question_group_version_id = ?
                """, fixture.groupVersionId()),
                "Published Question Group Versions are immutable");
    }

    @Test
    void hsaOptionalBranchRequiresOneCompatibleQuestionSectionBranch() {
        HsaOptionalBranchFixture fixture = createHsaOptionalBranchFixture();
        SelectionRules scienceSkills = new SelectionRules(
                List.of(fixture.scienceSkillId()), Map.of(), Map.of(), Map.of());
        BlueprintRequest scienceBlueprint = new BlueprintRequest(
                "Synthetic HSA Science", "Isolated HSA optional-branch fixture",
                fixture.examVersionId(), "DIAGNOSTIC", 30,
                List.of(new SectionRule(fixture.scienceSectionId(), 1, null)), scienceSkills,
                List.of(new OptionalBranchSelection(fixture.optionalGroupId(), "SCIENCE")));

        var blueprint = assessmentService.createBlueprint(scienceBlueprint, fixture.email());
        assertThat(blueprint.optionalBranchSelections()).containsExactly(
                new OptionalBranchSelection(fixture.optionalGroupId(), "SCIENCE"));

        var build = assessmentService.buildTest(
                new BuildRequest(blueprint.blueprintVersionId(), fixture.hsaReleaseId(),
                        99171L, "Synthetic HSA Science Test"), fixture.email());
        assertThat(build.status()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM test_items ti
                JOIN question_versions qv ON qv.question_version_id = ti.question_version_id
                WHERE ti.test_version_id = ? AND qv.section_id = ?
                """, Integer.class, build.testVersionId(), fixture.scienceSectionId())).isEqualTo(1);

        BlueprintRequest englishBlueprint = new BlueprintRequest(
                "Synthetic HSA English", "Isolated HSA optional-branch fixture",
                fixture.examVersionId(), "DIAGNOSTIC", 30,
                List.of(new SectionRule(fixture.englishSectionId(), 1, null)),
                new SelectionRules(List.of(fixture.englishSkillId()), Map.of(), Map.of(), Map.of()),
                List.of(new OptionalBranchSelection(fixture.optionalGroupId(), "ENGLISH")));
        var englishBlueprintVersion = assessmentService.createBlueprint(englishBlueprint, fixture.email());
        var englishBuild = assessmentService.buildTest(
                new BuildRequest(englishBlueprintVersion.blueprintVersionId(), fixture.hsaReleaseId(),
                        99173L, "Synthetic HSA English Test"), fixture.email());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM test_items ti
                JOIN question_versions qv ON qv.question_version_id = ti.question_version_id
                WHERE ti.test_version_id = ? AND qv.section_id = ?
                """, Integer.class, englishBuild.testVersionId(), fixture.englishSectionId())).isEqualTo(1);

        BlueprintRequest mixedBranches = new BlueprintRequest(
                "Synthetic HSA mixed", "Invalid fixture",
                fixture.examVersionId(), "DIAGNOSTIC", 30,
                List.of(
                        new SectionRule(fixture.scienceSectionId(), 1, null),
                        new SectionRule(fixture.englishSectionId(), 1, null)),
                scienceSkills,
                List.of(new OptionalBranchSelection(fixture.optionalGroupId(), "SCIENCE")));
        assertBadRequest(() -> assessmentService.createBlueprint(mixedBranches, fixture.email()),
                "cannot be mixed");

        BlueprintRequest missingBranch = new BlueprintRequest(
                "Synthetic HSA missing", "Invalid fixture",
                fixture.examVersionId(), "DIAGNOSTIC", 30,
                List.of(new SectionRule(fixture.scienceSectionId(), 1, null)), scienceSkills, List.of());
        assertBadRequest(() -> assessmentService.createBlueprint(missingBranch, fixture.email()),
                "requires an explicit branch");

        BlueprintRequest incompatibleSkill = new BlueprintRequest(
                "Synthetic HSA incompatible skill", "Invalid fixture",
                fixture.examVersionId(), "DIAGNOSTIC", 30,
                List.of(new SectionRule(fixture.scienceSectionId(), 1, null)),
                new SelectionRules(List.of(fixture.englishSkillId()), Map.of(), Map.of(), Map.of()),
                List.of(new OptionalBranchSelection(fixture.optionalGroupId(), "SCIENCE")));
        assertBadRequest(() -> assessmentService.createBlueprint(incompatibleSkill, fixture.email()),
                "must be compatible");

        long tsaReleaseId = createPublishedTaxonomyRelease(
                UUID.randomUUID().toString().replace("-", ""), "TSA", List.of());
        assertBadRequest(() -> assessmentService.buildTest(
                new BuildRequest(blueprint.blueprintVersionId(), tsaReleaseId,
                        99172L, "Invalid HSA release"), fixture.email()),
                "not compatible");

        assertDatabaseRejected(() -> jdbc.update("""
                INSERT INTO assessment_optional_section_branch_sections
                    (optional_section_branch_id, section_id)
                VALUES (?, ?)
                """, fixture.scienceBranchId(), fixture.otherExamSectionId()),
                "only contain sections from its Exam Version");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void selectedHsaOptionalBranchConfigurationCannotDrift() {
        HsaOptionalBranchFixture fixture = createHsaOptionalBranchFixture();
        BlueprintRequest blueprintRequest = new BlueprintRequest(
                "Synthetic HSA immutable branch", "Isolated HSA optional-branch fixture",
                fixture.examVersionId(), "DIAGNOSTIC", 30,
                List.of(new SectionRule(fixture.scienceSectionId(), 1, null)),
                new SelectionRules(List.of(fixture.scienceSkillId()), Map.of(), Map.of(), Map.of()),
                List.of(new OptionalBranchSelection(fixture.optionalGroupId(), "SCIENCE")));
        assessmentService.createBlueprint(blueprintRequest, fixture.email());

        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE assessment_optional_section_branch_sections
                SET section_id = ?
                WHERE optional_section_branch_id = ? AND section_id = ?
                """, fixture.englishSectionId(), fixture.scienceBranchId(), fixture.scienceSectionId()),
                "cannot change after a Blueprint Version selects it");
        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE assessment_optional_section_branches
                SET branch_code = 'BIOLOGY'
                WHERE optional_section_branch_id = ?
                """, fixture.scienceBranchId()),
                "cannot change after a Blueprint Version selects it");
        assertDatabaseRejected(() -> jdbc.update("""
                UPDATE assessment_optional_section_groups
                SET status = 'INACTIVE'
                WHERE optional_section_group_id = ?
                """, fixture.optionalGroupId()),
                "cannot change after a Blueprint Version selects it");
    }

    @Test
    @EnabledIfSystemProperty(named = "phase05.real-e2e.seed", matches = "true")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void seedRealHttpAcceptanceFixtures() throws Exception {
        String password = System.getenv("PHASE05_E2E_PASSWORD");
        if (password == null || password.length() < 12) {
            throw new IllegalStateException("PHASE05_E2E_PASSWORD must be provided for the isolated E2E fixture.");
        }
        PublishedBankFixture bank = createPublishedBankFixture(3, false, 3);
        PublishedBankFixture tableBank = createPublishedBankFixture(2, false, 2, "TABLE");
        PublishedBankFixture chartBank = createPublishedBankFixture(2, false, 2, "CHART");
        String passwordHash = passwordEncoder.encode(password);
        jdbc.update("UPDATE users SET password_hash = ? WHERE user_id = ?",
                passwordHash, bank.userId());
        long teacherRoleId = jdbc.queryForObject(
                "SELECT role_id FROM roles WHERE role_code = 'TEACHER'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)",
                bank.userId(), teacherRoleId);

        String adminEmail = "phase05-admin-real-e2e-20261015@example.invalid";
        Long adminId = jdbc.queryForObject("""
                INSERT INTO users (username, email, password_hash, full_name)
                VALUES ('phase05-admin-real-e2e-20261015', ?, ?, 'Phase 05 E2E Admin')
                RETURNING user_id
                """, Long.class, adminEmail, passwordHash);
        long adminRoleId = jdbc.queryForObject(
                "SELECT role_id FROM roles WHERE role_code = 'ADMIN'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)",
                adminId, adminRoleId);

        String unrelatedTeacherEmail = "phase06-unrelated-teacher-"
                + UUID.randomUUID().toString().replace("-", "") + "@example.invalid";
        Long unrelatedTeacherId = jdbc.queryForObject("""
                INSERT INTO users (username, email, password_hash, full_name)
                VALUES (?, ?, ?, 'Unrelated Phase 06 Analytics Teacher')
                RETURNING user_id
                """, Long.class, unrelatedTeacherEmail.substring(0, unrelatedTeacherEmail.indexOf('@')),
                unrelatedTeacherEmail, passwordHash);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)",
                unrelatedTeacherId, teacherRoleId);

        HsaOptionalBranchFixture hsa = createHsaOptionalBranchFixture();
        System.out.println("PHASE05_REAL_E2E_FIXTURE=" + mapper.writeValueAsString(Map.ofEntries(
                Map.entry("teacherEmail", bank.email()),
                Map.entry("unrelatedTeacherEmail", unrelatedTeacherEmail),
                Map.entry("adminEmail", adminEmail),
                Map.entry("examVersionId", bank.examVersionId()),
                Map.entry("sectionId", bank.sectionId()),
                Map.entry("taxonomyReleaseId", bank.taxonomyReleaseId()),
                Map.entry("skillId", bank.skillId()),
                Map.entry("questionGroupVersionId", bank.groupVersionId()),
                Map.entry("tableExamVersionId", tableBank.examVersionId()),
                Map.entry("tableSectionId", tableBank.sectionId()),
                Map.entry("tableTaxonomyReleaseId", tableBank.taxonomyReleaseId()),
                Map.entry("tableSkillId", tableBank.skillId()),
                Map.entry("tableQuestionGroupVersionId", tableBank.groupVersionId()),
                Map.entry("chartExamVersionId", chartBank.examVersionId()),
                Map.entry("chartSectionId", chartBank.sectionId()),
                Map.entry("chartTaxonomyReleaseId", chartBank.taxonomyReleaseId()),
                Map.entry("chartSkillId", chartBank.skillId()),
                Map.entry("chartQuestionGroupVersionId", chartBank.groupVersionId()),
                Map.entry("hsaExamVersionId", hsa.examVersionId()),
                Map.entry("hsaScienceSectionId", hsa.scienceSectionId()),
                Map.entry("hsaEnglishSectionId", hsa.englishSectionId()),
                Map.entry("hsaOptionalGroupId", hsa.optionalGroupId()),
                Map.entry("hsaTaxonomyReleaseId", hsa.hsaReleaseId()),
                Map.entry("hsaScienceSkillId", hsa.scienceSkillId()),
                Map.entry("hsaEnglishSkillId", hsa.englishSkillId()))));
    }

    private int saveAtSameRevision(
            CountDownLatch ready,
            CountDownLatch start,
            long attemptId,
            Fixture fixture,
            String answer) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            assessmentService.saveAnswer(
                    attemptId,
                    fixture.testItemId(),
                    fixture.userId(),
                    new SaveAnswerRequest(List.of(answer), null, 0, UUID.randomUUID()));
            return 200;
        } catch (ResponseStatusException exception) {
            return exception.getStatusCode().value();
        }
    }

    private ScoringDtos.ScoringResult scoreAtBarrier(
            CountDownLatch ready,
            CountDownLatch start,
            long attemptId,
            long userId,
            UUID requestId) throws InterruptedException {
        ready.countDown();
        start.await();
        return scoringService.scoreAttempt(attemptId, userId, requestId);
    }

    private long createActiveAttempt(Fixture fixture, boolean expired) {
        return createActiveAttempt(fixture.testVersionId(), fixture.userId(), expired);
    }

    private long scoreFixtureAttempt(Fixture fixture, long userId, boolean answerCorrectly) {
        long attemptId = createActiveAttempt(fixture.testVersionId(), userId, false);
        assessmentService.saveAnswer(
                attemptId, fixture.testItemId(), userId,
                new SaveAnswerRequest(List.of(answerCorrectly ? "A" : "B"), null, 0, UUID.randomUUID()));
        assessmentService.submitAttempt(attemptId, userId);
        scoringService.scoreAttempt(attemptId, userId, UUID.randomUUID());
        return attemptId;
    }

    private long tsaVersionOneExamId(Fixture fixture) {
        return jdbc.queryForObject("""
                SELECT ev.exam_id
                FROM test_versions tv
                JOIN tests t ON t.test_id = tv.test_id
                JOIN exam_versions ev ON ev.exam_version_id = t.exam_version_id
                WHERE tv.test_version_id = ?
                """, Long.class, fixture.testVersionId());
    }

    private long createActiveAttempt(long testVersionId, long userId, boolean expired) {
        Integer attemptNumber = jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt_number), 0) + 1 FROM attempts
                WHERE user_id = ? AND test_version_id = ?
                """, Integer.class, userId, testVersionId);
        return jdbc.queryForObject("""
                INSERT INTO attempts
                    (test_version_id, user_id, attempt_number, status, started_at, ends_at)
                VALUES (?, ?, ?, 'IN_PROGRESS',
                        CASE WHEN ? THEN CURRENT_TIMESTAMP - INTERVAL '2 minutes'
                             ELSE CURRENT_TIMESTAMP END,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP - INTERVAL '1 second'
                             ELSE CURRENT_TIMESTAMP + INTERVAL '10 minutes' END)
                RETURNING attempt_id
                """, Long.class, testVersionId, userId, attemptNumber, expired, expired);
    }

    private Fixture createDraftTestFixture() {
        return createDraftTestFixture("TSA");
    }

    private Fixture createDraftTestFixture(String examCode) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String email = "phase05-" + suffix + "@example.invalid";
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (username, email, password_hash, full_name)
                VALUES (?, ?, 'test-only', 'Assessment Integration')
                RETURNING user_id
                """, Long.class, "phase05-" + suffix, email);
        jdbc.update("""
                INSERT INTO exams (code, name) VALUES (?, ?)
                ON CONFLICT (code) DO NOTHING
                """, examCode, "Test-only " + examCode);
        Long examId = jdbc.queryForObject("SELECT exam_id FROM exams WHERE code = ?", Long.class, examCode);
        Long examVersionId = jdbc.queryForObject("""
                INSERT INTO exam_versions (exam_id, version_code, name)
                VALUES (?, ?, 'Assessment Integration Version')
                RETURNING exam_version_id
                """, Long.class, examId, "IT-" + suffix);
        Long sectionId = jdbc.queryForObject("""
                INSERT INTO sections (exam_version_id, section_code, section_name, order_no)
                VALUES (?, ?, 'Assessment Integration Section', 1)
                RETURNING section_id
                """, Long.class, examVersionId, "IT-" + suffix);

        ObjectNode question = mapper.createObjectNode()
                .put("question_id", "phase05-" + suffix)
                .put("stem", "Synthetic integration question")
                .put("question_type", "SINGLE_CHOICE");
        question.putObject("metadata").put("exam", examCode);
        question.putArray("options")
                .addObject().put("key", "A").put("text", "Synthetic option A");
        question.withArray("options").addObject().put("key", "B").put("text", "Synthetic option B");
        question.putArray("correct_answer").add("A");
        var imported = questionService.stageQuestions(
                new ImportBatchRequest("phase05-" + suffix, "test-only.json", List.of(question)),
                email);
        var materialized = questionService.materializeQuestion(
                imported.items().get(0).importItemId(),
                new MaterializeRequest(sectionId, "HUMAN"));

        Long testId = jdbc.queryForObject("""
                INSERT INTO tests (test_code, title, exam_version_id, test_type, created_by)
                VALUES (?, 'Isolated Test Version', ?, 'DIAGNOSTIC', ?)
                RETURNING test_id
                """, Long.class, "TEST-" + suffix, examVersionId, userId);
        Long testVersionId = jdbc.queryForObject("""
                INSERT INTO test_versions
                    (test_id, version_number, duration_minutes, total_questions, total_score)
                VALUES (?, 1, 10, 1, 1)
                RETURNING test_version_id
                """, Long.class, testId);
        Long testSectionId = jdbc.queryForObject("""
                INSERT INTO test_sections
                    (test_version_id, section_id, section_order, question_count)
                VALUES (?, ?, 1, 1)
                RETURNING test_section_id
                """, Long.class, testVersionId, sectionId);
        Long testItemId = jdbc.queryForObject("""
                INSERT INTO test_items
                    (test_version_id, test_section_id, question_version_id, item_order)
                VALUES (?, ?, ?, 1)
                RETURNING test_item_id
                """, Long.class, testVersionId, testSectionId, materialized.questionVersionId());
        return new Fixture(userId, testVersionId, testItemId, materialized.questionVersionId());
    }

    private PublishedBankFixture createPublishedBankFixture(int approvedQuestionCount, boolean withDraft) {
        return createPublishedBankFixture(approvedQuestionCount, withDraft, 0);
    }

    private PublishedBankFixture createPublishedBankFixture(
            int approvedQuestionCount, boolean withDraft, int groupedQuestionCount) {
        return createPublishedBankFixture(approvedQuestionCount, withDraft, groupedQuestionCount, "PASSAGE");
    }

    private PublishedBankFixture createPublishedBankFixture(
            int approvedQuestionCount, boolean withDraft, int groupedQuestionCount, String groupStimulusType) {
        return createPublishedBankFixture(
                approvedQuestionCount, withDraft, groupedQuestionCount, groupStimulusType, false);
    }

    private PublishedBankFixture createPublishedBankFixture(
            int approvedQuestionCount,
            boolean withDraft,
            int groupedQuestionCount,
            String groupStimulusType,
            boolean includeSecondarySkill) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String email = "phase05-bank-" + suffix + "@example.invalid";
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (username, email, password_hash, full_name)
                VALUES (?, ?, 'test-only', 'Synthetic Assessment Reviewer')
                RETURNING user_id
                """, Long.class, "phase05-bank-" + suffix, email);
        jdbc.update("""
                INSERT INTO exams (code, name) VALUES ('TSA', 'Synthetic TSA')
                ON CONFLICT (code) DO NOTHING
                """);
        Long examId = jdbc.queryForObject("SELECT exam_id FROM exams WHERE code = 'TSA'", Long.class);
        Long examVersionId = jdbc.queryForObject("""
                INSERT INTO exam_versions (exam_id, version_code, name)
                VALUES (?, ?, 'Synthetic TSA Assessment Version')
                RETURNING exam_version_id
                """, Long.class, examId, "BANK-" + suffix);
        Long sectionId = jdbc.queryForObject("""
                INSERT INTO sections (exam_version_id, section_code, section_name, order_no)
                VALUES (?, ?, 'Synthetic Mathematical Thinking', 1)
                RETURNING section_id
                """, Long.class, examVersionId, "BANK-" + suffix);
        Long competencyId = jdbc.queryForObject("""
                INSERT INTO competencies (competency_code, competency_name)
                VALUES (?, 'Synthetic Mathematical Thinking')
                RETURNING competency_id
                """, Long.class, "COMP-" + suffix);
        jdbc.update("""
                INSERT INTO section_competencies (section_id, competency_id, weight, is_primary)
                VALUES (?, ?, 1, TRUE)
                """, sectionId, competencyId);
        Long domainId = jdbc.queryForObject("""
                INSERT INTO domains (competency_id, domain_code, domain_name)
                VALUES (?, ?, 'Synthetic quantitative reasoning')
                RETURNING domain_id
                """, Long.class, competencyId, "DOMAIN-" + suffix);
        Long topicId = jdbc.queryForObject("""
                INSERT INTO topics (domain_id, topic_code, topic_name)
                VALUES (?, ?, 'Synthetic problem solving')
                RETURNING topic_id
                """, Long.class, domainId, "TOPIC-" + suffix);
        Long skillId = jdbc.queryForObject("""
                INSERT INTO skills (topic_id, skill_code, skill_name, description)
                VALUES (?, ?, 'Synthetic quantitative skill', 'Test-only measurable skill')
                RETURNING skill_id
                """, Long.class, topicId, "SKILL-" + suffix);
        Long secondarySkillId = includeSecondarySkill
                ? jdbc.queryForObject("""
                        INSERT INTO skills (topic_id, skill_code, skill_name, description)
                        VALUES (?, ?, 'Synthetic secondary skill', 'Test-only measurable secondary skill')
                        RETURNING skill_id
                        """, Long.class, topicId, "SKILL-SECONDARY-" + suffix)
                : null;

        Long catalogId = jdbc.queryForObject("""
                INSERT INTO taxonomy_catalogs (taxonomy_id, catalog_name, catalog_type, status)
                VALUES (?, 'Synthetic TSA Taxonomy', 'TSA', 'ACTIVE')
                RETURNING taxonomy_catalog_id
                """, Long.class, "TEST-" + suffix);
        Long releaseId = jdbc.queryForObject("""
                INSERT INTO taxonomy_releases
                    (taxonomy_catalog_id, release_code, taxonomy_version, status, content_hash,
                     snapshot_locator, manifest_json, source_artifact_hashes)
                VALUES (?, ?, 'test-1', 'DRAFT', ?, ?, '{}'::jsonb, '[]'::jsonb)
                RETURNING taxonomy_release_id
                """, Long.class, catalogId, "TEST-RELEASE-" + suffix,
                "a".repeat(64), "test-fixture://phase05/" + suffix + "/snapshot.json");
        jdbc.update("""
                INSERT INTO taxonomy_release_members (taxonomy_release_id, skill_id, membership_status)
                VALUES (?, ?, 'ACTIVE')
                """, releaseId, skillId);
        if (secondarySkillId != null) {
            jdbc.update("""
                    INSERT INTO taxonomy_release_members (taxonomy_release_id, skill_id, membership_status)
                    VALUES (?, ?, 'ACTIVE')
                    """, releaseId, secondarySkillId);
        }
        jdbc.update("""
                UPDATE taxonomy_releases SET status = 'RELEASE_CANDIDATE'
                WHERE taxonomy_release_id = ?
                """, releaseId);
        jdbc.update("""
                INSERT INTO taxonomy_release_events
                    (taxonomy_release_id, event_type, actor, reason_code, reason_text, evidence_uri,
                     metadata_json)
                VALUES (?, 'APPROVED', 'SYNTHETIC TEST FIXTURE', 'TEST_ONLY',
                        'Synthetic approval evidence limited to isolated acceptance database',
                        ?, '{"synthetic":true}'::jsonb)
                """, releaseId, "test-fixture://phase05/" + suffix + "/approval.json");
        jdbc.update("""
                UPDATE taxonomy_releases
                SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                    approval_references = '["test-fixture://phase05/synthetic-approval"]'::jsonb,
                    approved_by = 'SYNTHETIC TEST FIXTURE',
                    approval_decision_ref = ?
                WHERE taxonomy_release_id = ?
                """, "TEST-APPROVAL-" + suffix, releaseId);

        List<String> levels = List.of("M1", "M2", "M3");
        List<String> difficulties = List.of("0.1", "0.5", "0.9");
        Long[] groupVersionId = {null};
        for (int index = 0; index < approvedQuestionCount; index++) {
            createQuestionVersion(email, suffix, sectionId, releaseId, skillId, "TSA",
                    levels.get(index % levels.size()), difficulties.get(index % difficulties.size()), index,
                    index < groupedQuestionCount ? groupVersionId : null, groupStimulusType, secondarySkillId);
        }
        if (groupVersionId[0] != null) {
            questionService.reviewQuestionGroup(groupVersionId[0],
                    new QuestionGroupReviewRequest("PENDING_REVIEW", "Synthetic fixture group review",
                            List.of("test-fixture://phase05/group-review")), email);
            questionService.reviewQuestionGroup(groupVersionId[0],
                    new QuestionGroupReviewRequest("IN_REVIEW", "Synthetic fixture group review",
                            List.of("test-fixture://phase05/group-review")), email);
            questionService.reviewQuestionGroup(groupVersionId[0],
                    new QuestionGroupReviewRequest("APPROVED", "Test-only synthetic group approval",
                            List.of("test-fixture://phase05/synthetic-group-approval")), email);
            questionService.publishQuestionGroup(groupVersionId[0], email);
        }
        if (withDraft) {
            createDraftQuestionVersion(email, suffix, sectionId, approvedQuestionCount);
        }
        return new PublishedBankFixture(
                userId, email, examVersionId, sectionId, releaseId, skillId,
                secondarySkillId, groupVersionId[0]);
    }

    private HsaOptionalBranchFixture createHsaOptionalBranchFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String email = "phase05-hsa-" + suffix + "@example.invalid";
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (username, email, password_hash, full_name)
                VALUES (?, ?, 'test-only', 'Synthetic HSA Reviewer')
                RETURNING user_id
                """, Long.class, "phase05-hsa-" + suffix, email);
        jdbc.update("""
                INSERT INTO exams (code, name) VALUES ('HSA', 'Synthetic HSA')
                ON CONFLICT (code) DO NOTHING
                """);
        Long hsaExamId = jdbc.queryForObject("SELECT exam_id FROM exams WHERE code = 'HSA'", Long.class);
        Long examVersionId = jdbc.queryForObject("""
                INSERT INTO exam_versions (exam_id, version_code, name)
                VALUES (?, ?, 'Synthetic HSA Optional Version')
                RETURNING exam_version_id
                """, Long.class, hsaExamId, "HSA-" + suffix);
        Long scienceSectionId = jdbc.queryForObject("""
                INSERT INTO sections (exam_version_id, section_code, section_name, order_no)
                VALUES (?, ?, 'Synthetic HSA Science', 1)
                RETURNING section_id
                """, Long.class, examVersionId, "SCI-" + suffix);
        Long englishSectionId = jdbc.queryForObject("""
                INSERT INTO sections (exam_version_id, section_code, section_name, order_no)
                VALUES (?, ?, 'Synthetic HSA English', 2)
                RETURNING section_id
                """, Long.class, examVersionId, "ENG-" + suffix);
        Long scienceSkillId = createSkillForSection(suffix + "-science", scienceSectionId, "Science");
        Long englishSkillId = createSkillForSection(suffix + "-english", englishSectionId, "English");
        long hsaReleaseId = createPublishedTaxonomyRelease(
                suffix + "-hsa", "HSA", List.of(scienceSkillId, englishSkillId));
        createQuestionVersion(email, suffix, scienceSectionId, hsaReleaseId, scienceSkillId,
                "HSA", "M1", "0.5", 1);
        createQuestionVersion(email, suffix, englishSectionId, hsaReleaseId, englishSkillId,
                "HSA", "M1", "0.5", 2);

        long optionalGroupId = jdbc.queryForObject("""
                INSERT INTO assessment_optional_section_groups
                    (exam_version_id, group_code, group_name)
                VALUES (?, 'HSA_OPTIONAL', 'Synthetic HSA optional container')
                RETURNING optional_section_group_id
                """, Long.class, examVersionId);
        long scienceBranchId = jdbc.queryForObject("""
                INSERT INTO assessment_optional_section_branches
                    (optional_section_group_id, branch_code, branch_name)
                VALUES (?, 'SCIENCE', 'Science')
                RETURNING optional_section_branch_id
                """, Long.class, optionalGroupId);
        long englishBranchId = jdbc.queryForObject("""
                INSERT INTO assessment_optional_section_branches
                    (optional_section_group_id, branch_code, branch_name)
                VALUES (?, 'ENGLISH', 'English')
                RETURNING optional_section_branch_id
                """, Long.class, optionalGroupId);
        jdbc.update("""
                INSERT INTO assessment_optional_section_branch_sections
                    (optional_section_branch_id, section_id)
                VALUES (?, ?), (?, ?)
                """, scienceBranchId, scienceSectionId, englishBranchId, englishSectionId);

        jdbc.update("""
                INSERT INTO exams (code, name) VALUES ('TSA', 'Synthetic TSA')
                ON CONFLICT (code) DO NOTHING
                """);
        Long tsaExamId = jdbc.queryForObject("SELECT exam_id FROM exams WHERE code = 'TSA'", Long.class);
        Long otherExamVersionId = jdbc.queryForObject("""
                INSERT INTO exam_versions (exam_id, version_code, name)
                VALUES (?, ?, 'Other Exam Version')
                RETURNING exam_version_id
                """, Long.class, tsaExamId, "OTHER-" + suffix);
        Long otherExamSectionId = jdbc.queryForObject("""
                INSERT INTO sections (exam_version_id, section_code, section_name, order_no)
                VALUES (?, ?, 'Other section', 1)
                RETURNING section_id
                """, Long.class, otherExamVersionId, "OTHER-" + suffix);
        return new HsaOptionalBranchFixture(
                userId, email, examVersionId, scienceSectionId, englishSectionId,
                otherExamSectionId, optionalGroupId, scienceBranchId,
                hsaReleaseId, scienceSkillId, englishSkillId);
    }

    private long createSkillForSection(String suffix, long sectionId, String label) {
        Long competencyId = jdbc.queryForObject("""
                INSERT INTO competencies (competency_code, competency_name)
                VALUES (?, ?)
                RETURNING competency_id
                """, Long.class, "HSA-COMP-" + suffix, "Synthetic HSA " + label);
        jdbc.update("""
                INSERT INTO section_competencies (section_id, competency_id, weight, is_primary)
                VALUES (?, ?, 1, TRUE)
                """, sectionId, competencyId);
        Long domainId = jdbc.queryForObject("""
                INSERT INTO domains (competency_id, domain_code, domain_name)
                VALUES (?, ?, ?)
                RETURNING domain_id
                """, Long.class, competencyId, "HSA-DOMAIN-" + suffix, "Synthetic " + label + " Domain");
        Long topicId = jdbc.queryForObject("""
                INSERT INTO topics (domain_id, topic_code, topic_name)
                VALUES (?, ?, ?)
                RETURNING topic_id
                """, Long.class, domainId, "HSA-TOPIC-" + suffix, "Synthetic " + label + " Topic");
        return jdbc.queryForObject("""
                INSERT INTO skills (topic_id, skill_code, skill_name, description)
                VALUES (?, ?, ?, 'Synthetic test-only skill')
                RETURNING skill_id
                """, Long.class, topicId, "HSA-SKILL-" + suffix, "Synthetic " + label + " Skill");
    }

    private long createPublishedTaxonomyRelease(
            String suffix, String catalogType, List<Long> skillIds) {
        Long catalogId = jdbc.queryForObject("""
                INSERT INTO taxonomy_catalogs (taxonomy_id, catalog_name, catalog_type, status)
                VALUES (?, ?, ?, 'ACTIVE')
                RETURNING taxonomy_catalog_id
                """, Long.class, "TEST-" + suffix,
                "Synthetic " + catalogType + " Taxonomy", catalogType);
        Long releaseId = jdbc.queryForObject("""
                INSERT INTO taxonomy_releases
                    (taxonomy_catalog_id, release_code, taxonomy_version, status, content_hash,
                     snapshot_locator, manifest_json, source_artifact_hashes)
                VALUES (?, ?, 'test-1', 'DRAFT', ?, ?, '{}'::jsonb, '[]'::jsonb)
                RETURNING taxonomy_release_id
                """, Long.class, catalogId, "TEST-RELEASE-" + suffix,
                "a".repeat(64), "test-fixture://phase05/" + suffix + "/snapshot.json");
        for (Long skillId : skillIds) {
            jdbc.update("""
                    INSERT INTO taxonomy_release_members
                        (taxonomy_release_id, skill_id, membership_status)
                    VALUES (?, ?, 'ACTIVE')
                    """, releaseId, skillId);
        }
        jdbc.update("""
                UPDATE taxonomy_releases SET status = 'RELEASE_CANDIDATE'
                WHERE taxonomy_release_id = ?
                """, releaseId);
        jdbc.update("""
                INSERT INTO taxonomy_release_events
                    (taxonomy_release_id, event_type, actor, reason_code, reason_text, evidence_uri,
                     metadata_json)
                VALUES (?, 'APPROVED', 'SYNTHETIC TEST FIXTURE', 'TEST_ONLY',
                        'Synthetic approval evidence limited to isolated acceptance database',
                        ?, '{"synthetic":true}'::jsonb)
                """, releaseId, "test-fixture://phase05/" + suffix + "/approval.json");
        jdbc.update("""
                UPDATE taxonomy_releases
                SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                    approval_references = '["test-fixture://phase05/synthetic-approval"]'::jsonb,
                    approved_by = 'SYNTHETIC TEST FIXTURE',
                    approval_decision_ref = ?
                WHERE taxonomy_release_id = ?
                """, "TEST-APPROVAL-" + suffix, releaseId);
        return releaseId;
    }

    private void createQuestionVersion(
            String email, String suffix, long sectionId, long releaseId,
            long skillId, String examCode, String cognitiveLevel, String difficulty, int index) {
        createQuestionVersion(email, suffix, sectionId, releaseId, skillId, examCode,
                cognitiveLevel, difficulty, index, null, "PASSAGE");
    }

    private void createQuestionVersion(
            String email, String suffix, long sectionId, long releaseId,
            long skillId, String examCode, String cognitiveLevel, String difficulty, int index,
            Long[] groupVersionId, String groupStimulusType) {
        createQuestionVersion(email, suffix, sectionId, releaseId, skillId, examCode,
                cognitiveLevel, difficulty, index, groupVersionId, groupStimulusType, null);
    }

    private void createQuestionVersion(
            String email, String suffix, long sectionId, long releaseId,
            long skillId, String examCode, String cognitiveLevel, String difficulty, int index,
            Long[] groupVersionId, String groupStimulusType, Long secondarySkillId) {
        ObjectNode question = mapper.createObjectNode()
                .put("question_id", "approved-" + suffix + "-" + index)
                .put("stem", "Synthetic approved question " + index)
                .put("question_type", "SINGLE_CHOICE");
        question.putObject("metadata")
                .put("exam", examCode)
                .put("cognitive_level", cognitiveLevel)
                .put("estimated_difficulty", difficulty);
        question.putArray("options")
                .addObject().put("key", "A").put("text", "Synthetic option A");
        question.withArray("options").addObject().put("key", "B").put("text", "Synthetic option B");
        question.putArray("correct_answer").add("A");
        if ("TSA".equals(examCode) && index == 0) {
            question.putObject("passage")
                    .put("title", "Synthetic scientific passage")
                    .put("content", "Test-only passage content for rendering acceptance.")
                    .put("passage_type", "SCIENTIFIC")
                    .put("source_reference", "test-fixture://phase05/scientific-passage");
            question.putArray("visual_assets")
                    .addObject()
                    .put("kind", "IMAGE")
                    .put("path", "fixtures/phase05-image.svg")
                    .put("alt_text", "Synthetic diagram")
                    .put("caption", "Synthetic image")
                    .put("asset_id", "phase05-image");
            question.withArray("visual_assets")
                    .addObject()
                    .put("kind", "TABLE")
                    .put("path", "fixtures/phase05-table.svg")
                    .put("alt_text", "Synthetic data table")
                    .put("caption", "Synthetic table")
                    .put("asset_id", "phase05-table");
            question.withArray("visual_assets")
                    .addObject()
                    .put("kind", "CHART")
                    .put("path", "fixtures/phase05-chart.svg")
                    .put("alt_text", "Synthetic chart")
                    .put("caption", "Synthetic chart")
                    .put("asset_id", "phase05-chart");
        } else if ("HSA".equals(examCode) && index == 2) {
            question.putObject("passage")
                    .put("title", "Synthetic reading passage")
                    .put("content", "Test-only reading passage content for HSA English.")
                    .put("passage_type", "READING")
                    .put("source_reference", "test-fixture://phase05/reading-passage");
        }
        var imported = questionService.stageQuestions(
                new ImportBatchRequest("phase05-bank-" + suffix + "-" + index,
                        "test-fixture://phase05/synthetic-question-" + index, List.of(question)),
                email);
        var materialized = questionService.materializeQuestion(
                imported.items().get(0).importItemId(), new MaterializeRequest(sectionId, "HUMAN"));
        if (groupVersionId != null) {
            if (groupVersionId[0] == null) {
                ObjectNode stimulus = switch (groupStimulusType) {
                    case "TABLE" -> {
                        ObjectNode table = mapper.createObjectNode();
                        table.putArray("columns")
                                .addObject().put("key", "sample").put("label", "Sample");
                        table.withArray("columns").addObject().put("key", "value").put("label", "Value");
                        table.putArray("rows").add(mapper.createArrayNode().add("B").add(18));
                        yield table;
                    }
                    case "CHART" -> {
                        ObjectNode chart = mapper.createObjectNode();
                        chart.putArray("labels").add("Sample B");
                        chart.putArray("series").addObject()
                                .put("name", "Value").putArray("values").add(18);
                        yield chart;
                    }
                    default -> mapper.createObjectNode()
                            .put("title", "Synthetic shared passage")
                            .put("content", "Synthetic shared stimulus for builder and player integration.");
                };
                groupVersionId[0] = questionService.createQuestionGroup(new QuestionGroupRequest(
                        "Synthetic grouped question set", groupStimulusType, stimulus,
                        mapper.createObjectNode().put("source", "test-fixture://phase05"),
                        "test-fixture://phase05/group-rights",
                        List.of(materialized.questionVersionId())), email).questionGroupVersionId();
            } else {
                questionService.addQuestionGroupItem(groupVersionId[0],
                        new QuestionGroupItemRequest(materialized.questionVersionId(), index + 1));
            }
        }
        jdbc.update("""
                UPDATE visual_asset
                SET rights_status = 'VERIFIED', rights_evidence_uri = ?,
                    rights_verified_by = (SELECT user_id FROM users WHERE email = ?),
                    rights_verified_at = CURRENT_TIMESTAMP, needs_review = FALSE
                WHERE question_version_id = ?
                """, "test-fixture://phase05/synthetic-rights/" + suffix + "/" + index,
                email, materialized.questionVersionId());
        List<MappingItemRequest> mappingItems = secondarySkillId == null
                ? List.of(new MappingItemRequest(skillId, "PRIMARY", true, BigDecimal.ONE))
                : List.of(
                        new MappingItemRequest(skillId, "PRIMARY", true,
                                index >= 2 ? BigDecimal.ZERO
                                        : index % 2 == 0 ? new BigDecimal("0.8") : new BigDecimal("0.2")),
                        new MappingItemRequest(secondarySkillId, "SECONDARY", false,
                                index >= 2 ? BigDecimal.ZERO
                                        : index % 2 == 0 ? new BigDecimal("0.2") : new BigDecimal("0.8")));
        long mappingSetId = questionService.createMappingSet(
                materialized.questionVersionId(), new MappingSetRequest(releaseId, mappingItems));
        questionService.reviewMappingSet(mappingSetId,
                new MappingSetReviewRequest("REVIEWED", "Synthetic test fixture review",
                        List.of("test-fixture://phase05/mapping-review")), email);
        questionService.reviewQuestion(materialized.questionVersionId(),
                new QuestionReviewRequest("PENDING_REVIEW", "Synthetic test fixture",
                        List.of("test-fixture://phase05/question-review"), null, null, null, null), email);
        questionService.reviewQuestion(materialized.questionVersionId(),
                new QuestionReviewRequest("IN_REVIEW", "Synthetic test fixture",
                        List.of("test-fixture://phase05/question-review"), null, null, null, null), email);
        questionService.reviewQuestion(materialized.questionVersionId(),
                new QuestionReviewRequest("APPROVED", "Synthetic test fixture; test database only",
                        List.of("test-fixture://phase05/synthetic-approval"), releaseId, "test-1",
                        "test-fixture://phase05/synthetic-rights", null), email);
        questionService.reviewMappingSet(mappingSetId,
                new MappingSetReviewRequest("PUBLISHED", "Synthetic test fixture publication",
                        List.of("test-fixture://phase05/synthetic-mapping-publication")), email);
        questionService.publishQuestionVersion(materialized.questionVersionId());
    }

    private void createDraftQuestionVersion(String email, String suffix, long sectionId, int index) {
        ObjectNode question = mapper.createObjectNode()
                .put("question_id", "draft-" + suffix)
                .put("stem", "Synthetic draft question excluded from builder")
                .put("question_type", "SINGLE_CHOICE");
        question.putObject("metadata").put("exam", "TSA").put("cognitive_level", "M1");
        question.putArray("options")
                .addObject().put("key", "A").put("text", "Synthetic option A");
        question.withArray("options").addObject().put("key", "B").put("text", "Synthetic option B");
        question.putArray("correct_answer").add("A");
        var imported = questionService.stageQuestions(
                new ImportBatchRequest("phase05-draft-" + suffix + "-" + index,
                        "test-fixture://phase05/draft-question", List.of(question)), email);
        questionService.materializeQuestion(
                imported.items().get(0).importItemId(), new MaterializeRequest(sectionId, "HUMAN"));
    }

    private long materializeDraftGroupMember(String email, String suffix, long sectionId) {
        ObjectNode question = mapper.createObjectNode()
                .put("question_id", "phase05-group-member-" + suffix)
                .put("stem", "Synthetic grouped question")
                .put("question_type", "SINGLE_CHOICE");
        question.putObject("metadata").put("exam", "TSA");
        question.putArray("options").addObject().put("key", "A").put("text", "Option A");
        question.withArray("options").addObject().put("key", "B").put("text", "Option B");
        question.putArray("correct_answer").add("A");
        var imported = questionService.stageQuestions(
                new ImportBatchRequest("phase05-group-member-" + suffix,
                        "test-only.json", List.of(question)), email);
        return questionService.materializeQuestion(
                imported.items().get(0).importItemId(), new MaterializeRequest(sectionId, "HUMAN"))
                .questionVersionId();
    }

    private long sectionIdFor(Fixture fixture) {
        return jdbc.queryForObject("""
                SELECT section_id FROM question_versions WHERE question_version_id = ?
                """, Long.class, fixture.questionVersionId());
    }

    private BlueprintRequest blueprintRequest(
            PublishedBankFixture fixture, String testType, int questionCount, SelectionRules selectionRules) {
        return new BlueprintRequest("Synthetic " + testType, "Isolated acceptance fixture",
                fixture.examVersionId(), testType, 30,
                List.of(new SectionRule(fixture.sectionId(), questionCount, null)), selectionRules,
                List.of());
    }

    private void assertBadRequest(Runnable action, String expectedReason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).contains(expectedReason);
                });
    }

    private void assertDatabaseRejected(Runnable mutation, String expectedMessage) {
        assertThatThrownBy(mutation::run)
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(expectedMessage);
    }

    private record Fixture(long userId, long testVersionId, long testItemId, long questionVersionId) {}

    private record PublishedBankFixture(
            long userId, String email, long examVersionId, long sectionId,
            long taxonomyReleaseId, long skillId, Long secondarySkillId, Long groupVersionId) {}

    private record HsaOptionalBranchFixture(
            long userId,
            String email,
            long examVersionId,
            long scienceSectionId,
            long englishSectionId,
            long otherExamSectionId,
            long optionalGroupId,
            long scienceBranchId,
            long hsaReleaseId,
            long scienceSkillId,
            long englishSkillId) {}
}
