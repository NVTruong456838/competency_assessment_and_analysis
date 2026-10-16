package vn.smartassessment.api.assessment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.smartassessment.api.assessment.dto.AssessmentDtos;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.AttemptSummary;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.AvailableTest;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.BlueprintRequest;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.BlueprintVersion;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.BuildRequest;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.BuildResult;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.ExamQuestion;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.ExamSession;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.QuestionGroupStimulus;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.MissingCoverage;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.OptionalBranchSelection;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.ReviewRequest;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.SaveAnswerRequest;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.SaveAnswerResult;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.SubmitResult;

@Service
public class AssessmentService {
    private static final Set<String> TEST_TYPES = Set.of(
            "DIAGNOSTIC", "MINI_MOCK", "FULL_MOCK", "PRACTICE", "CUSTOM");
    private static final Set<String> COGNITIVE_LEVELS = Set.of("M1", "M2", "M3");
    private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");
    private static final Set<String> QUESTION_TYPES = Set.of(
            "SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE", "NUMERIC", "SHORT_ANSWER");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AssessmentService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored blueprint JSON is invalid.", exception);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored blueprint JSON is invalid.", exception);
        }
    }

    @Transactional
    public BlueprintVersion createBlueprint(BlueprintRequest request, String actor) {
        long userId = userId(actor);
        long examVersionId = request.examVersionId();
        String testType = normalizeType(request.testType());
        validateBlueprint(request, examVersionId, testType);
        String code = "BP-" + UUID.randomUUID();
        long blueprintId = jdbc.queryForObject("""
                INSERT INTO test_blueprints
                    (blueprint_code, title, description, exam_version_id, created_by)
                VALUES (?, ?, ?, ?, ?)
                RETURNING blueprint_id
                """, Long.class, code, request.title().trim(), request.description(), examVersionId, userId);
        return insertBlueprintVersion(blueprintId, 1, request, testType);
    }

    @Transactional
    public BlueprintVersion createBlueprintVersion(long blueprintId, BlueprintRequest request) {
        long examVersionId = jdbc.queryForObject("""
                SELECT exam_version_id FROM test_blueprints
                WHERE blueprint_id = ? FOR UPDATE
                """, Long.class, blueprintId);
        if (examVersionId != request.examVersionId()) {
            throw badRequest("A blueprint version must keep the original Exam Version.");
        }
        String testType = normalizeType(request.testType());
        validateBlueprint(request, examVersionId, testType);
        Integer nextVersion = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_number), 0) + 1
                FROM test_blueprint_versions WHERE blueprint_id = ?
                """, Integer.class, blueprintId);
        jdbc.update("""
                UPDATE test_blueprint_versions
                SET is_latest = FALSE
                WHERE blueprint_id = ? AND is_latest
                """, blueprintId);
        jdbc.update("""
                UPDATE test_blueprint_versions
                SET status = 'ARCHIVED'
                WHERE blueprint_id = ? AND status = 'ACTIVE' AND is_latest = FALSE
                """, blueprintId);
        return insertBlueprintVersion(blueprintId, nextVersion, request, testType);
    }

    @Transactional(readOnly = true)
    public List<BlueprintVersion> listBlueprints() {
        return jdbc.query("""
                SELECT b.blueprint_id, v.blueprint_version_id, b.blueprint_code, b.title,
                       e.code AS exam, ev.version_code AS exam_version,
                       v.version_number, v.test_type, v.duration_minutes,
                       v.section_rules, v.selection_rules, v.status,
                       COALESCE((
                           SELECT jsonb_agg(jsonb_build_object(
                               'optionalSectionGroupId', choice.optional_section_group_id,
                               'branchCode', branch.branch_code
                           ) ORDER BY choice.optional_section_group_id)
                           FROM test_blueprint_optional_branches choice
                           JOIN assessment_optional_section_branches branch
                             ON branch.optional_section_branch_id = choice.optional_section_branch_id
                           WHERE choice.blueprint_version_id = v.blueprint_version_id
                       ), '[]'::jsonb) AS optional_branch_selections
                FROM test_blueprints b
                JOIN test_blueprint_versions v ON v.blueprint_id = b.blueprint_id
                JOIN exam_versions ev ON ev.exam_version_id = b.exam_version_id
                JOIN exams e ON e.exam_id = ev.exam_id
                WHERE v.is_latest
                ORDER BY v.created_at DESC, b.blueprint_id DESC
                """, blueprintVersionMapper());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> availableExamVersions() {
        return jdbc.queryForList("""
                SELECT ev.exam_version_id, e.code AS exam, e.name AS exam_name,
                       ev.version_code, ev.name AS version_name, ev.status,
                       s.section_id, s.section_code, s.section_name, s.order_no,
                       g.optional_section_group_id, g.group_code AS optional_group_code,
                       g.group_name AS optional_group_name, b.branch_code AS optional_branch_code,
                       b.branch_name AS optional_branch_name
                FROM exam_versions ev
                JOIN exams e ON e.exam_id = ev.exam_id
                JOIN sections s ON s.exam_version_id = ev.exam_version_id
                LEFT JOIN assessment_optional_section_branch_sections bs
                  ON bs.section_id = s.section_id
                LEFT JOIN assessment_optional_section_branches b
                  ON b.optional_section_branch_id = bs.optional_section_branch_id
                LEFT JOIN assessment_optional_section_groups g
                  ON g.optional_section_group_id = b.optional_section_group_id
                 AND g.status = 'ACTIVE'
                WHERE ev.status IN ('ACTIVE', 'DRAFT')
                ORDER BY e.code, ev.version_code, s.order_no
                """);
    }

    @Transactional
    public BuildResult buildTest(BuildRequest request, String actor) {
        long userId = userId(actor);
        BlueprintVersion blueprint = blueprintVersion(request.blueprintVersionId());
        if (!"ACTIVE".equals(blueprint.status())) {
            throw badRequest("Only an active blueprint version can be used to build a test.");
        }
        String releaseCatalogType = jdbc.query("""
                SELECT tc.catalog_type
                FROM taxonomy_releases tr
                JOIN taxonomy_catalogs tc ON tc.taxonomy_catalog_id = tr.taxonomy_catalog_id
                WHERE tr.taxonomy_release_id = ? AND tr.status = 'PUBLISHED'
                """, rs -> rs.next() ? rs.getString(1) : null, request.taxonomyReleaseId());
        if (releaseCatalogType == null) {
            throw badRequest("Test building requires a published taxonomy release.");
        }

        BlueprintRules rules = blueprintRules(request.blueprintVersionId());
        long examVersionId = jdbc.queryForObject("""
                SELECT b.exam_version_id
                FROM test_blueprints b
                JOIN test_blueprint_versions v ON v.blueprint_id = b.blueprint_id
                WHERE v.blueprint_version_id = ?
                """, Long.class, request.blueprintVersionId());
        String examCode = jdbc.queryForObject("""
                SELECT e.code
                FROM exam_versions ev
                JOIN exams e ON e.exam_id = ev.exam_id
                WHERE ev.exam_version_id = ?
                """, String.class, examVersionId);
        validateOptionalBranchSelections(
                blueprint.optionalBranchSelections(),
                examVersionId,
                rules.sections().stream()
                        .map(AssessmentDtos.SectionRule::sectionId)
                        .collect(Collectors.toSet()));
        if (!examCode.equalsIgnoreCase(releaseCatalogType)) {
            throw badRequest("The published taxonomy release is not compatible with this Exam Version.");
        }
        validateReleaseSkills(request.taxonomyReleaseId(), rules.skillIds());
        List<SelectedQuestion> selected = new ArrayList<>();
        List<MissingCoverage> missing = new ArrayList<>();
        int requested = rules.sections().stream().mapToInt(AssessmentDtos.SectionRule::questionCount).sum();
        RandomGenerator random = new java.util.Random(request.seed());

        for (var sectionRule : rules.sections()) {
            List<SelectedQuestion> candidates = eligibleQuestions(
                    sectionRule.sectionId(), examVersionId, request.taxonomyReleaseId(), rules.skillIds());
            List<SelectedQuestion> sectionSelection = chooseQuestions(
                    candidates, sectionRule.questionCount(), rules, random);
            selected.addAll(sectionSelection);
            if (sectionSelection.size() != sectionRule.questionCount()) {
                missing.add(new MissingCoverage(
                        sectionRule.sectionId(),
                        sectionRule.questionCount(),
                        sectionSelection.size(),
                        candidates.size(),
                        describeConstraints(rules)));
            }
        }
        if (!missing.isEmpty()) {
            return new BuildResult(null, null, "INSUFFICIENT_QUESTIONS", request.seed(),
                    requested, selected.size(), null, missing);
        }

        String snapshot = selected.stream()
                .map(question -> question.sectionId() + ":" + question.questionVersionId()
                        + ":" + question.mappingSetId() + ":"
                        + (question.groupVersionId() == null ? "" : question.groupVersionId())
                        + ":" + question.groupItemOrder())
                .collect(Collectors.joining("|"));
        String contentHash = sha256(request.blueprintVersionId() + "|" + request.taxonomyReleaseId()
                + "|" + request.seed() + "|" + snapshot);
        String testCode = "TEST-" + UUID.randomUUID();
        long testId = jdbc.queryForObject("""
                INSERT INTO tests (test_code, title, description, exam_version_id,
                                   test_type, status, created_by, blueprint_version_id)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?)
                RETURNING test_id
                """, Long.class, testCode, request.title().trim(),
                "Built from blueprint " + blueprint.blueprintCode() + " v" + blueprint.versionNumber(),
                examVersionId, blueprint.testType(), userId, request.blueprintVersionId());
        long versionId = jdbc.queryForObject("""
                INSERT INTO test_versions
                    (test_id, version_number, duration_minutes, total_questions,
                     total_score, shuffle_questions, shuffle_options, status,
                     taxonomy_release_id, builder_seed, content_sha256)
                VALUES (?, 1, ?, ?, ?, FALSE, FALSE, 'DRAFT', ?, ?, ?)
                RETURNING test_version_id
                """, Long.class, testId, blueprint.durationMinutes(), selected.size(),
                BigDecimal.valueOf(selected.size()), request.taxonomyReleaseId(), request.seed(), contentHash);

        Map<Long, Long> testSectionIds = new HashMap<>();
        for (int index = 0; index < rules.sections().size(); index++) {
            var sectionRule = rules.sections().get(index);
            Long testSectionId = jdbc.queryForObject("""
                    INSERT INTO test_sections
                        (test_version_id, section_id, section_order, question_count, duration_minutes)
                    VALUES (?, ?, ?, ?, ?)
                    RETURNING test_section_id
                    """, Long.class, versionId, sectionRule.sectionId(), index + 1,
                    sectionRule.questionCount(), sectionRule.durationMinutes());
            testSectionIds.put(sectionRule.sectionId(), testSectionId);
        }
        for (int index = 0; index < selected.size(); index++) {
            SelectedQuestion question = selected.get(index);
            jdbc.update("""
                    INSERT INTO test_items
                        (test_version_id, test_section_id, question_version_id,
                         item_order, points, is_required, mapping_set_id, taxonomy_release_id,
                         question_group_version_id)
                    VALUES (?, ?, ?, ?, 1, TRUE, ?, ?, ?)
                    """, versionId, testSectionIds.get(question.sectionId()),
                    question.questionVersionId(), index + 1,
                    question.mappingSetId(), request.taxonomyReleaseId(), question.groupVersionId());
        }
        return new BuildResult(testId, versionId, "DRAFT", request.seed(),
                requested, selected.size(), contentHash, List.of());
    }

    @Transactional
    public void publishTest(long testVersionId, String actor) {
        long userId = userId(actor);
        Map<String, Object> version = jdbc.query("""
                SELECT test_id, total_questions, taxonomy_release_id, status
                FROM test_versions
                WHERE test_version_id = ?
                FOR UPDATE
                """, rs -> rs.next() ? Map.of(
                "testId", rs.getLong("test_id"),
                "total", rs.getInt("total_questions"),
                "release", rs.getLong("taxonomy_release_id"),
                "status", rs.getString("status")) : null, testVersionId);
        if (version == null) throw notFound("Test Version not found.");
        if (!"DRAFT".equals(version.get("status"))) {
            throw badRequest("Only a draft Test Version can be published.");
        }
        Integer actualCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM test_items WHERE test_version_id = ?
                """, Integer.class, testVersionId);
        if (!version.get("total").equals(actualCount) || actualCount == null || actualCount <= 0) {
            throw badRequest("Test Version item count does not match its immutable snapshot.");
        }
        Integer eligibleCount = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT ti.test_item_id)
                FROM test_items ti
                JOIN test_versions tv ON tv.test_version_id = ti.test_version_id
                JOIN question_versions qv
                  ON qv.question_version_id = ti.question_version_id
                 AND qv.is_latest AND qv.review_status = 'APPROVED'
                JOIN questions q ON q.question_id = qv.question_id AND q.status = 'ACTIVE'
                JOIN question_mapping_sets ms
                  ON ms.mapping_set_id = ti.mapping_set_id
                 AND ms.question_version_id = qv.question_version_id
                 AND ms.taxonomy_release_id = ti.taxonomy_release_id
                 AND ms.status = 'PUBLISHED' AND ms.published_at IS NOT NULL
                JOIN taxonomy_releases tr
                  ON tr.taxonomy_release_id = tv.taxonomy_release_id
                 AND tr.status = 'PUBLISHED'
                JOIN question_review qr
                  ON qr.question_version_id = qv.question_version_id
                 AND qr.review_status = 'APPROVED'
                 AND qr.taxonomy_release_id = tv.taxonomy_release_id
                 AND NULLIF(BTRIM(qr.source_rights_evidence_uri), '') IS NOT NULL
                 AND jsonb_typeof(qr.evidence_refs) = 'array'
                 AND qr.evidence_refs <> '[]'::jsonb
                WHERE ti.test_version_id = ?
                  AND ti.taxonomy_release_id = tv.taxonomy_release_id
                  AND NOT EXISTS (
                      SELECT 1 FROM visual_asset va
                      WHERE va.question_version_id = qv.question_version_id
                        AND (va.rights_status <> 'VERIFIED' OR va.needs_review)
                  )
                """, Integer.class, testVersionId);
        if (!actualCount.equals(eligibleCount)) {
            throw badRequest("Every item must still satisfy the published Question Bank and rights gates.");
        }
        jdbc.update("""
                UPDATE test_versions
                SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP, published_by = ?
                WHERE test_version_id = ?
                """, userId, testVersionId);
        jdbc.update("UPDATE tests SET status = 'PUBLISHED' WHERE test_id = ?",
                version.get("testId"));
    }

    @Transactional(readOnly = true)
    public List<AvailableTest> availableTests(long userId) {
        return jdbc.query("""
                SELECT tv.test_version_id, t.test_id, t.title, t.description,
                       e.code AS exam, t.test_type, tv.duration_minutes,
                       tv.total_questions, r.taxonomy_version,
                       (SELECT a.attempt_id FROM attempts a
                        WHERE a.test_version_id = tv.test_version_id
                          AND a.user_id = ? AND a.status = 'IN_PROGRESS'
                        ORDER BY a.started_at DESC LIMIT 1) AS active_attempt_id
                FROM tests t
                JOIN test_versions tv ON tv.test_id = t.test_id
                JOIN exam_versions ev ON ev.exam_version_id = t.exam_version_id
                JOIN exams e ON e.exam_id = ev.exam_id
                JOIN taxonomy_releases r ON r.taxonomy_release_id = tv.taxonomy_release_id
                WHERE t.status = 'PUBLISHED' AND tv.status = 'PUBLISHED'
                ORDER BY t.created_at DESC, tv.test_version_id DESC
                """, (rs, row) -> new AvailableTest(
                rs.getLong("test_version_id"), rs.getLong("test_id"),
                rs.getString("title"), rs.getString("description"),
                rs.getString("exam"), rs.getString("test_type"),
                rs.getInt("duration_minutes"), rs.getInt("total_questions"),
                rs.getString("taxonomy_version"),
                (Long) rs.getObject("active_attempt_id")), userId);
    }

    @Transactional
    public ExamSession startAttempt(long testVersionId, long userId) {
        Map<String, Object> test = jdbc.query("""
                SELECT tv.test_version_id, tv.duration_minutes, tv.status, tv.total_questions,
                       t.title, e.code AS exam, t.test_id
                FROM test_versions tv
                JOIN tests t ON t.test_id = tv.test_id
                JOIN exam_versions ev ON ev.exam_version_id = t.exam_version_id
                JOIN exams e ON e.exam_id = ev.exam_id
                WHERE tv.test_version_id = ?
                FOR UPDATE OF tv
                """, rs -> rs.next() ? Map.of(
                "duration", rs.getInt("duration_minutes"),
                "status", rs.getString("status"),
                "total", rs.getInt("total_questions"),
                "title", rs.getString("title"),
                "exam", rs.getString("exam"),
                "testId", rs.getLong("test_id"),
                "testVersionId", rs.getLong("test_version_id")) : null, testVersionId);
        if (test == null) throw notFound("Test Version not found.");
        if (!"PUBLISHED".equals(test.get("status"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Test Version chưa được phát hành.");
        }
        Long activeAttempt = jdbc.query("""
                SELECT attempt_id FROM attempts
                WHERE user_id = ? AND test_version_id = ? AND status = 'IN_PROGRESS'
                ORDER BY started_at DESC LIMIT 1 FOR UPDATE
                """, rs -> rs.next() ? rs.getLong(1) : null, userId, testVersionId);
        if (activeAttempt == null) {
            Integer attemptNumber = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(attempt_number), 0) + 1 FROM attempts
                    WHERE user_id = ? AND test_version_id = ?
                    """, Integer.class, userId, testVersionId);
            activeAttempt = jdbc.queryForObject("""
                    INSERT INTO attempts
                        (test_version_id, user_id, attempt_number, status, ends_at)
                    VALUES (?, ?, ?, 'IN_PROGRESS',
                            CURRENT_TIMESTAMP + (? * INTERVAL '1 minute'))
                    RETURNING attempt_id
                    """, Long.class, testVersionId, userId, attemptNumber, test.get("duration"));
            jdbc.update("""
                    INSERT INTO attempt_events (attempt_id, event_type, event_data)
                    VALUES (?, 'STARTED', jsonb_build_object('test_version_id', ?))
                    """, activeAttempt, testVersionId);
        }
        finalizeExpiredAttempt(activeAttempt);
        return examSession(activeAttempt, userId, test);
    }

    @Transactional
    public ExamSession resumeAttempt(long attemptId, long userId) {
        Map<String, Object> test = attemptTest(attemptId, userId, true);
        finalizeExpiredAttempt(attemptId);
        return examSession(attemptId, userId, test);
    }

    @Transactional
    public ExamSession getExamSession(long attemptId, long userId) {
        Map<String, Object> test = attemptTest(attemptId, userId, false);
        finalizeExpiredAttempt(attemptId);
        return examSession(attemptId, userId, test);
    }

    @Transactional
    public SaveAnswerResult saveAnswer(
            long attemptId, long itemId, long userId, SaveAnswerRequest request) {
        AttemptLock attempt = lockActiveAttempt(attemptId, userId);
        Integer existingRevision = jdbc.query("""
                SELECT revision FROM attempt_answers
                WHERE attempt_id = ? AND test_item_id = ?
                """, rs -> rs.next() ? rs.getInt(1) : null, attemptId, itemId);
        UUID lastRequestId = jdbc.query("""
                SELECT last_request_id FROM attempt_answers
                WHERE attempt_id = ? AND test_item_id = ?
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, attemptId, itemId);
        if (request.requestId().equals(lastRequestId)) {
            return new SaveAnswerResult(itemId, existingRevision, false, true);
        }
        int currentRevision = existingRevision == null ? 0 : existingRevision;
        if (request.expectedRevision() != currentRevision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Answer changed elsewhere. Reload the current response before retrying.");
        }
        ItemContext item = itemContext(attempt.testVersionId(), itemId);
        if (item == null) throw notFound("Question is not part of this Test Version.");
        List<String> keys = request.selectedOptionKeys() == null
                ? List.of() : request.selectedOptionKeys().stream().distinct().toList();
        if (keys.size() != (request.selectedOptionKeys() == null ? 0 : request.selectedOptionKeys().size())) {
            throw badRequest("Duplicate option keys are not allowed.");
        }
        boolean choice = item.responseMode().equals("SINGLE_OPTION")
                || item.responseMode().equals("MULTI_OPTION");
        String answerText = request.answerText();
        if (choice && answerText != null && !answerText.isBlank()) {
            throw badRequest("Choice questions accept option keys only.");
        }
        if (!choice && !keys.isEmpty()) {
            throw badRequest("This question does not accept selected options.");
        }
        if (item.responseMode().equals("SINGLE_OPTION") && keys.size() > 1) {
            throw badRequest("A single-choice question accepts only one option.");
        }
        List<Long> optionIds = optionIds(item.questionVersionId(), keys);
        if (optionIds.size() != keys.size()) {
            throw badRequest("One or more selected options do not belong to this question.");
        }
        int nextRevision = currentRevision + 1;
        BigDecimal numericAnswer = null;
        if ("NUMERIC".equals(item.responseMode()) && answerText != null && !answerText.isBlank()) {
            try {
                numericAnswer = new BigDecimal(answerText.trim());
            } catch (NumberFormatException exception) {
                throw badRequest("Numeric answers must be a valid number.");
            }
        }
        Long answerId;
        if (existingRevision == null) {
            answerId = jdbc.queryForObject("""
                    INSERT INTO attempt_answers
                        (attempt_id, test_item_id, test_version_id, question_version_id,
                         answer_text, numeric_answer, revision, last_request_id, answered_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN CURRENT_TIMESTAMP END)
                    RETURNING attempt_answer_id
                    """, Long.class, attemptId, itemId, attempt.testVersionId(),
                    item.questionVersionId(), choice ? null : blankToNull(answerText),
                    numericAnswer, nextRevision, request.requestId(),
                    !keys.isEmpty() || (answerText != null && !answerText.isBlank()));
        } else {
            answerId = jdbc.queryForObject("""
                    UPDATE attempt_answers
                    SET answer_text = ?, numeric_answer = ?, revision = ?,
                        last_request_id = ?, answered_at = CASE
                            WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END
                    WHERE attempt_id = ? AND test_item_id = ?
                    RETURNING attempt_answer_id
                    """, Long.class, choice ? null : blankToNull(answerText), numericAnswer,
                    nextRevision, request.requestId(),
                    !keys.isEmpty() || (answerText != null && !answerText.isBlank()),
                    attemptId, itemId);
        }
        jdbc.update("DELETE FROM attempt_selected_options WHERE attempt_answer_id = ?", answerId);
        for (Long optionId : optionIds) {
            jdbc.update("""
                    INSERT INTO attempt_selected_options
                        (attempt_answer_id, option_id, question_version_id)
                    VALUES (?, ?, ?)
                    """, answerId, optionId, item.questionVersionId());
        }
        jdbc.update("""
                INSERT INTO attempt_events
                    (attempt_id, test_item_id, event_type, event_data, idempotency_key)
                VALUES (?, ?, 'ANSWER_SAVED',
                        jsonb_build_object('revision', ?, 'selected_count', ?), ?)
                """, attemptId, itemId, nextRevision, keys.size(), request.requestId());
        return new SaveAnswerResult(itemId, nextRevision, false, false);
    }

    @Transactional
    public SaveAnswerResult markForReview(
            long attemptId, long itemId, long userId, ReviewRequest request) {
        AttemptLock attempt = lockActiveAttempt(attemptId, userId);
        Integer currentRevision = jdbc.query("""
                SELECT revision FROM attempt_answers
                WHERE attempt_id = ? AND test_item_id = ?
                """, rs -> rs.next() ? rs.getInt(1) : null, attemptId, itemId);
        UUID lastRequestId = jdbc.query("""
                SELECT last_request_id FROM attempt_answers
                WHERE attempt_id = ? AND test_item_id = ?
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, attemptId, itemId);
        if (request.requestId().equals(lastRequestId)) {
            return new SaveAnswerResult(itemId, currentRevision, request.markedForReview(), true);
        }
        int revision = currentRevision == null ? 0 : currentRevision;
        if (revision != request.expectedRevision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Question state changed. Reload and retry.");
        }
        ItemContext item = itemContext(attempt.testVersionId(), itemId);
        if (item == null) throw notFound("Question is not part of this Test Version.");
        int nextRevision = revision + 1;
        if (currentRevision == null) {
            jdbc.update("""
                    INSERT INTO attempt_answers
                        (attempt_id, test_item_id, test_version_id, question_version_id,
                         marked_for_review, revision, last_request_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, attemptId, itemId, attempt.testVersionId(), item.questionVersionId(),
                    request.markedForReview(), nextRevision, request.requestId());
        } else {
            jdbc.update("""
                    UPDATE attempt_answers
                    SET marked_for_review = ?, revision = ?, last_request_id = ?
                    WHERE attempt_id = ? AND test_item_id = ?
                    """, request.markedForReview(), nextRevision, request.requestId(), attemptId, itemId);
        }
        jdbc.update("""
                INSERT INTO attempt_events
                    (attempt_id, test_item_id, event_type, event_data, idempotency_key)
                VALUES (?, ?, 'REVIEW_MARK_CHANGED', jsonb_build_object('marked', ?, 'revision', ?), ?)
                """, attemptId, itemId, request.markedForReview(), nextRevision, request.requestId());
        return new SaveAnswerResult(itemId, nextRevision, request.markedForReview(), false);
    }

    @Transactional
    public SaveAnswerResult clearAnswer(
            long attemptId, long itemId, long userId, SaveAnswerRequest request) {
        List<String> selected = request.selectedOptionKeys() == null ? List.of() : request.selectedOptionKeys();
        if (!selected.isEmpty() || (request.answerText() != null && !request.answerText().isBlank())) {
            throw badRequest("Clear-answer requests must not include answer content.");
        }
        return saveAnswer(attemptId, itemId, userId, request);
    }

    @Transactional
    public SubmitResult submitAttempt(long attemptId, long userId) {
        AttemptLock attempt = lockAttempt(attemptId, userId);
        finalizeExpiredAttempt(attemptId);
        Map<String, Object> current = jdbc.query("""
                SELECT status, submitted_at, auto_submitted
                FROM attempts WHERE attempt_id = ?
                """, rs -> rs.next() ? Map.of(
                "status", rs.getString("status"),
                "submittedAt", rs.getTimestamp("submitted_at") == null
                        ? Instant.EPOCH : rs.getTimestamp("submitted_at").toInstant(),
                "auto", rs.getBoolean("auto_submitted")) : null, attemptId);
        if (!"IN_PROGRESS".equals(current.get("status"))) {
            return submitResult(attemptId, current);
        }
        jdbc.update("""
                UPDATE attempts
                SET status = 'SUBMITTED', submitted_at = CURRENT_TIMESTAMP,
                    time_spent_seconds = GREATEST(0,
                        FLOOR(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - started_at)))::INTEGER),
                    auto_submitted = FALSE
                WHERE attempt_id = ? AND status = 'IN_PROGRESS'
                """, attemptId);
        jdbc.update("""
                INSERT INTO attempt_events (attempt_id, event_type, event_data)
                VALUES (?, 'SUBMITTED', jsonb_build_object('auto', FALSE))
                """, attemptId);
        Map<String, Object> submitted = jdbc.query("""
                SELECT status, submitted_at, auto_submitted FROM attempts WHERE attempt_id = ?
                """, rs -> rs.next() ? Map.of(
                "status", rs.getString("status"),
                "submittedAt", rs.getTimestamp("submitted_at").toInstant(),
                "auto", rs.getBoolean("auto_submitted")) : null, attemptId);
        return submitResult(attemptId, submitted);
    }

    @Scheduled(fixedDelay = 10_000)
    @Transactional
    public void autoSubmitExpiredAttempts() {
        List<Long> expired = jdbc.query("""
                SELECT a.attempt_id
                FROM attempts a
                JOIN test_versions tv ON tv.test_version_id = a.test_version_id
                WHERE a.status = 'IN_PROGRESS'
                  AND COALESCE(a.ends_at,
                      a.started_at + (tv.duration_minutes * INTERVAL '1 minute')) <= CURRENT_TIMESTAMP
                ORDER BY COALESCE(a.ends_at,
                    a.started_at + (tv.duration_minutes * INTERVAL '1 minute'))
                LIMIT 200
                FOR UPDATE OF a SKIP LOCKED
                """, (rs, row) -> rs.getLong(1));
        for (Long attemptId : expired) finalizeExpiredAttempt(attemptId);
    }

    private void finalizeExpiredAttempt(long attemptId) {
        int updated = jdbc.update("""
                UPDATE attempts a
                SET ends_at = COALESCE(a.ends_at,
                        a.started_at + (tv.duration_minutes * INTERVAL '1 minute')),
                    status = 'AUTO_SUBMITTED',
                    submitted_at = COALESCE(a.ends_at,
                        a.started_at + (tv.duration_minutes * INTERVAL '1 minute')),
                    time_spent_seconds = GREATEST(0,
                        FLOOR(EXTRACT(EPOCH FROM (
                            COALESCE(a.ends_at,
                                a.started_at + (tv.duration_minutes * INTERVAL '1 minute'))
                            - a.started_at)))::INTEGER),
                    auto_submitted = TRUE
                FROM test_versions tv
                WHERE a.test_version_id = tv.test_version_id
                  AND a.attempt_id = ? AND a.status = 'IN_PROGRESS'
                  AND COALESCE(a.ends_at,
                      a.started_at + (tv.duration_minutes * INTERVAL '1 minute')) <= CURRENT_TIMESTAMP
                """, attemptId);
        if (updated == 1) {
            jdbc.update("""
                    INSERT INTO attempt_events (attempt_id, event_type, event_data)
                    VALUES (?, 'AUTO_SUBMITTED',
                            jsonb_build_object('reason', 'DEADLINE_EXPIRED'))
                    """, attemptId);
        }
    }

    private ExamSession examSession(long attemptId, long userId, Map<String, Object> test) {
        AttemptSummary attempt = jdbc.query("""
                SELECT a.status, a.started_at,
                       COALESCE(a.ends_at,
                           a.started_at + (tv.duration_minutes * INTERVAL '1 minute')) AS ends_at,
                       GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                           (COALESCE(a.ends_at,
                               a.started_at + (tv.duration_minutes * INTERVAL '1 minute'))
                            - CURRENT_TIMESTAMP)))::BIGINT) AS remaining_seconds,
                       COUNT(DISTINCT ti.test_item_id) AS total_questions,
                       COUNT(DISTINCT aa.test_item_id) FILTER (
                           WHERE aa.answered_at IS NOT NULL) AS answered_questions,
                       COUNT(DISTINCT aa.test_item_id) FILTER (
                           WHERE aa.marked_for_review) AS marked_for_review
                FROM attempts a
                JOIN test_versions tv ON tv.test_version_id = a.test_version_id
                JOIN test_items ti ON ti.test_version_id = a.test_version_id
                LEFT JOIN attempt_answers aa
                  ON aa.attempt_id = a.attempt_id AND aa.test_item_id = ti.test_item_id
                WHERE a.attempt_id = ? AND a.user_id = ?
                GROUP BY a.attempt_id, tv.duration_minutes
                """, rs -> rs.next() ? new AttemptSummary(
                attemptId, rs.getString("status"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("ends_at").toInstant(),
                rs.getLong("remaining_seconds"),
                rs.getInt("total_questions"),
                rs.getInt("answered_questions"),
                rs.getInt("marked_for_review")) : null, attemptId, userId);
        if (attempt == null) throw notFound("Attempt not found.");
        List<ExamQuestion> questions = jdbc.query("""
                SELECT ti.test_item_id, ti.item_order, qv.question_version_id,
                       qv.question_type, qv.response_mode, qv.stem,
                       qv.cognitive_level, qv.estimated_difficulty,
                       s.section_name, p.title AS passage_title, p.content AS passage_content,
                       qgv.question_group_version_id, qgv.title AS group_title,
                       qgv.stimulus_type, qgv.stimulus_json::text AS group_stimulus,
                       gi.item_order AS group_item_order,
                       (SELECT COUNT(*) FROM question_group_version_items group_items
                        WHERE group_items.question_group_version_id = qgv.question_group_version_id)
                           AS group_item_count,
                       aa.answer_text, aa.revision, COALESCE(aa.marked_for_review, FALSE) AS marked,
                       COALESCE((SELECT jsonb_agg(jsonb_build_object(
                           'key', qo.option_key, 'text', qo.option_text
                       ) ORDER BY qo.order_no)
                        FROM question_options qo
                        WHERE qo.question_version_id = qv.question_version_id), '[]'::jsonb)::text AS options_json,
                       COALESCE((SELECT jsonb_agg(jsonb_build_object(
                           'type', va.asset_type, 'path', va.file_path, 'caption', va.caption,
                           'altText', va.alt_text, 'pageNumber', va.page_number, 'bbox', va.bbox
                       ) ORDER BY va.visual_asset_id)
                        FROM visual_asset va
                        WHERE va.question_version_id = qv.question_version_id), '[]'::jsonb)::text AS assets_json,
                       COALESCE((SELECT jsonb_agg(qo.option_key ORDER BY qo.order_no)
                        FROM attempt_answers ans
                        JOIN attempt_selected_options aso ON aso.attempt_answer_id = ans.attempt_answer_id
                        JOIN question_options qo
                          ON qo.option_id = aso.option_id
                         AND qo.question_version_id = ans.question_version_id
                        WHERE ans.attempt_id = ? AND ans.test_item_id = ti.test_item_id), '[]'::jsonb)::text AS selected_json
                FROM test_items ti
                JOIN question_versions qv ON qv.question_version_id = ti.question_version_id
                JOIN test_sections ts ON ts.test_section_id = ti.test_section_id
                JOIN sections s ON s.section_id = ts.section_id
                LEFT JOIN question_group_versions qgv
                  ON qgv.question_group_version_id = ti.question_group_version_id
                 AND qgv.review_status = 'PUBLISHED'
                LEFT JOIN question_group_version_items gi
                  ON gi.question_group_version_id = qgv.question_group_version_id
                 AND gi.question_version_id = qv.question_version_id
                LEFT JOIN passages p ON p.passage_id = qv.passage_id
                LEFT JOIN attempt_answers aa
                  ON aa.attempt_id = ? AND aa.test_item_id = ti.test_item_id
                WHERE ti.test_version_id = ?
                ORDER BY ti.item_order
                """, (rs, row) -> new ExamQuestion(
                rs.getLong("test_item_id"), rs.getInt("item_order"),
                rs.getLong("question_version_id"), rs.getString("question_type"),
                rs.getString("response_mode"), rs.getString("stem"),
                rs.getString("cognitive_level"), rs.getBigDecimal("estimated_difficulty"),
                rs.getString("section_name"), rs.getString("passage_title"),
                rs.getString("passage_content"),
                jsonList(rs.getString("options_json")),
                jsonList(rs.getString("assets_json")),
                jsonStringList(rs.getString("selected_json")),
                rs.getString("answer_text"),
                (Integer) rs.getObject("revision"),
                rs.getBoolean("marked"),
                rs.getObject("question_group_version_id") == null ? null : new QuestionGroupStimulus(
                        rs.getLong("question_group_version_id"),
                        rs.getString("group_title"),
                        rs.getString("stimulus_type"),
                        parseJson(rs.getString("group_stimulus")),
                        rs.getInt("group_item_order"),
                        rs.getInt("group_item_count"))), attemptId, attemptId, test.get("testVersionId"));
        return new ExamSession(attempt, (String) test.get("title"),
                (String) test.get("exam"), questions);
    }

    private Map<String, Object> attemptTest(long attemptId, long userId, boolean resumeOnly) {
        Map<String, Object> result = jdbc.query("""
                SELECT a.test_version_id, a.status, t.title, e.code AS exam
                FROM attempts a
                JOIN test_versions tv ON tv.test_version_id = a.test_version_id
                JOIN tests t ON t.test_id = tv.test_id
                JOIN exam_versions ev ON ev.exam_version_id = t.exam_version_id
                JOIN exams e ON e.exam_id = ev.exam_id
                WHERE a.attempt_id = ? AND a.user_id = ?
                """, rs -> rs.next() ? Map.of(
                "testVersionId", rs.getLong("test_version_id"),
                "status", rs.getString("status"),
                "title", rs.getString("title"),
                "exam", rs.getString("exam")) : null, attemptId, userId);
        if (result == null) throw notFound("Attempt not found.");
        if (resumeOnly && !"IN_PROGRESS".equals(result.get("status"))) {
            throw badRequest("Only an in-progress attempt can be resumed.");
        }
        return result;
    }

    private AttemptLock lockAttempt(long attemptId, long userId) {
        AttemptLock attempt = jdbc.query("""
                SELECT a.test_version_id, a.status,
                       COALESCE(a.ends_at,
                           a.started_at + (tv.duration_minutes * INTERVAL '1 minute')) AS ends_at
                FROM attempts a
                JOIN test_versions tv ON tv.test_version_id = a.test_version_id
                WHERE a.attempt_id = ? AND a.user_id = ? FOR UPDATE OF a
                """, rs -> rs.next() ? new AttemptLock(
                rs.getLong("test_version_id"), rs.getString("status"),
                rs.getTimestamp("ends_at") == null ? null : rs.getTimestamp("ends_at").toInstant()) : null,
                attemptId, userId);
        if (attempt == null) throw notFound("Attempt not found.");
        return attempt;
    }

    private AttemptLock lockActiveAttempt(long attemptId, long userId) {
        AttemptLock attempt = lockAttempt(attemptId, userId);
        if (!"IN_PROGRESS".equals(attempt.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Attempt is no longer in progress.");
        }
        Boolean deadlineExpired = jdbc.queryForObject("""
                SELECT COALESCE(a.ends_at,
                    a.started_at + (tv.duration_minutes * INTERVAL '1 minute')) <= CURRENT_TIMESTAMP
                FROM attempts a
                JOIN test_versions tv ON tv.test_version_id = a.test_version_id
                WHERE a.attempt_id = ?
                """, Boolean.class, attemptId);
        if (attempt.endsAt() == null || Boolean.TRUE.equals(deadlineExpired)) {
            finalizeExpiredAttempt(attemptId);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The server-side exam deadline has expired.");
        }
        return attempt;
    }

    private ItemContext itemContext(long testVersionId, long itemId) {
        return jdbc.query("""
                SELECT ti.question_version_id, qv.response_mode
                FROM test_items ti
                JOIN question_versions qv ON qv.question_version_id = ti.question_version_id
                WHERE ti.test_version_id = ? AND ti.test_item_id = ?
                """, rs -> rs.next()
                ? new ItemContext(rs.getLong(1), rs.getString(2)) : null, testVersionId, itemId);
    }

    private List<Long> optionIds(long questionVersionId, List<String> keys) {
        if (keys.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(keys.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(questionVersionId);
        args.addAll(keys);
        return jdbc.query("""
                SELECT option_id FROM question_options
                WHERE question_version_id = ? AND option_key IN (""" + placeholders + ")",
                (rs, row) -> rs.getLong(1), args.toArray());
    }

    private List<SelectedQuestion> eligibleQuestions(
            long sectionId, long examVersionId, long releaseId, List<Long> skillIds) {
        String skillClause = skillIds.isEmpty() ? "" : """
                 AND EXISTS (
                     SELECT 1 FROM question_mapping_set_items msi
                     WHERE msi.mapping_set_id = ms.mapping_set_id
                       AND msi.role <> 'EXCLUDED'
                       AND msi.skill_id IN (
                """ + String.join(",", java.util.Collections.nCopies(skillIds.size(), "?")) + """
                     )
                 )
                """;
        List<Object> args = new ArrayList<>(List.of(releaseId, sectionId, examVersionId, releaseId));
        args.addAll(skillIds);
        return jdbc.query("""
                SELECT DISTINCT qv.question_version_id, qv.section_id,
                       ms.mapping_set_id, qv.cognitive_level,
                       gi.question_group_version_id,
                       COALESCE(gi.item_order, 1) AS group_item_order,
                       CASE WHEN gi.question_group_version_id IS NULL THEN 1
                            ELSE (SELECT COUNT(*) FROM question_group_version_items group_members
                                  WHERE group_members.question_group_version_id = gi.question_group_version_id)
                       END AS group_item_count,
                       CASE
                         WHEN qv.estimated_difficulty < 0.34 THEN 'EASY'
                         WHEN qv.estimated_difficulty < 0.67 THEN 'MEDIUM'
                         WHEN qv.estimated_difficulty IS NULL THEN 'UNKNOWN'
                         ELSE 'HARD'
                       END AS difficulty_bucket,
                       qv.question_type
                FROM question_versions qv
                JOIN questions q ON q.question_id = qv.question_id AND q.status = 'ACTIVE'
                JOIN sections s ON s.section_id = qv.section_id
                JOIN exam_versions ev ON ev.exam_version_id = s.exam_version_id
                JOIN question_review qr
                  ON qr.question_version_id = qv.question_version_id
                 AND qr.review_status = 'APPROVED'
                 AND NULLIF(BTRIM(qr.source_rights_evidence_uri), '') IS NOT NULL
                JOIN question_mapping_sets ms
                  ON ms.question_version_id = qv.question_version_id
                 AND ms.taxonomy_release_id = ?
                 AND ms.status = 'PUBLISHED'
                 AND ms.published_at IS NOT NULL
                LEFT JOIN question_group_version_items gi
                  ON gi.question_version_id = qv.question_version_id
                LEFT JOIN question_group_versions qgv
                  ON qgv.question_group_version_id = gi.question_group_version_id
                JOIN taxonomy_releases tr
                  ON tr.taxonomy_release_id = ms.taxonomy_release_id
                 AND tr.status = 'PUBLISHED'
                WHERE qv.section_id = ? AND ev.exam_version_id = ?
                  AND qv.is_latest AND qv.review_status = 'APPROVED'
                  AND EXISTS (
                      SELECT 1 FROM question_review approval
                      WHERE approval.question_version_id = qv.question_version_id
                        AND approval.review_status = 'APPROVED'
                        AND approval.taxonomy_release_id = ?
                        AND jsonb_typeof(approval.evidence_refs) = 'array'
                        AND approval.evidence_refs <> '[]'::jsonb
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM visual_asset va
                      WHERE va.question_version_id = qv.question_version_id
                        AND (va.rights_status <> 'VERIFIED' OR va.needs_review)
                  )
                  AND (gi.question_group_version_id IS NULL OR qgv.review_status = 'PUBLISHED')
                """ + skillClause + """
                ORDER BY qv.question_version_id
                """, (rs, row) -> new SelectedQuestion(
                rs.getLong("question_version_id"), rs.getLong("section_id"),
                rs.getLong("mapping_set_id"), rs.getString("cognitive_level"),
                rs.getString("difficulty_bucket"), rs.getString("question_type"),
                (Long) rs.getObject("question_group_version_id"),
                rs.getInt("group_item_order"), rs.getInt("group_item_count")),
                args.toArray());
    }

    private List<SelectedQuestion> chooseQuestions(
            List<SelectedQuestion> candidates,
            int count,
            BlueprintRules rules,
            RandomGenerator random) {
        List<SelectedQuestion> remaining = new ArrayList<>(candidates);
        java.util.Collections.shuffle(remaining, new java.util.Random(random.nextLong()));
        List<SelectedQuestion> selected = new ArrayList<>();
        Map<String, Integer> cognitiveTargets = quotas(rules.cognitiveDistribution(), count);
        Map<String, Integer> difficultyTargets = quotas(rules.difficultyDistribution(), count);
        Map<String, Integer> typeTargets = quotas(rules.typeDistribution(), count);
        Map<String, Integer> cognitiveCounts = new HashMap<>();
        Map<String, Integer> difficultyCounts = new HashMap<>();
        Map<String, Integer> typeCounts = new HashMap<>();
        while (selected.size() < count && !remaining.isEmpty()) {
            List<SelectedQuestion> best = null;
            int bestScore = Integer.MIN_VALUE;
            Set<Long> consideredGroups = new HashSet<>();
            for (SelectedQuestion candidate : remaining) {
                if (candidate.groupVersionId() != null && !consideredGroups.add(candidate.groupVersionId())) continue;
                List<SelectedQuestion> unit = candidate.groupVersionId() == null
                        ? List.of(candidate)
                        : remaining.stream().filter(item ->
                                candidate.groupVersionId().equals(item.groupVersionId()))
                                .sorted(Comparator.comparingInt(SelectedQuestion::groupItemOrder)).toList();
                if (unit.size() != candidate.groupItemCount() || selected.size() + unit.size() > count) continue;
                Map<String, Integer> localCognitive = new HashMap<>(cognitiveCounts);
                Map<String, Integer> localDifficulty = new HashMap<>(difficultyCounts);
                Map<String, Integer> localType = new HashMap<>(typeCounts);
                int score = 0;
                boolean fits = true;
                for (SelectedQuestion member : unit) {
                    if (exceedsTarget(member.cognitiveLevel(), cognitiveTargets, localCognitive)
                            || exceedsTarget(member.difficulty(), difficultyTargets, localDifficulty)
                            || exceedsTarget(member.questionType(), typeTargets, localType)) {
                        fits = false;
                        break;
                    }
                    score += deficit(member.cognitiveLevel(), cognitiveTargets, localCognitive)
                            + deficit(member.difficulty(), difficultyTargets, localDifficulty)
                            + deficit(member.questionType(), typeTargets, localType);
                    increment(localCognitive, member.cognitiveLevel());
                    increment(localDifficulty, member.difficulty());
                    increment(localType, member.questionType());
                }
                if (!fits) continue;
                if (score > bestScore) {
                    best = unit;
                    bestScore = score;
                }
            }
            if (best == null) break;
            for (SelectedQuestion member : best) {
                selected.add(member);
                remaining.remove(member);
                increment(cognitiveCounts, member.cognitiveLevel());
                increment(difficultyCounts, member.difficulty());
                increment(typeCounts, member.questionType());
            }
        }
        return selected;
    }

    private void validateBlueprint(BlueprintRequest request, long examVersionId, String testType) {
        if (!TEST_TYPES.contains(testType)) throw badRequest("Unsupported test type.");
        if (request.durationMinutes() > 600) throw badRequest("Duration cannot exceed 600 minutes.");
        if (request.sections().size() > 20) throw badRequest("A blueprint cannot contain more than 20 sections.");
        Set<Long> sectionIds = new HashSet<>();
        int total = 0;
        for (var section : request.sections()) {
            if (!sectionIds.add(section.sectionId())) throw badRequest("A section may appear only once.");
            if (section.questionCount() < 1 || section.questionCount() > 200) {
                throw badRequest("Section question count must be between 1 and 200.");
            }
            if (section.durationMinutes() != null && section.durationMinutes() > request.durationMinutes()) {
                throw badRequest("Section duration cannot exceed the total test duration.");
            }
            Integer belongs = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sections WHERE section_id = ? AND exam_version_id = ?
                    """, Integer.class, section.sectionId(), examVersionId);
            if (belongs == null || belongs != 1) {
                throw badRequest("Every section must belong to the selected Exam Version.");
            }
            total += section.questionCount();
        }
        if (total > 200) throw badRequest("A blueprint cannot contain more than 200 questions.");
        validateOptionalBranchSelections(
                optionalBranchSelections(request), examVersionId, sectionIds);
        validateDistribution(request.selectionRules().cognitiveLevelDistribution(),
                COGNITIVE_LEVELS, "cognitive level");
        validateDistribution(request.selectionRules().difficultyDistribution(),
                DIFFICULTIES, "difficulty");
        validateDistribution(request.selectionRules().questionTypeDistribution(),
                QUESTION_TYPES, "question type");
        if (request.selectionRules().skillIds() != null
                && request.selectionRules().skillIds().stream().distinct().count()
                != request.selectionRules().skillIds().size()) {
            throw badRequest("Skill filters cannot contain duplicates.");
        }
        validateSkillsMatchSelectedSections(request.selectionRules().skillIds(), sectionIds);
    }

    private void validateOptionalBranchSelections(
            List<OptionalBranchSelection> selections,
            long examVersionId,
            Set<Long> selectedSectionIds) {
        List<OptionalBranchConfig> configuredBranches = jdbc.query("""
                SELECT g.optional_section_group_id, b.optional_section_branch_id,
                       UPPER(BTRIM(b.branch_code)) AS branch_code,
                       bs.section_id, g.status AS group_status
                FROM assessment_optional_section_groups g
                JOIN assessment_optional_section_branches b
                  ON b.optional_section_group_id = g.optional_section_group_id
                JOIN assessment_optional_section_branch_sections bs
                  ON bs.optional_section_branch_id = b.optional_section_branch_id
                WHERE g.exam_version_id = ?
                """, (rs, row) -> new OptionalBranchConfig(
                rs.getLong("optional_section_group_id"),
                rs.getLong("optional_section_branch_id"),
                rs.getString("branch_code"),
                rs.getLong("section_id"),
                rs.getString("group_status")), examVersionId);

        Map<Long, OptionalBranchSelection> selectedBranches = new HashMap<>();
        for (OptionalBranchSelection selection : selections) {
            if (selectedBranches.put(selection.optionalSectionGroupId(), selection) != null) {
                throw badRequest("Only one optional branch may be selected for each optional section group.");
            }
        }

        Map<Long, List<OptionalBranchConfig>> configsByGroup = configuredBranches.stream()
                .collect(Collectors.groupingBy(OptionalBranchConfig::groupId));
        Set<Long> usedGroups = new HashSet<>();
        for (OptionalBranchConfig config : configuredBranches) {
            if (selectedSectionIds.contains(config.sectionId())) usedGroups.add(config.groupId());
        }

        for (Long groupId : usedGroups) {
            OptionalBranchSelection selection = selectedBranches.get(groupId);
            if (selection == null) {
                throw badRequest("An optional section requires an explicit branch selection.");
            }
            List<OptionalBranchConfig> groupConfigs = configsByGroup.get(groupId);
            if (groupConfigs.stream().anyMatch(config -> !"ACTIVE".equals(config.groupStatus()))) {
                throw badRequest("The optional section group is inactive for this Exam Version.");
            }
            String selectedCode = normalizeBranchCode(selection.branchCode());
            boolean knownBranch = groupConfigs.stream()
                    .anyMatch(config -> config.branchCode().equals(selectedCode));
            if (!knownBranch) {
                throw badRequest("The selected optional branch is not configured for this Exam Version.");
            }
            boolean mixedBranch = groupConfigs.stream()
                    .anyMatch(config -> selectedSectionIds.contains(config.sectionId())
                            && !config.branchCode().equals(selectedCode));
            if (mixedBranch) {
                throw badRequest("Question sections from different optional branches cannot be mixed.");
            }
        }

        for (var entry : selectedBranches.entrySet()) {
            if (!configsByGroup.containsKey(entry.getKey())) {
                throw badRequest("The selected optional branch does not belong to this Exam Version.");
            }
            if (!usedGroups.contains(entry.getKey())) {
                throw badRequest("A selected optional branch must include a question-bearing section.");
            }
        }
    }

    private void validateSkillsMatchSelectedSections(List<Long> skillIds, Set<Long> sectionIds) {
        if (skillIds == null || skillIds.isEmpty()) return;
        String skillPlaceholders = String.join(",", java.util.Collections.nCopies(skillIds.size(), "?"));
        String sectionPlaceholders = String.join(",", java.util.Collections.nCopies(sectionIds.size(), "?"));
        List<Object> arguments = new ArrayList<>(skillIds);
        arguments.addAll(sectionIds);
        Integer compatibleSkills = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT s.skill_id)
                FROM skills s
                JOIN topics t ON t.topic_id = s.topic_id
                JOIN domains d ON d.domain_id = t.domain_id
                JOIN competencies c ON c.competency_id = d.competency_id
                JOIN section_competencies sc ON sc.competency_id = c.competency_id
                WHERE s.skill_id IN (""" + skillPlaceholders + """
                )
                AND sc.section_id IN (""" + sectionPlaceholders + ")",
                Integer.class, arguments.toArray());
        if (compatibleSkills == null || compatibleSkills != skillIds.size()) {
            throw badRequest("Every selected Skill must be compatible with a selected question section.");
        }
    }

    private void validateReleaseSkills(long taxonomyReleaseId, List<Long> skillIds) {
        if (skillIds.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(skillIds.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(taxonomyReleaseId);
        arguments.addAll(skillIds);
        Integer activeSkills = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT skill_id)
                FROM taxonomy_release_members
                WHERE taxonomy_release_id = ?
                  AND membership_status = 'ACTIVE'
                  AND skill_id IN (""" + placeholders + ")",
                Integer.class, arguments.toArray());
        if (activeSkills == null || activeSkills != skillIds.size()) {
            throw badRequest("Every selected Skill must be active in the published Taxonomy Release.");
        }
    }

    private static List<OptionalBranchSelection> optionalBranchSelections(BlueprintRequest request) {
        return request.optionalBranchSelections() == null ? List.of() : request.optionalBranchSelections();
    }

    private static String normalizeBranchCode(String branchCode) {
        return branchCode.trim().toUpperCase(Locale.ROOT);
    }

    private static void validateDistribution(
            Map<String, Integer> distribution, Set<String> allowed, String label) {
        if (distribution == null || distribution.isEmpty()) return;
        int total = 0;
        for (var entry : distribution.entrySet()) {
            String key = entry.getKey().toUpperCase(Locale.ROOT);
            if (!allowed.contains(key)) throw badRequest("Invalid " + label + " distribution key: " + key);
            if (entry.getValue() == null || entry.getValue() < 0 || entry.getValue() > 100) {
                throw badRequest("Distribution values must be between 0 and 100.");
            }
            total += entry.getValue();
        }
        if (total != 100) throw badRequest("Each configured distribution must total 100.");
    }

    private BlueprintVersion insertBlueprintVersion(
            long blueprintId, int version, BlueprintRequest request, String type) {
        String rules = json(request.selectionRules());
        String sections = json(request.sections());
        String optionalBranches = json(optionalBranchSelections(request));
        String hash = sha256(type + "|" + request.durationMinutes() + "|" + sections + "|"
                + rules + "|" + optionalBranches);
        long blueprintVersionId = jdbc.queryForObject("""
                INSERT INTO test_blueprint_versions
                    (blueprint_id, version_number, test_type, duration_minutes,
                     section_rules, selection_rules, content_sha256)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                RETURNING blueprint_version_id
                """, Long.class, blueprintId, version, type, request.durationMinutes(),
                sections, rules, hash);
        for (OptionalBranchSelection selection : optionalBranchSelections(request)) {
            Long branchId = jdbc.query("""
                    SELECT b.optional_section_branch_id
                    FROM assessment_optional_section_groups g
                    JOIN assessment_optional_section_branches b
                      ON b.optional_section_group_id = g.optional_section_group_id
                    WHERE g.optional_section_group_id = ?
                      AND UPPER(BTRIM(b.branch_code)) = ?
                      AND g.status = 'ACTIVE'
                    """, rs -> rs.next() ? rs.getLong(1) : null,
                    selection.optionalSectionGroupId(), normalizeBranchCode(selection.branchCode()));
            if (branchId == null) {
                throw badRequest("The selected optional branch is not configured for this Exam Version.");
            }
            jdbc.update("""
                    INSERT INTO test_blueprint_optional_branches
                        (blueprint_version_id, optional_section_group_id, optional_section_branch_id)
                    VALUES (?, ?, ?)
                    """, blueprintVersionId, selection.optionalSectionGroupId(), branchId);
        }
        return blueprintVersion(blueprintVersionId);
    }

    private BlueprintVersion blueprintVersion(long blueprintVersionId) {
        return jdbc.queryForObject("""
                SELECT b.blueprint_id, v.blueprint_version_id, b.blueprint_code, b.title,
                       e.code AS exam, ev.version_code AS exam_version,
                       v.version_number, v.test_type, v.duration_minutes,
                       v.section_rules, v.selection_rules, v.status,
                       COALESCE((
                           SELECT jsonb_agg(jsonb_build_object(
                               'optionalSectionGroupId', choice.optional_section_group_id,
                               'branchCode', branch.branch_code
                           ) ORDER BY choice.optional_section_group_id)
                           FROM test_blueprint_optional_branches choice
                           JOIN assessment_optional_section_branches branch
                             ON branch.optional_section_branch_id = choice.optional_section_branch_id
                           WHERE choice.blueprint_version_id = v.blueprint_version_id
                       ), '[]'::jsonb) AS optional_branch_selections
                FROM test_blueprints b
                JOIN test_blueprint_versions v ON v.blueprint_id = b.blueprint_id
                JOIN exam_versions ev ON ev.exam_version_id = b.exam_version_id
                JOIN exams e ON e.exam_id = ev.exam_id
                WHERE v.blueprint_version_id = ?
                """, blueprintVersionMapper(), blueprintVersionId);
    }

    private RowMapper<BlueprintVersion> blueprintVersionMapper() {
        return (rs, row) -> {
            List<AssessmentDtos.SectionRule> sections = readJson(
                    rs.getString("section_rules"), new TypeReference<>() {});
            AssessmentDtos.SelectionRules selection = readJson(
                    rs.getString("selection_rules"), AssessmentDtos.SelectionRules.class);
            List<OptionalBranchSelection> optionalBranches = readJson(
                    rs.getString("optional_branch_selections"), new TypeReference<>() {});
            return new BlueprintVersion(
                    rs.getLong("blueprint_id"), rs.getLong("blueprint_version_id"),
                    rs.getString("blueprint_code"), rs.getString("title"),
                    rs.getString("exam"), rs.getString("exam_version"),
                    rs.getInt("version_number"), rs.getString("test_type"),
                    rs.getInt("duration_minutes"),
                    sections.stream().mapToInt(AssessmentDtos.SectionRule::questionCount).sum(),
                    sections, selection, optionalBranches, rs.getString("status"));
        };
    }

    private BlueprintRules blueprintRules(long blueprintVersionId) {
        return jdbc.query("""
                SELECT section_rules, selection_rules
                FROM test_blueprint_versions WHERE blueprint_version_id = ?
                """, rs -> {
            if (!rs.next()) throw notFound("Blueprint Version not found.");
            List<AssessmentDtos.SectionRule> sections = readJson(
                    rs.getString("section_rules"), new TypeReference<>() {});
            AssessmentDtos.SelectionRules selection = readJson(
                    rs.getString("selection_rules"), AssessmentDtos.SelectionRules.class);
            return new BlueprintRules(sections,
                    selection.skillIds() == null ? List.of() : selection.skillIds(),
                    normalizeDistribution(selection.cognitiveLevelDistribution()),
                    normalizeDistribution(selection.difficultyDistribution()),
                    normalizeDistribution(selection.questionTypeDistribution()));
        }, blueprintVersionId);
    }

    private List<String> describeConstraints(BlueprintRules rules) {
        List<String> constraints = new ArrayList<>();
        if (!rules.skillIds().isEmpty()) constraints.add("skillIds=" + rules.skillIds());
        if (!rules.cognitiveDistribution().isEmpty()) constraints.add("cognitiveLevelDistribution");
        if (!rules.difficultyDistribution().isEmpty()) constraints.add("difficultyDistribution");
        if (!rules.typeDistribution().isEmpty()) constraints.add("questionTypeDistribution");
        constraints.add("approvedQuestionVersion");
        constraints.add("publishedMappingSet");
        constraints.add("publishedTaxonomyRelease");
        constraints.add("verifiedRights");
        return constraints;
    }

    private static Map<String, Integer> normalizeDistribution(Map<String, Integer> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, Integer> normalized = new HashMap<>();
        values.forEach((key, value) -> normalized.put(key.toUpperCase(Locale.ROOT), value));
        return normalized;
    }

    private static Map<String, Integer> quotas(Map<String, Integer> percentages, int count) {
        if (percentages.isEmpty()) return Map.of();
        Map<String, Integer> result = new HashMap<>();
        List<Map.Entry<String, Integer>> remainders = new ArrayList<>();
        int allocated = 0;
        for (var entry : percentages.entrySet()) {
            int exact = entry.getValue() * count;
            int base = exact / 100;
            result.put(entry.getKey(), base);
            allocated += base;
            remainders.add(Map.entry(entry.getKey(), exact % 100));
        }
        remainders.sort(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));
        for (int index = 0; allocated < count; index++, allocated++) {
            String key = remainders.get(index % remainders.size()).getKey();
            result.compute(key, (ignored, value) -> value + 1);
        }
        return result;
    }

    private static boolean exceedsTarget(
            String value, Map<String, Integer> target, Map<String, Integer> current) {
        return !target.isEmpty() && target.getOrDefault(normalize(value), 0) <= current.getOrDefault(normalize(value), 0);
    }

    private static int deficit(
            String value, Map<String, Integer> target, Map<String, Integer> current) {
        if (target.isEmpty()) return 0;
        String key = normalize(value);
        return Math.max(0, target.getOrDefault(key, 0) - current.getOrDefault(key, 0));
    }

    private static String normalize(String value) {
        return value == null ? "UNKNOWN" : value.toUpperCase(Locale.ROOT);
    }

    private static void increment(Map<String, Integer> values, String value) {
        values.merge(normalize(value), 1, Integer::sum);
    }

    private long userId(String subject) {
        Long id = jdbc.query("""
                SELECT user_id FROM users
                WHERE LOWER(email) = LOWER(?) OR phone_number = ?
                """, rs -> rs.next() ? rs.getLong(1) : null, subject, subject);
        if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found.");
        return id;
    }

    private SubmitResult submitResult(long attemptId, Map<String, Object> current) {
        return jdbc.query("""
                SELECT COUNT(*) FILTER (WHERE aa.answered_at IS NOT NULL) AS answered,
                       COUNT(ti.test_item_id) AS total
                FROM attempts a
                JOIN test_items ti ON ti.test_version_id = a.test_version_id
                LEFT JOIN attempt_answers aa
                  ON aa.attempt_id = a.attempt_id AND aa.test_item_id = ti.test_item_id
                WHERE a.attempt_id = ?
                """, rs -> {
            rs.next();
            Instant submittedAt = (Instant) current.get("submittedAt");
            return new SubmitResult(attemptId, (String) current.get("status"),
                    submittedAt.equals(Instant.EPOCH) ? null : submittedAt,
                    (boolean) current.get("auto"), rs.getInt("answered"), rs.getInt("total"));
        }, attemptId);
    }

    private List<Map<String, Object>> jsonList(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("Stored assessment JSON is invalid.", exception);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode parseJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Stored Question Group stimulus is invalid.", exception);
        }
    }

    private List<String> jsonStringList(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("Stored answer JSON is invalid.", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Assessment data could not be serialized.", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private record BlueprintRules(
            List<AssessmentDtos.SectionRule> sections,
            List<Long> skillIds,
            Map<String, Integer> cognitiveDistribution,
            Map<String, Integer> difficultyDistribution,
            Map<String, Integer> typeDistribution) {}

    private record OptionalBranchConfig(
            long groupId,
            long branchId,
            String branchCode,
            long sectionId,
            String groupStatus) {}

    private record SelectedQuestion(
            long questionVersionId,
            long sectionId,
            long mappingSetId,
            String cognitiveLevel,
            String difficulty,
            String questionType,
            Long groupVersionId,
            int groupItemOrder,
            int groupItemCount) {}

    private record AttemptLock(long testVersionId, String status, Instant endsAt) {}

    private record ItemContext(long questionVersionId, String responseMode) {}
}
