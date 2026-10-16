package vn.smartassessment.api.analytics.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.smartassessment.api.analytics.dto.ScoringDtos;
import vn.smartassessment.api.analytics.service.ScoringService;

@RestController
@RequestMapping("/api")
public class ScoringController {
    private final ScoringService scoringService;

    public ScoringController(ScoringService scoringService) {
        this.scoringService = scoringService;
    }

    @PostMapping("/student/assessments/attempts/{attemptId}/scoring-runs")
    @ResponseStatus(HttpStatus.CREATED)
    public ScoringDtos.ScoringResult scoreAttempt(
            @PathVariable long attemptId,
            @Valid @RequestBody ScoreRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return scoringService.scoreAttempt(attemptId, userId(jwt), request.requestId());
    }

    @GetMapping("/student/assessments/attempts/{attemptId}/scoring")
    public ScoringDtos.ScoringResult studentResult(
            @PathVariable long attemptId,
            @AuthenticationPrincipal Jwt jwt) {
        return scoringService.studentResult(attemptId, userId(jwt));
    }

    @GetMapping("/teacher/assessments/attempts/{attemptId}/scoring")
    public ScoringDtos.ScoringResult teacherResult(
            @PathVariable long attemptId,
            @AuthenticationPrincipal Jwt jwt) {
        return scoringService.teacherResult(attemptId, userId(jwt));
    }

    @GetMapping("/student/analytics")
    public ScoringDtos.AnalyticsSummary studentAnalytics(
            @RequestParam(required = false) Long examId,
            @RequestParam(required = false) Long examVersionId,
            @RequestParam(required = false) Long testVersionId,
            @RequestParam(required = false) Long taxonomyReleaseId,
            @RequestParam(required = false) String scoringVersion,
            @RequestParam(required = false) String analyticsVersion,
            @AuthenticationPrincipal Jwt jwt) {
        return scoringService.studentAnalytics(
                userId(jwt), examId, examVersionId, testVersionId, taxonomyReleaseId,
                scoringVersion, analyticsVersion);
    }

    @GetMapping("/teacher/analytics")
    public ScoringDtos.TeacherAnalyticsSummary teacherAnalytics(
            @RequestParam(required = false) Long examId,
            @RequestParam(required = false) Long examVersionId,
            @RequestParam(required = false) Long testVersionId,
            @RequestParam(required = false) Long taxonomyReleaseId,
            @RequestParam(required = false) String scoringVersion,
            @RequestParam(required = false) String analyticsVersion,
            @AuthenticationPrincipal Jwt jwt) {
        return scoringService.teacherAnalytics(
                userId(jwt), examId, examVersionId, testVersionId, taxonomyReleaseId,
                scoringVersion, analyticsVersion);
    }

    private static long userId(Jwt jwt) {
        Long userId = jwt.getClaim("uid");
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user id is missing.");
        }
        return userId;
    }

    public record ScoreRequest(@NotNull UUID requestId) {}
}
