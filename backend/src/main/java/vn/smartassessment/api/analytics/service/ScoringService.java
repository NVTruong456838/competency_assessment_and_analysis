package vn.smartassessment.api.analytics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.smartassessment.api.analytics.dto.ScoringDtos;
import vn.smartassessment.api.analytics.service.ScoringPolicy.ExpectedAnswers;
import vn.smartassessment.api.analytics.service.ScoringPolicy.NumericExpectedAnswer;

@Service
public class ScoringService {
    private static final String INTERPRETATION =
            "Internal exact-match score only; accuracy is correct questions divided by all questions, "
                    + "including skipped questions. No partial credit is used. Numeric tolerance is "
                    + "applied per answer key only when stored there. Text answers use Unicode NFC, trim "
                    + "outer whitespace, and remain case-sensitive. Mastery equals observed mapped "
                    + "accuracy; confidence is an evidence indicator n/(n+3), not a probability. "
                    + "Mapping weights are normalized across non-excluded links; missing weights count "
                    + "as 1. Error causes are not inferred.";
    private static final String STUDENT_LATEST_RUNS = """
            WITH latest AS (
                SELECT DISTINCT ON (sr.attempt_id) sr.*, ev.exam_id, e.code AS exam_code,
                       e.name AS exam_name, ev.exam_version_id, ev.version_code AS exam_version_code,
                       t.title AS test_title, tr.taxonomy_version
                FROM scoring_runs sr
                JOIN test_versions tv ON tv.test_version_id = sr.test_version_id
                JOIN tests t ON t.test_id = tv.test_id
                JOIN exam_versions ev ON ev.exam_version_id = t.exam_version_id
                JOIN exams e ON e.exam_id = ev.exam_id
                LEFT JOIN taxonomy_releases tr ON tr.taxonomy_release_id = sr.taxonomy_release_id
                WHERE sr.user_id = ?
                ORDER BY sr.attempt_id, sr.computed_at DESC, sr.scoring_run_id DESC
            )
            """;
    private static final String TEACHER_LATEST_RUNS = """
            WITH latest AS (
                SELECT DISTINCT ON (sr.attempt_id) sr.*, ev.exam_id, e.code AS exam_code,
                       e.name AS exam_name, ev.exam_version_id, ev.version_code AS exam_version_code,
                       t.title AS test_title, tr.taxonomy_version
                FROM scoring_runs sr
                JOIN test_versions tv ON tv.test_version_id = sr.test_version_id
                JOIN tests t ON t.test_id = tv.test_id
                JOIN exam_versions ev ON ev.exam_version_id = t.exam_version_id
                JOIN exams e ON e.exam_id = ev.exam_id
                LEFT JOIN taxonomy_releases tr ON tr.taxonomy_release_id = sr.taxonomy_release_id
                WHERE t.created_by = ?
                ORDER BY sr.attempt_id, sr.computed_at DESC, sr.scoring_run_id DESC
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ScoringService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ScoringDtos.ScoringResult scoreAttempt(long attemptId, long userId, UUID requestId) {
        if (requestId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A requestId is required.");
        }
        Attempt attempt = loadAttemptForUpdate(attemptId);
        if (attempt == null || attempt.userId() != userId) throw notFound();
        if (!isSubmitted(attempt.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "An attempt can only be scored after submission.");
        }

        Long existingRun = jdbc.query("""
                SELECT scoring_run_id FROM scoring_runs
                WHERE attempt_id = ? AND scoring_version = ? AND request_id = ?
                """, rs -> rs.next() ? rs.getLong(1) : null,
                attemptId, ScoringPolicy.SCORING_VERSION, requestId);
        if (existingRun != null) return loadResult(existingRun);

        List<Item> items = loadItems(attempt);
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "The Test Version has no questions.");
        }
        List<EvaluatedItem> evaluated = new ArrayList<>(items.size());
        for (Item item : items) evaluated.add(evaluate(item));

        BigDecimal rawScore = sum(evaluated, EvaluatedItem::score);
        BigDecimal maxScore = sum(evaluated, EvaluatedItem::maxScore);
        int total = evaluated.size();
        int answered = (int) evaluated.stream().filter(item -> !item.skipped()).count();
        int correct = (int) evaluated.stream().filter(item -> Boolean.TRUE.equals(item.correct())).count();
        int incorrect = answered - correct;
        int skipped = total - answered;
        BigDecimal accuracy = ratio(correct, total);
        String inputHash = hashInput(attempt, evaluated);
        Map<Long, SectionAggregate> sections = aggregateSections(evaluated);
        Map<Long, WeightedAggregate> skills = new HashMap<>();
        Map<Long, WeightedAggregate> competencies = new HashMap<>();
        Map<String, DimensionAggregate> dimensions = aggregateDimensions(evaluated);
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("policy", INTERPRETATION);
        provenance.put("taxonomyReleaseId", attempt.taxonomyReleaseId());
        provenance.put("questionVersionIds", evaluated.stream().map(item -> item.item().questionVersionId()).toList());
        provenance.put("mappingSetIds", evaluated.stream().map(item -> item.item().mappingSetId())
                .filter(id -> id != null).distinct().sorted().toList());
        List<Map<String, Object>> mappingEvidence = mappingProvenance(evaluated);
        provenance.put("publishedMappingEvidence", mappingEvidence);
        Set<Long> mappedQuestionVersionIds = mappingEvidence.stream()
                .filter(evidence -> evidence.get("weight") instanceof BigDecimal weight
                        && weight.signum() > 0)
                .map(evidence -> ((Number) evidence.get("question_version_id")).longValue())
                .collect(java.util.stream.Collectors.toSet());
        provenance.put("unmappedQuestionVersionIds", evaluated.stream()
                .filter(item -> item.item().mappingSetId() == null)
                .map(item -> item.item().questionVersionId()).distinct().toList());
        provenance.put("ineligibleMappingQuestionVersionIds", evaluated.stream()
                .filter(item -> item.item().mappingSetId() != null)
                .map(item -> item.item().questionVersionId())
                .filter(questionVersionId -> !mappedQuestionVersionIds.contains(questionVersionId))
                .distinct().toList());
        provenance.put("sectionCompetencyEvidence", competencyProvenance(evaluated));
        provenance.put("accuracyDenominator", "all_questions_including_skipped");

        Long runId = jdbc.queryForObject("""
                INSERT INTO scoring_runs
                    (attempt_id, test_version_id, user_id, request_id, scoring_version,
                     analytics_version, input_sha256, taxonomy_release_id, raw_score,
                     max_score, accuracy, total_questions, answered_questions,
                     correct_questions, incorrect_questions, skipped_questions,
                     total_time_seconds, confidence, provenance_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                RETURNING scoring_run_id
                """, Long.class,
                attempt.id(), attempt.testVersionId(), userId, requestId,
                ScoringPolicy.SCORING_VERSION, ScoringPolicy.ANALYTICS_VERSION,
                inputHash, attempt.taxonomyReleaseId(), rawScore, maxScore, accuracy,
                total, answered, correct, incorrect, skipped, attempt.timeSpentSeconds(),
                ScoringPolicy.confidence(total),
                toJson(provenance));

        for (EvaluatedItem result : evaluated) {
            insertQuestionResult(runId, attempt, result);
            if (!result.skipped()) {
                addMappedSkillEvidence(skills, result);
                addCompetencyEvidence(competencies, result);
            }
        }
        insertSectionResults(runId, sections);
        insertSkillResults(runId, skills);
        insertCompetencyResults(runId, competencies);
        insertDimensionResults(runId, dimensions);
        return loadResult(runId);
    }

    @Transactional(readOnly = true)
    public ScoringDtos.ScoringResult studentResult(long attemptId, long userId) {
        Long runId = latestRun(attemptId, userId, null);
        if (runId == null) throw notFound();
        return loadResult(runId);
    }

    @Transactional(readOnly = true)
    public ScoringDtos.ScoringResult teacherResult(long attemptId, long teacherId) {
        Long runId = latestRun(attemptId, null, teacherId);
        if (runId == null) throw notFound();
        return loadResult(runId);
    }

    @Transactional(readOnly = true)
    public ScoringDtos.AnalyticsSummary studentAnalytics(long userId) {
        return studentAnalytics(userId, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public ScoringDtos.AnalyticsSummary studentAnalytics(
            long userId, Long examId, Long examVersionId, Long testVersionId, Long taxonomyReleaseId) {
        return studentAnalytics(userId, examId, examVersionId, testVersionId, taxonomyReleaseId, null, null);
    }

    @Transactional(readOnly = true)
    public ScoringDtos.AnalyticsSummary studentAnalytics(
            long userId,
            Long examId,
            Long examVersionId,
            Long testVersionId,
            Long taxonomyReleaseId,
            String scoringVersion,
            String analyticsVersion) {
        AnalyticsSelection selection = studentSelection(
                userId, examId, examVersionId, testVersionId, taxonomyReleaseId,
                scoringVersion, analyticsVersion);
        if (selection.cohort() == null) {
            return new ScoringDtos.AnalyticsSummary(
                    0, BigDecimal.ZERO, 0, 0, 0, null, selection.examId(), selection.exams(),
                    null, List.of(), null, List.of(), List.of(), List.of());
        }
        Object[] parameters = scopeParameters(userId, selection.cohort());
        SummaryCounts counts = summaryCounts(STUDENT_LATEST_RUNS, parameters, selection.cohort(), true);
        Improvement improvement = improvement(STUDENT_LATEST_RUNS, parameters, selection.cohort());
        List<ScoringDtos.AttemptTrendPoint> recentAttempts =
                recentAttempts(STUDENT_LATEST_RUNS, parameters, selection.cohort());
        return new ScoringDtos.AnalyticsSummary(
                counts.attempts(), counts.accuracy(), counts.correct(), counts.incorrect(),
                counts.skipped(), counts.averageTime(), selection.examId(), selection.exams(),
                selection.cohort(), selection.cohorts(), improvement.change(), recentAttempts,
                studentSkills(userId, selection.cohort()),
                studentCompetencies(userId, selection.cohort()));
    }

    @Transactional(readOnly = true)
    public ScoringDtos.TeacherAnalyticsSummary teacherAnalytics(long teacherId) {
        return teacherAnalytics(teacherId, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public ScoringDtos.TeacherAnalyticsSummary teacherAnalytics(
            long teacherId, Long examId, Long examVersionId, Long testVersionId, Long taxonomyReleaseId) {
        return teacherAnalytics(
                teacherId, examId, examVersionId, testVersionId, taxonomyReleaseId, null, null);
    }

    @Transactional(readOnly = true)
    public ScoringDtos.TeacherAnalyticsSummary teacherAnalytics(
            long teacherId,
            Long examId,
            Long examVersionId,
            Long testVersionId,
            Long taxonomyReleaseId,
            String scoringVersion,
            String analyticsVersion) {
        AnalyticsSelection selection = teacherSelection(
                teacherId, examId, examVersionId, testVersionId, taxonomyReleaseId,
                scoringVersion, analyticsVersion);
        if (selection.cohort() == null) {
            return new ScoringDtos.TeacherAnalyticsSummary(
                    0, BigDecimal.ZERO, 0, 0, 0, selection.examId(), selection.exams(),
                    null, List.of(), null, List.of(), List.of(), List.of());
        }
        Object[] parameters = scopeParameters(teacherId, selection.cohort());
        SummaryCounts counts = summaryCounts(TEACHER_LATEST_RUNS, parameters, selection.cohort(), false);
        Improvement improvement = improvement(TEACHER_LATEST_RUNS, parameters, selection.cohort());
        return new ScoringDtos.TeacherAnalyticsSummary(
                counts.attempts(), counts.accuracy(), counts.correct(), counts.incorrect(),
                counts.skipped(), selection.examId(), selection.exams(), selection.cohort(),
                selection.cohorts(), improvement.change(),
                recentAttempts(TEACHER_LATEST_RUNS, parameters, selection.cohort()),
                teacherSkills(teacherId, selection.cohort()),
                teacherCompetencies(teacherId, selection.cohort()));
    }

    private AnalyticsSelection studentSelection(
            long userId,
            Long examId,
            Long examVersionId,
            Long testVersionId,
            Long taxonomyReleaseId,
            String scoringVersion,
            String analyticsVersion) {
        return analyticsSelection(
                STUDENT_LATEST_RUNS, userId, examId, examVersionId, testVersionId, taxonomyReleaseId,
                scoringVersion, analyticsVersion);
    }

    private AnalyticsSelection teacherSelection(
            long teacherId,
            Long examId,
            Long examVersionId,
            Long testVersionId,
            Long taxonomyReleaseId,
            String scoringVersion,
            String analyticsVersion) {
        return analyticsSelection(
                TEACHER_LATEST_RUNS, teacherId, examId, examVersionId, testVersionId, taxonomyReleaseId,
                scoringVersion, analyticsVersion);
    }

    private AnalyticsSelection analyticsSelection(
            String latestRunsCte,
            long principalId,
            Long examId,
            Long examVersionId,
            Long testVersionId,
            Long taxonomyReleaseId,
            String scoringVersion,
            String analyticsVersion) {
        List<ScoringDtos.AnalyticsExam> exams = jdbc.query(latestRunsCte + """
                SELECT exam_id, exam_code, exam_name, MAX(computed_at) AS latest_scored_at
                FROM latest
                GROUP BY exam_id, exam_code, exam_name
                ORDER BY latest_scored_at DESC, exam_code
                """, (rs, row) -> new ScoringDtos.AnalyticsExam(
                rs.getLong("exam_id"), rs.getString("exam_code"), rs.getString("exam_name"),
                rs.getTimestamp("latest_scored_at").toInstant()), principalId);
        if (exams.isEmpty()) return new AnalyticsSelection(null, exams, null, List.of());

        List<ScoringDtos.AnalyticsCohort> candidates = jdbc.query(latestRunsCte + """
                SELECT exam_id, exam_code, exam_name, exam_version_id, exam_version_code,
                       test_version_id, test_title, taxonomy_release_id, taxonomy_version,
                       scoring_version, analytics_version, MAX(computed_at) AS latest_scored_at
                FROM latest
                GROUP BY exam_id, exam_code, exam_name, exam_version_id, exam_version_code,
                         test_version_id, test_title, taxonomy_release_id, taxonomy_version,
                         scoring_version, analytics_version
                ORDER BY latest_scored_at DESC, test_version_id DESC,
                         scoring_version DESC, analytics_version DESC
                """, (rs, row) -> new ScoringDtos.AnalyticsCohort(
                rs.getLong("exam_id"), rs.getString("exam_code"), rs.getString("exam_name"),
                rs.getLong("exam_version_id"), rs.getString("exam_version_code"),
                rs.getLong("test_version_id"), rs.getString("test_title"),
                nullableLong(rs, "taxonomy_release_id"), rs.getString("taxonomy_version"),
                rs.getString("scoring_version"), rs.getString("analytics_version"),
                rs.getTimestamp("latest_scored_at").toInstant()), principalId);

        List<ScoringDtos.AnalyticsCohort> matching = candidates.stream()
                .filter(cohort -> examId == null || cohort.examId() == examId)
                .filter(cohort -> examVersionId == null || cohort.examVersionId() == examVersionId)
                .filter(cohort -> testVersionId == null || cohort.testVersionId() == testVersionId)
                .filter(cohort -> taxonomyReleaseId == null
                        || taxonomyReleaseId.equals(cohort.taxonomyReleaseId()))
                .filter(cohort -> scoringVersion == null || cohort.scoringVersion().equals(scoringVersion))
                .filter(cohort -> analyticsVersion == null || cohort.analyticsVersion().equals(analyticsVersion))
                .toList();
        if (matching.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analytics cohort was not found.");
        }
        ScoringDtos.AnalyticsCohort selected = matching.get(0);
        List<ScoringDtos.AnalyticsCohort> availableForExam = candidates.stream()
                .filter(cohort -> cohort.examId() == selected.examId())
                .toList();
        return new AnalyticsSelection(selected.examId(), exams, selected, availableForExam);
    }

    private SummaryCounts summaryCounts(
            String latestRunsCte,
            Object[] parameters,
            ScoringDtos.AnalyticsCohort cohort,
            boolean includeTime) {
        String averageTime = includeTime ? "AVG(l.total_time_seconds)" : "NULL::numeric";
        return jdbc.queryForObject(latestRunsCte + """
                SELECT COUNT(*)::integer AS attempts,
                       COALESCE(AVG(l.accuracy), 0) AS accuracy,
                       COALESCE(SUM(l.correct_questions), 0)::integer AS correct,
                       COALESCE(SUM(l.incorrect_questions), 0)::integer AS incorrect,
                       COALESCE(SUM(l.skipped_questions), 0)::integer AS skipped,
                       """ + averageTime + " AS avg_time FROM latest l WHERE " + scopePredicate("l"),
                summaryMapper(), parameters);
    }

    private Improvement improvement(
            String latestRunsCte, Object[] parameters, ScoringDtos.AnalyticsCohort cohort) {
        return jdbc.queryForObject(latestRunsCte + """
                SELECT MAX(accuracy) FILTER (WHERE position = attempt_count) AS latest_accuracy,
                       MAX(accuracy) FILTER (WHERE position = 1) AS earliest_accuracy,
                       MAX(attempt_count)::integer AS attempts
                FROM (
                    SELECT l.accuracy,
                           ROW_NUMBER() OVER (ORDER BY l.computed_at, l.attempt_id) AS position,
                           COUNT(*) OVER () AS attempt_count
                    FROM latest l
                    WHERE """ + scopePredicate("l") + """
                ) ordered_attempts
                """, (rs, row) -> {
            int attempts = rs.getInt("attempts");
            BigDecimal latest = rs.getBigDecimal("latest_accuracy");
            BigDecimal earliest = rs.getBigDecimal("earliest_accuracy");
            return new Improvement(attempts, attempts < 2 || latest == null || earliest == null
                    ? null : latest.subtract(earliest));
        }, parameters);
    }

    private List<ScoringDtos.AttemptTrendPoint> recentAttempts(
            String latestRunsCte, Object[] parameters, ScoringDtos.AnalyticsCohort cohort) {
        return jdbc.query(latestRunsCte + """
                SELECT attempt_id, computed_at, accuracy, raw_score, max_score,
                       exam_id, exam_code, exam_version_id, exam_version_code, test_version_id,
                       taxonomy_release_id, taxonomy_version, scoring_version, analytics_version
                FROM (
                    SELECT l.*
                    FROM latest l
                    WHERE """ + scopePredicate("l") + """
                    ORDER BY l.computed_at DESC, l.attempt_id DESC
                    LIMIT 10
                ) scoped
                ORDER BY computed_at, attempt_id
                """, (rs, row) -> new ScoringDtos.AttemptTrendPoint(
                rs.getLong("attempt_id"), rs.getTimestamp("computed_at").toInstant(),
                rs.getBigDecimal("accuracy"), rs.getBigDecimal("raw_score"),
                rs.getBigDecimal("max_score"), rs.getLong("exam_id"), rs.getString("exam_code"),
                rs.getLong("exam_version_id"), rs.getString("exam_version_code"),
                rs.getLong("test_version_id"), nullableLong(rs, "taxonomy_release_id"),
                rs.getString("taxonomy_version"), rs.getString("scoring_version"),
                rs.getString("analytics_version")), parameters);
    }

    private static String scopePredicate(String alias) {
        return " " + alias + ".exam_id = ? AND " + alias + ".exam_version_id = ? AND "
                + alias + ".test_version_id = ? AND " + alias + ".taxonomy_release_id IS NOT DISTINCT FROM ? AND "
                + alias + ".scoring_version = ? AND " + alias + ".analytics_version = ?";
    }

    private static Object[] scopeParameters(long principalId, ScoringDtos.AnalyticsCohort cohort) {
        return new Object[] {
            principalId, cohort.examId(), cohort.examVersionId(), cohort.testVersionId(),
            cohort.taxonomyReleaseId(), cohort.scoringVersion(), cohort.analyticsVersion()
        };
    }

    private Attempt loadAttemptForUpdate(long attemptId) {
        return jdbc.query("""
                SELECT a.attempt_id, a.user_id, a.test_version_id, a.status,
                       a.time_spent_seconds, tv.taxonomy_release_id
                FROM attempts a
                JOIN test_versions tv ON tv.test_version_id = a.test_version_id
                WHERE a.attempt_id = ?
                FOR UPDATE OF a
                """, rs -> rs.next() ? new Attempt(
                rs.getLong("attempt_id"), rs.getLong("user_id"), rs.getLong("test_version_id"),
                rs.getString("status"), nullableInt(rs, "time_spent_seconds"),
                nullableLong(rs, "taxonomy_release_id")) : null, attemptId);
    }

    private List<Item> loadItems(Attempt attempt) {
        return jdbc.query("""
                SELECT ti.test_item_id, ti.test_section_id, ti.item_order, ti.points,
                       ti.mapping_set_id, ti.taxonomy_release_id, ts.section_id,
                       s.section_name, qv.question_version_id, qv.response_mode,
                       qv.cognitive_level, qv.estimated_difficulty, aa.attempt_answer_id,
                       aa.answer_text, aa.numeric_answer, aa.answered_at, aa.time_spent_seconds,
                       COALESCE((SELECT array_agg(qo.option_key ORDER BY qo.option_key)
                           FROM attempt_selected_options aso
                           JOIN question_options qo ON qo.option_id = aso.option_id
                           WHERE aso.attempt_answer_id = aa.attempt_answer_id), ARRAY[]::varchar[]) AS selected_keys,
                       COALESCE((SELECT array_agg(qo.option_key ORDER BY qo.option_key)
                           FROM question_options qo
                           WHERE qo.question_version_id = qv.question_version_id AND qo.is_correct),
                           ARRAY[]::varchar[]) AS correct_keys,
                       COALESCE((SELECT array_agg(qa.answer_value ORDER BY qa.answer_value)
                           FROM question_answers qa
                           WHERE qa.question_version_id = qv.question_version_id
                             AND qa.is_correct AND qa.answer_type IN ('TEXT', 'BOOLEAN')),
                           ARRAY[]::text[]) AS correct_text_values,
                       COALESCE((SELECT array_agg(
                               qa.numeric_value::text || ':' || COALESCE(qa.tolerance, 0)::text
                               ORDER BY qa.numeric_value)
                           FROM question_answers qa
                           WHERE qa.question_version_id = qv.question_version_id
                             AND qa.is_correct AND qa.answer_type = 'NUMERIC'),
                           ARRAY[]::text[]) AS correct_numeric_values
                FROM test_items ti
                JOIN test_sections ts
                  ON ts.test_section_id = ti.test_section_id
                 AND ts.test_version_id = ti.test_version_id
                JOIN sections s ON s.section_id = ts.section_id
                JOIN question_versions qv ON qv.question_version_id = ti.question_version_id
                LEFT JOIN attempt_answers aa
                  ON aa.attempt_id = ? AND aa.test_item_id = ti.test_item_id
                WHERE ti.test_version_id = ?
                ORDER BY ti.item_order
                """, (rs, row) -> new Item(
                rs.getLong("test_item_id"), rs.getLong("test_section_id"),
                rs.getInt("item_order"), rs.getBigDecimal("points"),
                nullableLong(rs, "mapping_set_id"), nullableLong(rs, "taxonomy_release_id"),
                rs.getLong("section_id"), rs.getString("section_name"),
                rs.getLong("question_version_id"), rs.getString("response_mode"),
                rs.getString("cognitive_level"), rs.getBigDecimal("estimated_difficulty"),
                nullableLong(rs, "attempt_answer_id"), rs.getString("answer_text"),
                rs.getBigDecimal("numeric_answer"), rs.getTimestamp("answered_at") != null,
                nullableInt(rs, "time_spent_seconds"), stringArray(rs.getArray("selected_keys")),
                stringArray(rs.getArray("correct_keys")),
                stringArray(rs.getArray("correct_text_values")),
                numericAnswerArray(rs.getArray("correct_numeric_values"))),
                attempt.id(), attempt.testVersionId());
    }

    private EvaluatedItem evaluate(Item item) {
        if (!item.answered()) return new EvaluatedItem(item, null, true, BigDecimal.ZERO);
        Set<String> selected = new HashSet<>(item.selectedKeys());
        ExpectedAnswers expected = new ExpectedAnswers(
                Set.copyOf(item.correctKeys()), item.correctTextValues(),
                item.correctNumericValues());
        if (expected.optionKeys().isEmpty() && expected.textValues().isEmpty()
                && expected.numericValues().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Question Version " + item.questionVersionId() + " has no usable answer key.");
        }
        boolean correct = ScoringPolicy.isCorrect(
                item.responseMode(), selected, item.answerText(), item.numericAnswer(), expected);
        return new EvaluatedItem(item, correct, false, correct ? item.points() : BigDecimal.ZERO);
    }

    private Map<Long, SectionAggregate> aggregateSections(List<EvaluatedItem> items) {
        Map<Long, SectionAggregate> result = new LinkedHashMap<>();
        for (EvaluatedItem evaluated : items) {
            Item item = evaluated.item();
            SectionAggregate aggregate = result.computeIfAbsent(item.testSectionId(),
                    ignored -> new SectionAggregate(item.sectionName()));
            aggregate.add(evaluated);
        }
        return result;
    }

    private Map<String, DimensionAggregate> aggregateDimensions(List<EvaluatedItem> items) {
        Map<String, DimensionAggregate> result = new LinkedHashMap<>();
        for (EvaluatedItem evaluated : items) {
            Item item = evaluated.item();
            if (item.cognitiveLevel() != null) {
                result.computeIfAbsent("COGNITIVE_LEVEL:" + item.cognitiveLevel(),
                        ignored -> new DimensionAggregate("COGNITIVE_LEVEL", item.cognitiveLevel()))
                        .add(evaluated);
            }
            if (item.estimatedDifficulty() != null) {
                String value = item.estimatedDifficulty().stripTrailingZeros().toPlainString();
                result.computeIfAbsent("ESTIMATED_DIFFICULTY:" + value,
                        ignored -> new DimensionAggregate("ESTIMATED_DIFFICULTY", value))
                        .add(evaluated);
            }
        }
        return result;
    }

    private void addMappedSkillEvidence(Map<Long, WeightedAggregate> aggregates, EvaluatedItem result) {
        Item item = result.item();
        if (item.mappingSetId() == null || item.taxonomyReleaseId() == null) return;
        List<WeightedTarget> targets = jdbc.query("""
                SELECT mi.skill_id,
                       COALESCE(mi.weight, 1)::numeric AS weight
                FROM question_mapping_set_items mi
                JOIN question_mapping_sets ms
                  ON ms.mapping_set_id = mi.mapping_set_id
                 AND ms.taxonomy_release_id = mi.taxonomy_release_id
                JOIN taxonomy_releases tr
                  ON tr.taxonomy_release_id = mi.taxonomy_release_id
                WHERE mi.mapping_set_id = ? AND mi.taxonomy_release_id = ?
                  AND mi.role <> 'EXCLUDED'
                  AND ms.status IN ('PUBLISHED', 'RETIRED')
                  AND tr.status IN ('PUBLISHED', 'RETIRED', 'SUPERSEDED')
                """, (rs, row) -> new WeightedTarget(rs.getLong("skill_id"), rs.getBigDecimal("weight")),
                item.mappingSetId(), item.taxonomyReleaseId());
        addWeightedEvidence(aggregates, targets, result);
    }

    private void addCompetencyEvidence(Map<Long, WeightedAggregate> aggregates, EvaluatedItem result) {
        List<WeightedTarget> targets = jdbc.query("""
                SELECT sc.competency_id, COALESCE(sc.weight, 1)::numeric AS weight
                FROM section_competencies sc
                WHERE sc.section_id = ?
                """, (rs, row) -> new WeightedTarget(
                rs.getLong("competency_id"), rs.getBigDecimal("weight")),
                result.item().sectionId());
        addWeightedEvidence(aggregates, targets, result);
    }

    private static void addWeightedEvidence(
            Map<Long, WeightedAggregate> aggregates,
            List<WeightedTarget> targets,
            EvaluatedItem result) {
        BigDecimal totalWeight = targets.stream().map(WeightedTarget::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.signum() == 0) return;
        for (WeightedTarget target : targets) {
            if (target.weight().signum() <= 0) continue;
            WeightedAggregate aggregate = aggregates.computeIfAbsent(
                    target.id(), ignored -> new WeightedAggregate());
            BigDecimal normalizedWeight = target.weight().divide(totalWeight, 8, RoundingMode.HALF_UP);
            aggregate.add(Boolean.TRUE.equals(result.correct()), normalizedWeight, result.item().timeSpentSeconds());
        }
    }

    private void insertQuestionResult(long runId, Attempt attempt, EvaluatedItem result) {
        Item item = result.item();
        jdbc.update("""
                INSERT INTO scoring_question_results
                    (scoring_run_id, attempt_id, test_version_id, test_item_id,
                     test_section_id, question_version_id, attempt_answer_id,
                     is_correct, is_skipped, score, max_score, time_spent_seconds,
                     cognitive_level, estimated_difficulty)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, attempt.id(), attempt.testVersionId(), item.testItemId(),
                item.testSectionId(), item.questionVersionId(), item.attemptAnswerId(),
                result.correct(), result.skipped(), result.score(), item.points(),
                item.timeSpentSeconds(), item.cognitiveLevel(), item.estimatedDifficulty());
    }

    private void insertSectionResults(long runId, Map<Long, SectionAggregate> sections) {
        sections.forEach((sectionId, aggregate) -> jdbc.update("""
                INSERT INTO scoring_section_results
                    (scoring_run_id, test_section_id, raw_score, max_score, accuracy,
                     total_questions, answered_questions, correct_questions,
                     incorrect_questions, skipped_questions, time_spent_seconds)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, sectionId, aggregate.score, aggregate.maxScore,
                ratio(aggregate.correct, aggregate.total), aggregate.total,
                aggregate.answered, aggregate.correct, aggregate.answered - aggregate.correct,
                aggregate.total - aggregate.answered,
                aggregate.timeCount == 0 ? null : aggregate.timeSeconds));
    }

    private void insertSkillResults(long runId, Map<Long, WeightedAggregate> skills) {
        skills.forEach((skillId, aggregate) -> {
            SkillInfo skill = jdbc.query("""
                    SELECT skill_name FROM skills WHERE skill_id = ?
                    """, rs -> rs.next() ? new SkillInfo(rs.getString(1)) : null, skillId);
            if (skill != null && aggregate.evidence > 0 && aggregate.weight.signum() > 0) {
                jdbc.update("""
                        INSERT INTO scoring_skill_results
                            (scoring_run_id, skill_id, accuracy, mastery_score,
                             evidence_count, confidence, avg_time_seconds)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, runId, skillId, aggregate.accuracy(), aggregate.accuracy(),
                        aggregate.evidence, ScoringPolicy.confidence(aggregate.evidence),
                        aggregate.timeCount == 0 ? null
                                : BigDecimal.valueOf(aggregate.timeSeconds)
                                        .divide(BigDecimal.valueOf(aggregate.timeCount), 4, RoundingMode.HALF_UP));
            }
        });
    }

    private void insertCompetencyResults(long runId, Map<Long, WeightedAggregate> competencies) {
        competencies.forEach((competencyId, aggregate) -> {
            if (aggregate.evidence > 0 && aggregate.weight.signum() > 0) {
                jdbc.update("""
                        INSERT INTO scoring_competency_results
                            (scoring_run_id, competency_id, accuracy, mastery_score,
                             evidence_count, confidence)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, runId, competencyId, aggregate.accuracy(), aggregate.accuracy(),
                        aggregate.evidence, ScoringPolicy.confidence(aggregate.evidence));
            }
        });
    }

    private void insertDimensionResults(long runId, Map<String, DimensionAggregate> dimensions) {
        dimensions.values().forEach(aggregate -> jdbc.update("""
                INSERT INTO scoring_dimension_results
                    (scoring_run_id, dimension_type, dimension_value, total_questions,
                     answered_questions, correct_questions, accuracy)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, runId, aggregate.type, aggregate.value, aggregate.total,
                aggregate.answered, aggregate.correct, ratio(aggregate.correct, aggregate.total)));
    }

    private Long latestRun(long attemptId, Long studentId, Long teacherId) {
        if (studentId != null) {
            return jdbc.query("""
                    SELECT scoring_run_id FROM scoring_runs
                    WHERE attempt_id = ? AND user_id = ?
                    ORDER BY computed_at DESC, scoring_run_id DESC LIMIT 1
                    """, rs -> rs.next() ? rs.getLong(1) : null, attemptId, studentId);
        }
        return jdbc.query("""
                SELECT sr.scoring_run_id
                FROM scoring_runs sr
                JOIN test_versions tv ON tv.test_version_id = sr.test_version_id
                JOIN tests t ON t.test_id = tv.test_id
                WHERE sr.attempt_id = ? AND t.created_by = ?
                ORDER BY sr.computed_at DESC, sr.scoring_run_id DESC LIMIT 1
                """, rs -> rs.next() ? rs.getLong(1) : null, attemptId, teacherId);
    }

    private ScoringDtos.ScoringResult loadResult(long runId) {
        ScoringDtos.RunSummary run = jdbc.query("""
                SELECT sr.scoring_run_id, sr.attempt_id, ev.exam_id, e.code AS exam_code,
                       ev.exam_version_id, ev.version_code AS exam_version_code,
                       sr.test_version_id, sr.taxonomy_release_id, tr.taxonomy_version,
                       sr.input_sha256, sr.provenance_json::text AS provenance,
                       sr.scoring_version, sr.analytics_version,
                       sr.raw_score, sr.max_score, sr.accuracy, sr.total_questions,
                       sr.answered_questions, sr.correct_questions, sr.incorrect_questions,
                       sr.skipped_questions, sr.total_time_seconds, sr.confidence, sr.computed_at
                FROM scoring_runs sr
                JOIN test_versions tv ON tv.test_version_id = sr.test_version_id
                JOIN tests t ON t.test_id = tv.test_id
                JOIN exam_versions ev ON ev.exam_version_id = t.exam_version_id
                JOIN exams e ON e.exam_id = ev.exam_id
                LEFT JOIN taxonomy_releases tr ON tr.taxonomy_release_id = sr.taxonomy_release_id
                WHERE sr.scoring_run_id = ?
                """, rs -> rs.next() ? new ScoringDtos.RunSummary(
                rs.getLong("scoring_run_id"), rs.getLong("attempt_id"),
                rs.getLong("exam_id"), rs.getString("exam_code"),
                rs.getLong("exam_version_id"), rs.getString("exam_version_code"),
                rs.getLong("test_version_id"), nullableLong(rs, "taxonomy_release_id"),
                rs.getString("taxonomy_version"), rs.getString("input_sha256"),
                readJsonObject(rs.getString("provenance")),
                rs.getString("scoring_version"), rs.getString("analytics_version"),
                rs.getBigDecimal("raw_score"), rs.getBigDecimal("max_score"),
                rs.getBigDecimal("accuracy"), rs.getInt("total_questions"),
                rs.getInt("answered_questions"), rs.getInt("correct_questions"),
                rs.getInt("incorrect_questions"), rs.getInt("skipped_questions"),
                nullableInt(rs, "total_time_seconds"), rs.getBigDecimal("confidence"),
                rs.getTimestamp("computed_at").toInstant()) : null, runId);
        if (run == null) throw notFound();
        List<ScoringDtos.QuestionResult> questions = jdbc.query("""
                SELECT qr.test_item_id, ti.item_order, s.section_name, qr.is_correct,
                       qr.is_skipped, qr.score, qr.max_score, qr.time_spent_seconds
                FROM scoring_question_results qr
                JOIN test_items ti ON ti.test_item_id = qr.test_item_id
                JOIN test_sections ts ON ts.test_section_id = qr.test_section_id
                JOIN sections s ON s.section_id = ts.section_id
                WHERE qr.scoring_run_id = ?
                ORDER BY ti.item_order
                """, (rs, row) -> new ScoringDtos.QuestionResult(
                rs.getLong("test_item_id"), rs.getInt("item_order"), rs.getString("section_name"),
                (Boolean) rs.getObject("is_correct"), rs.getBoolean("is_skipped"),
                rs.getBigDecimal("score"), rs.getBigDecimal("max_score"),
                nullableInt(rs, "time_spent_seconds")), runId);
        List<ScoringDtos.SectionResult> sections = jdbc.query("""
                SELECT s.section_name, sr.raw_score, sr.max_score, sr.accuracy,
                       sr.total_questions, sr.answered_questions, sr.correct_questions,
                       sr.incorrect_questions, sr.skipped_questions, sr.time_spent_seconds
                FROM scoring_section_results sr
                JOIN test_sections ts ON ts.test_section_id = sr.test_section_id
                JOIN sections s ON s.section_id = ts.section_id
                WHERE sr.scoring_run_id = ?
                ORDER BY ts.section_order
                """, (rs, row) -> new ScoringDtos.SectionResult(
                rs.getString("section_name"), rs.getBigDecimal("raw_score"),
                rs.getBigDecimal("max_score"), rs.getBigDecimal("accuracy"),
                rs.getInt("total_questions"), rs.getInt("answered_questions"),
                rs.getInt("correct_questions"), rs.getInt("incorrect_questions"),
                rs.getInt("skipped_questions"), nullableInt(rs, "time_spent_seconds")), runId);
        List<ScoringDtos.SkillResult> skills = jdbc.query("""
                SELECT sr.skill_id, s.skill_name, sr.accuracy, sr.mastery_score,
                       sr.evidence_count, sr.confidence, sr.avg_time_seconds
                FROM scoring_skill_results sr JOIN skills s ON s.skill_id = sr.skill_id
                WHERE sr.scoring_run_id = ? ORDER BY s.skill_name
                """, (rs, row) -> skillResult(
                rs.getLong("skill_id"), rs.getString("skill_name"), rs.getBigDecimal("accuracy"),
                rs.getBigDecimal("mastery_score"), rs.getInt("evidence_count"),
                rs.getBigDecimal("confidence"), rs.getBigDecimal("avg_time_seconds")), runId);
        List<ScoringDtos.CompetencyResult> competencies = jdbc.query("""
                SELECT cr.competency_id, c.competency_name, cr.accuracy, cr.mastery_score,
                       cr.evidence_count, cr.confidence
                FROM scoring_competency_results cr
                JOIN competencies c ON c.competency_id = cr.competency_id
                WHERE cr.scoring_run_id = ? ORDER BY c.competency_name
                """, (rs, row) -> competencyResult(
                rs.getLong("competency_id"), rs.getString("competency_name"),
                rs.getBigDecimal("accuracy"), rs.getBigDecimal("mastery_score"),
                rs.getInt("evidence_count"), rs.getBigDecimal("confidence")), runId);
        List<ScoringDtos.DimensionResult> dimensions = jdbc.query("""
                SELECT dimension_type, dimension_value, total_questions, answered_questions,
                       correct_questions, accuracy
                FROM scoring_dimension_results
                WHERE scoring_run_id = ?
                ORDER BY dimension_type, dimension_value
                """, (rs, row) -> new ScoringDtos.DimensionResult(
                rs.getString("dimension_type"), rs.getString("dimension_value"),
                rs.getInt("total_questions"), rs.getInt("answered_questions"),
                rs.getInt("correct_questions"), rs.getBigDecimal("accuracy")), runId);
        return new ScoringDtos.ScoringResult(run, sections, questions, skills,
                competencies, dimensions, INTERPRETATION);
    }

    private List<ScoringDtos.SkillResult> studentSkills(
            long userId, ScoringDtos.AnalyticsCohort cohort) {
        return skillAnalytics(STUDENT_LATEST_RUNS, userId, cohort);
    }

    private List<ScoringDtos.CompetencyResult> studentCompetencies(
            long userId, ScoringDtos.AnalyticsCohort cohort) {
        return competencyAnalytics(STUDENT_LATEST_RUNS, userId, cohort);
    }

    private List<ScoringDtos.SkillResult> teacherSkills(
            long teacherId, ScoringDtos.AnalyticsCohort cohort) {
        return skillAnalytics(TEACHER_LATEST_RUNS, teacherId, cohort);
    }

    private List<ScoringDtos.CompetencyResult> teacherCompetencies(
            long teacherId, ScoringDtos.AnalyticsCohort cohort) {
        return competencyAnalytics(TEACHER_LATEST_RUNS, teacherId, cohort);
    }

    private List<ScoringDtos.SkillResult> skillAnalytics(
            String latestRunsCte, long principalId, ScoringDtos.AnalyticsCohort cohort) {
        return jdbc.query(latestRunsCte + """
                , scoped AS (
                    SELECT l.scoring_run_id FROM latest l WHERE """ + scopePredicate("l") + """
                ), aggregated AS (
                    SELECT sr.skill_id, SUM(sr.evidence_count)::integer evidence,
                           SUM(sr.accuracy * sr.evidence_count) / NULLIF(SUM(sr.evidence_count), 0) accuracy,
                           AVG(sr.avg_time_seconds) avg_time
                    FROM scoring_skill_results sr JOIN scoped sc USING (scoring_run_id)
                    GROUP BY sr.skill_id
                )
                SELECT a.skill_id, s.skill_name, a.accuracy, a.evidence, a.avg_time
                FROM aggregated a JOIN skills s USING (skill_id)
                ORDER BY a.accuracy, a.evidence DESC, s.skill_name
                """, (rs, row) -> {
            int evidence = rs.getInt("evidence");
            BigDecimal accuracy = rs.getBigDecimal("accuracy");
            return skillResult(rs.getLong("skill_id"), rs.getString("skill_name"), accuracy,
                    accuracy, evidence, ScoringPolicy.confidence(evidence), rs.getBigDecimal("avg_time"));
        }, scopeParameters(principalId, cohort));
    }

    private List<ScoringDtos.CompetencyResult> competencyAnalytics(
            String latestRunsCte, long principalId, ScoringDtos.AnalyticsCohort cohort) {
        return jdbc.query(latestRunsCte + """
                , scoped AS (
                    SELECT l.scoring_run_id FROM latest l WHERE """ + scopePredicate("l") + """
                ), aggregated AS (
                    SELECT cr.competency_id, SUM(cr.evidence_count)::integer evidence,
                           SUM(cr.accuracy * cr.evidence_count) / NULLIF(SUM(cr.evidence_count), 0) accuracy
                    FROM scoring_competency_results cr JOIN scoped sc USING (scoring_run_id)
                    GROUP BY cr.competency_id
                )
                SELECT a.competency_id, c.competency_name, a.accuracy, a.evidence
                FROM aggregated a JOIN competencies c USING (competency_id)
                ORDER BY a.accuracy, a.evidence DESC, c.competency_name
                """, (rs, row) -> {
            int evidence = rs.getInt("evidence");
            BigDecimal accuracy = rs.getBigDecimal("accuracy");
            return competencyResult(rs.getLong("competency_id"), rs.getString("competency_name"),
                    accuracy, accuracy, evidence, ScoringPolicy.confidence(evidence));
        }, scopeParameters(principalId, cohort));
    }

    private static ScoringDtos.SkillResult skillResult(
            long id, String name, BigDecimal accuracy, BigDecimal mastery,
            int evidence, BigDecimal confidence, BigDecimal averageTime) {
        return new ScoringDtos.SkillResult(id, name, accuracy, mastery, evidence,
                confidence, averageTime, ScoringPolicy.hasSufficientEvidence(evidence));
    }

    private static ScoringDtos.CompetencyResult competencyResult(
            long id, String name, BigDecimal accuracy, BigDecimal mastery,
            int evidence, BigDecimal confidence) {
        return new ScoringDtos.CompetencyResult(id, name, accuracy, mastery, evidence,
                confidence, ScoringPolicy.hasSufficientEvidence(evidence));
    }

    private static org.springframework.jdbc.core.RowMapper<SummaryCounts> summaryMapper() {
        return (rs, row) -> new SummaryCounts(
                rs.getInt("attempts"), rs.getBigDecimal("accuracy"),
                rs.getInt("correct"), rs.getInt("incorrect"), rs.getInt("skipped"),
                rs.getBigDecimal("avg_time"));
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<EvaluatedItem> items, java.util.function.Function<EvaluatedItem, BigDecimal> value) {
        return items.stream().map(value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String hashInput(Attempt attempt, List<EvaluatedItem> items) {
        StringBuilder value = new StringBuilder()
                .append(attempt.id()).append('|').append(attempt.status()).append('|')
                .append(attempt.testVersionId()).append('|');
        for (EvaluatedItem evaluated : items) {
            Item item = evaluated.item();
            value.append(item.testItemId()).append(':').append(item.questionVersionId()).append(':')
                    .append(item.answerText()).append(':').append(item.numericAnswer()).append(':')
                    .append(item.selectedKeys().stream().sorted().toList()).append(':')
                    .append(item.answered()).append(':').append(item.timeSpentSeconds()).append(':')
                    .append(item.mappingSetId()).append(':').append(item.taxonomyReleaseId()).append(';');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize scoring provenance.", exception);
        }
    }

    private Map<String, Object> readJsonObject(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not read scoring provenance.", exception);
        }
    }

    private List<Map<String, Object>> mappingProvenance(List<EvaluatedItem> evaluated) {
        List<Long> mappingIds = evaluated.stream().map(item -> item.item().mappingSetId())
                .filter(id -> id != null).distinct().sorted().toList();
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (Long mappingId : mappingIds) {
            evidence.addAll(jdbc.queryForList("""
                    SELECT mi.mapping_set_id, ms.question_version_id, mi.taxonomy_release_id,
                           mi.skill_id, mi.role, mi.weight
                    FROM question_mapping_set_items mi
                    JOIN question_mapping_sets ms
                      ON ms.mapping_set_id = mi.mapping_set_id
                     AND ms.taxonomy_release_id = mi.taxonomy_release_id
                    JOIN taxonomy_releases tr
                      ON tr.taxonomy_release_id = mi.taxonomy_release_id
                    WHERE mi.mapping_set_id = ?
                      AND mi.role <> 'EXCLUDED'
                      AND ms.status IN ('PUBLISHED', 'RETIRED')
                      AND tr.status IN ('PUBLISHED', 'RETIRED', 'SUPERSEDED')
                    ORDER BY mi.skill_id
                    """, mappingId));
        }
        return evidence;
    }

    private List<Map<String, Object>> competencyProvenance(List<EvaluatedItem> evaluated) {
        List<Long> sectionIds = evaluated.stream().map(item -> item.item().sectionId())
                .distinct().sorted().toList();
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (Long sectionId : sectionIds) {
            evidence.addAll(jdbc.queryForList("""
                    SELECT section_id, competency_id, weight, is_primary
                    FROM section_competencies
                    WHERE section_id = ?
                    ORDER BY competency_id
                    """, sectionId));
        }
        return evidence;
    }

    private static List<String> stringArray(Array array) {
        if (array == null) return List.of();
        try {
            Object[] values = (Object[]) array.getArray();
            List<String> result = new ArrayList<>(values.length);
            for (Object value : values) result.add(String.valueOf(value));
            return result;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read scoring evidence.", exception);
        }
    }

    private static List<NumericExpectedAnswer> numericAnswerArray(Array array) {
        List<NumericExpectedAnswer> result = new ArrayList<>();
        for (String pair : stringArray(array)) {
            String[] values = pair.split(":", 2);
            result.add(new NumericExpectedAnswer(
                    new BigDecimal(values[0]), new BigDecimal(values[1])));
        }
        return result;
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static boolean isSubmitted(String status) {
        return "SUBMITTED".equals(status) || "AUTO_SUBMITTED".equals(status);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Scoring result was not found.");
    }

    private record Attempt(
            long id, long userId, long testVersionId, String status,
            Integer timeSpentSeconds, Long taxonomyReleaseId) {}

    private record Item(
            long testItemId, long testSectionId, int order, BigDecimal points,
            Long mappingSetId, Long taxonomyReleaseId, long sectionId, String sectionName,
            long questionVersionId, String responseMode, String cognitiveLevel,
            BigDecimal estimatedDifficulty, Long attemptAnswerId, String answerText,
            BigDecimal numericAnswer, boolean answered, Integer timeSpentSeconds,
            List<String> selectedKeys, List<String> correctKeys, List<String> correctTextValues,
            List<NumericExpectedAnswer> correctNumericValues) {}

    private record EvaluatedItem(Item item, Boolean correct, boolean skipped, BigDecimal score) {
        private BigDecimal maxScore() { return item.points(); }
    }
    private record WeightedTarget(long id, BigDecimal weight) {}
    private record SkillInfo(String name) {}
    private record SummaryCounts(
            int attempts, BigDecimal accuracy, int correct, int incorrect, int skipped,
            BigDecimal averageTime) {}
    private record Improvement(int attempts, BigDecimal change) {}
    private record AnalyticsSelection(
            Long examId,
            List<ScoringDtos.AnalyticsExam> exams,
            ScoringDtos.AnalyticsCohort cohort,
            List<ScoringDtos.AnalyticsCohort> cohorts) {}

    private static final class SectionAggregate {
        private final String name;
        private BigDecimal score = BigDecimal.ZERO;
        private BigDecimal maxScore = BigDecimal.ZERO;
        private int total;
        private int answered;
        private int correct;
        private int timeSeconds;
        private int timeCount;

        private SectionAggregate(String name) { this.name = name; }
        private void add(EvaluatedItem item) {
            total++;
            score = score.add(item.score());
            maxScore = maxScore.add(item.maxScore());
            if (!item.skipped()) answered++;
            if (Boolean.TRUE.equals(item.correct())) correct++;
            if (item.item().timeSpentSeconds() != null) {
                timeSeconds += item.item().timeSpentSeconds();
                timeCount++;
            }
        }
    }

    private static final class WeightedAggregate {
        private BigDecimal correctWeight = BigDecimal.ZERO;
        private BigDecimal weight = BigDecimal.ZERO;
        private int evidence;
        private int timeSeconds;
        private int timeCount;

        private void add(boolean correct, BigDecimal normalizedWeight, Integer time) {
            evidence++;
            weight = weight.add(normalizedWeight);
            if (correct) correctWeight = correctWeight.add(normalizedWeight);
            if (time != null) {
                timeSeconds += time;
                timeCount++;
            }
        }
        private BigDecimal accuracy() {
            return correctWeight.divide(weight, 4, RoundingMode.HALF_UP);
        }
    }

    private static final class DimensionAggregate {
        private final String type;
        private final String value;
        private int total;
        private int answered;
        private int correct;

        private DimensionAggregate(String type, String value) {
            this.type = type;
            this.value = value;
        }
        private void add(EvaluatedItem item) {
            total++;
            if (!item.skipped()) answered++;
            if (Boolean.TRUE.equals(item.correct())) correct++;
        }
    }
}
