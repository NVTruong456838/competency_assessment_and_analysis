package vn.smartassessment.api.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ScoringPolicyTest {
    private static final ScoringPolicy.ExpectedAnswers NO_TEXT_OR_NUMERIC =
            new ScoringPolicy.ExpectedAnswers(Set.of("A"), List.of(), List.of());

    @Test
    void choiceScoringRequiresExactOptionSetAndRejectsPartialCredit() {
        assertThat(ScoringPolicy.isCorrect("SINGLE_OPTION", Set.of("A"), null, null,
                NO_TEXT_OR_NUMERIC)).isTrue();
        assertThat(ScoringPolicy.isCorrect("MULTI_OPTION", Set.of("A", "B"), null, null,
                new ScoringPolicy.ExpectedAnswers(Set.of("A", "B"), List.of(), List.of())))
                .isTrue();
        assertThat(ScoringPolicy.isCorrect("MULTI_OPTION", Set.of("A"), null, null,
                new ScoringPolicy.ExpectedAnswers(Set.of("A", "B"), List.of(), List.of())))
                .isFalse();
    }

    @Test
    void numericAnswersUseOnlyExplicitNonnegativeTolerance() {
        var expected = new ScoringPolicy.ExpectedAnswers(
                Set.of(), List.of(), List.of(
                        new ScoringPolicy.NumericExpectedAnswer(
                                new BigDecimal("2.5"), new BigDecimal("0.1"))));
        assertThat(ScoringPolicy.isCorrect(
                "NUMERIC", Set.of(), null, new BigDecimal("2.6"), expected)).isTrue();
        assertThat(ScoringPolicy.isCorrect(
                "NUMERIC", Set.of(), null, new BigDecimal("2.61"), expected)).isFalse();
    }

    @Test
    void textUsesTrimAndUnicodeNormalizationButRemainsCaseSensitive() {
        var expected = new ScoringPolicy.ExpectedAnswers(Set.of(), List.of("café"), List.of());
        assertThat(ScoringPolicy.isCorrect("TEXT", Set.of(), " cafe\u0301 ", null, expected)).isTrue();
        assertThat(ScoringPolicy.isCorrect("TEXT", Set.of(), "Café", null, expected)).isFalse();
    }

    @Test
    void onlyBooleanModeIgnoresCase() {
        var expected = new ScoringPolicy.ExpectedAnswers(Set.of(), List.of("TRUE"), List.of());
        assertThat(ScoringPolicy.isCorrect("BOOLEAN", Set.of(), "true", null, expected)).isTrue();
    }

    @Test
    void confidenceIsAnEvidenceIndicatorRatherThanProbability() {
        assertThat(ScoringPolicy.confidence(2)).isEqualByComparingTo("0.4000");
        assertThat(ScoringPolicy.hasSufficientEvidence(4)).isFalse();
        assertThat(ScoringPolicy.hasSufficientEvidence(5)).isTrue();
    }

    @Test
    void unsupportedModesFailExplicitly() {
        assertThatThrownBy(() -> ScoringPolicy.isCorrect(
                "UNKNOWN", Set.of(), null, null, NO_TEXT_OR_NUMERIC))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not support");
    }
}
