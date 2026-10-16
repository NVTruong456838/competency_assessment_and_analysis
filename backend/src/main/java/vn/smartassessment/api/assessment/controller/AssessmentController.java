package vn.smartassessment.api.assessment.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.BlueprintRequest;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.BlueprintVersion;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.BuildResult;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.BuildRequest;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.ExamSession;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.AvailableTest;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.ReviewRequest;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.SaveAnswerRequest;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.SaveAnswerResult;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.SubmitResult;
import vn.smartassessment.api.assessment.service.AssessmentService;

@RestController
@RequestMapping("/api")
public class AssessmentController {
    private final AssessmentService service;

    public AssessmentController(AssessmentService service) {
        this.service = service;
    }

    @GetMapping("/teacher/assessments/exam-versions")
    public List<Map<String, Object>> examVersions() {
        return service.availableExamVersions();
    }

    @GetMapping("/teacher/assessments/blueprints")
    public List<BlueprintVersion> blueprints() {
        return service.listBlueprints();
    }

    @PostMapping("/teacher/assessments/blueprints")
    @ResponseStatus(HttpStatus.CREATED)
    public BlueprintVersion createBlueprint(
            @Valid @RequestBody BlueprintRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.createBlueprint(request, jwt.getSubject());
    }

    @PostMapping("/teacher/assessments/blueprints/{blueprintId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public BlueprintVersion createBlueprintVersion(
            @PathVariable long blueprintId,
            @Valid @RequestBody BlueprintRequest request) {
        return service.createBlueprintVersion(blueprintId, request);
    }

    @PostMapping("/teacher/assessments/tests/build")
    public BuildResult buildTest(
            @Valid @RequestBody BuildRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.buildTest(request, jwt.getSubject());
    }

    @PostMapping("/admin/assessments/test-versions/{testVersionId}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void publishTest(
            @PathVariable long testVersionId,
            @AuthenticationPrincipal Jwt jwt) {
        service.publishTest(testVersionId, jwt.getSubject());
    }

    @GetMapping("/student/assessments/tests")
    public List<AvailableTest> availableTests(@AuthenticationPrincipal Jwt jwt) {
        return service.availableTests(userId(jwt));
    }

    @PostMapping("/student/assessments/test-versions/{testVersionId}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    public ExamSession startAttempt(
            @PathVariable long testVersionId,
            @AuthenticationPrincipal Jwt jwt) {
        return service.startAttempt(testVersionId, userId(jwt));
    }

    @PostMapping("/student/assessments/attempts/{attemptId}/resume")
    public ExamSession resumeAttempt(
            @PathVariable long attemptId,
            @AuthenticationPrincipal Jwt jwt) {
        return service.resumeAttempt(attemptId, userId(jwt));
    }

    @GetMapping("/student/assessments/attempts/{attemptId}")
    public ExamSession examSession(
            @PathVariable long attemptId,
            @AuthenticationPrincipal Jwt jwt) {
        return service.getExamSession(attemptId, userId(jwt));
    }

    @PostMapping("/student/assessments/attempts/{attemptId}/items/{itemId}/answer")
    public SaveAnswerResult saveAnswer(
            @PathVariable long attemptId,
            @PathVariable long itemId,
            @Valid @RequestBody SaveAnswerRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.saveAnswer(attemptId, itemId, userId(jwt), request);
    }

    @DeleteMapping("/student/assessments/attempts/{attemptId}/items/{itemId}/answer")
    public SaveAnswerResult clearAnswer(
            @PathVariable long attemptId,
            @PathVariable long itemId,
            @Valid @RequestBody SaveAnswerRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.clearAnswer(attemptId, itemId, userId(jwt), request);
    }

    @PostMapping("/student/assessments/attempts/{attemptId}/items/{itemId}/review")
    public SaveAnswerResult markForReview(
            @PathVariable long attemptId,
            @PathVariable long itemId,
            @Valid @RequestBody ReviewRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.markForReview(attemptId, itemId, userId(jwt), request);
    }

    @PostMapping("/student/assessments/attempts/{attemptId}/submit")
    public SubmitResult submitAttempt(
            @PathVariable long attemptId,
            @AuthenticationPrincipal Jwt jwt) {
        return service.submitAttempt(attemptId, userId(jwt));
    }

    private static long userId(Jwt jwt) {
        Long userId = jwt.getClaim("uid");
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user id is missing.");
        }
        return userId;
    }
}
