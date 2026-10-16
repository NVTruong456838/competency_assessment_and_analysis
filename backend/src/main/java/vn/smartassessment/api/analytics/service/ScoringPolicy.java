package vn.smartassessment.api.analytics.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class ScoringPolicy {
    public static final String SCORING_VERSION = "objective-exact-v1";
    public static final String ANALYTICS_VERSION = "evidence-mean-v1";
    public static final int SUFFICIENT_EVIDENCE_COUNT = 5;

    private ScoringPolicy() {}

    public record ExpectedAnswers(
            Set<String> optionKeys,
            List<String> textValues,
            List<NumericExpectedAnswer> numericValues) {}

    public record NumericExpectedAnswer(BigDecimal value, BigDecimal tolerance) {}

    public static boolean isCorrect(
            String responseMode,
            Set<String> selectedOptionKeys,
            String answerText,
            BigDecimal numericAnswer,
            ExpectedAnswers expected) {
        return switch (responseMode) {
            case "SINGLE_OPTION", "MULTI_OPTION" ->
                    !expected.optionKeys().isEmpty()
                            && selectedOptionKeys.equals(expected.optionKeys());
            case "BOOLEAN" -> textMatch(answerText, expected.textValues(), true);
            case "TEXT" -> textMatch(answerText, expected.textValues(), false);
            case "NUMERIC" -> numericMatch(numericAnswer, expected.numericValues());
            default -> throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Scoring does not support response mode " + responseMode + ".");
        };
    }

    public static BigDecimal confidence(int evidenceCount) {
        return BigDecimal.valueOf(evidenceCount)
                .divide(BigDecimal.valueOf(evidenceCount + 3L), 4, java.math.RoundingMode.HALF_UP);
    }

    public static boolean hasSufficientEvidence(int evidenceCount) {
        return evidenceCount >= SUFFICIENT_EVIDENCE_COUNT;
    }

    private static boolean textMatch(String response, List<String> expectedValues, boolean booleanMode) {
        if (response == null || response.isBlank() || expectedValues.isEmpty()) return false;
        String normalized = normalize(response);
        if (booleanMode) normalized = normalized.toLowerCase(Locale.ROOT);
        final String candidate = normalized;
        return expectedValues.stream()
                .map(ScoringPolicy::normalize)
                .map(value -> booleanMode ? value.toLowerCase(Locale.ROOT) : value)
                .anyMatch(candidate::equals);
    }

    private static boolean numericMatch(
            BigDecimal response, List<NumericExpectedAnswer> expectedValues) {
        if (response == null || expectedValues.isEmpty()) return false;
        return expectedValues.stream().anyMatch(expected -> {
            BigDecimal acceptedTolerance = expected.tolerance() == null
                    ? BigDecimal.ZERO : expected.tolerance();
            return response.subtract(expected.value()).abs().compareTo(acceptedTolerance) <= 0;
        });
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
    }
}
