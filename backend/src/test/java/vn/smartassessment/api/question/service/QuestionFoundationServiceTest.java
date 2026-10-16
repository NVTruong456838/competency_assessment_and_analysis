package vn.smartassessment.api.question.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.ImportBatchRequest;

@ExtendWith(MockitoExtension.class)
class QuestionFoundationServiceTest {
    @Mock
    private JdbcTemplate jdbc;

    @Test
    void patternCandidateRejectsRawQuestionTextBeforePersistence() {
        QuestionFoundationService service = new QuestionFoundationService(jdbc, new ObjectMapper(), "");
        var sourceEvidence = JsonNodeFactory.instance.objectNode().put("stem", "raw assessment question");
        var request = new vn.smartassessment.api.question.dto.QuestionFoundationDtos.PatternCandidateRequest(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "STRUCTURAL_PATTERN",
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(),
                sourceEvidence,
                "miner-1",
                1);

        assertThatThrownBy(() -> service.createPatternCandidate(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(jdbc);
    }

    @Test
    void patternCandidateRequiresSha256CandidateKey() {
        QuestionFoundationService service = new QuestionFoundationService(jdbc, new ObjectMapper(), "");
        var request = new vn.smartassessment.api.question.dto.QuestionFoundationDtos.PatternCandidateRequest(
                "not-a-hash",
                "STRUCTURAL_PATTERN",
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(),
                "miner-1",
                1);

        assertThatThrownBy(() -> service.createPatternCandidate(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(jdbc);
    }

    @Test
    void patternCandidateRejectsRawQuestionTextInStructure() {
        QuestionFoundationService service = new QuestionFoundationService(jdbc, new ObjectMapper(), "");
        var structure = JsonNodeFactory.instance.objectNode().put("stem", "raw assessment question");
        var request = new vn.smartassessment.api.question.dto.QuestionFoundationDtos.PatternCandidateRequest(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "STRUCTURAL_PATTERN",
                structure,
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(),
                "miner-1",
                1);

        assertThatThrownBy(() -> service.createPatternCandidate(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(jdbc);
    }

    @Test
    void importPreviewDoesNotPersistAndDetectsWithinBatchDuplicates() {
        QuestionFoundationService service = new QuestionFoundationService(jdbc, new ObjectMapper(), "");
        var question = JsonNodeFactory.instance.objectNode()
                .put("question_id", "fixture-1")
                .put("stem", "Select the correct result.")
                .put("question_type", "SINGLE_CHOICE");
        question.putObject("metadata").put("exam", "TSA");
        question.putArray("options")
                .addObject().put("key", "A").put("text", "4");
        question.putArray("correct_answer").add("A");
        var reorderedQuestion = JsonNodeFactory.instance.objectNode()
                .put("question_type", "SINGLE_CHOICE")
                .put("stem", "Select the correct result.")
                .put("question_id", "fixture-1");
        reorderedQuestion.putArray("correct_answer").add("A");
        reorderedQuestion.putArray("options")
                .addObject().put("key", "A").put("text", "4");
        reorderedQuestion.putObject("metadata").put("exam", "TSA");
        var request = new ImportBatchRequest(
                "fixture", "fixture.json", List.of(question, reorderedQuestion));

        var result = service.previewQuestions(request);

        assertThat(result.validQuestions()).isEqualTo(1);
        assertThat(result.invalidQuestions()).isEqualTo(1);
        assertThat(result.items().get(1).errors())
                .contains("Duplicate question_id in this import batch.")
                .contains("Duplicate normalized payload in this import batch.");
        verify(jdbc).query(any(String.class), any(RowMapper.class), any(Object.class));
        verify(jdbc, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    void studentQuestionQueryDoesNotSelectAnswerExplanation() {
        QuestionFoundationService service = new QuestionFoundationService(jdbc, new ObjectMapper(), "");

        service.publishedQuestions(null);

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(
                sql.capture(),
                (Object) isNull(),
                (Object) isNull());
        assertThat(sql.getValue()).doesNotContain("v.explanation", "correct_answer");
        assertThat(sql.getValue()).contains("newer_mapping", "passage_content", "visual_assets");
    }

    @Test
    void revisionRejectsNonRelativeVisualAssetPathsDuringValidation() {
        QuestionFoundationService service = new QuestionFoundationService(jdbc, new ObjectMapper(), "");
        var question = JsonNodeFactory.instance.objectNode()
                .put("stem", "Question stem")
                .put("question_type", "SINGLE_CHOICE");
        question.putObject("metadata").put("exam", "TSA");
        question.putArray("options").addObject().put("key", "A").put("text", "Answer");
        question.putArray("correct_answer").add("A");
        question.putArray("visual_assets").addObject()
                .put("path", "..\\outside.png");

        var result = service.previewQuestions(new ImportBatchRequest(
                "fixture", "fixture.json", List.of(question)));

        assertThat(result.invalidQuestions()).isEqualTo(1);
        assertThat(result.items().get(0).errors())
                .contains("Each visual asset requires a safe relative path.");
    }

    @Test
    void questionPublicationRejectsUnapprovedVersionBeforeMutation() {
        QuestionFoundationService service = new QuestionFoundationService(jdbc, new ObjectMapper(), "");
        when(jdbc.queryForObject(any(String.class), eq(Integer.class), eq(12L))).thenReturn(1);

        assertThatThrownBy(() -> service.publishQuestionVersion(12))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(jdbc, never()).update(any(String.class), any(Object[].class));
    }
}
