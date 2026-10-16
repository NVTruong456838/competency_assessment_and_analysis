package vn.smartassessment.api.question.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.ImportBatchRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.ImportBatchResult;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.ImportPreviewResult;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.MappingSetRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.MappingSetReviewRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.MaterializeRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.MaterializedQuestion;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.PatternCandidateRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.PatternCandidateResult;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.PatternReviewRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.PatternSkillMappingRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.PatternSkillReviewRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.QuestionReviewRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.QuestionRevisionRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.QuestionGroupRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.QuestionGroupItemRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.QuestionGroupReviewRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.QuestionGroupResult;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.TaxonomyCandidateRegistrationRequest;
import vn.smartassessment.api.question.dto.QuestionFoundationDtos.TaxonomyCandidateResult;
import vn.smartassessment.api.question.service.QuestionFoundationService;

@RestController
@RequestMapping("/api")
public class QuestionFoundationController {
    private final QuestionFoundationService service;

    public QuestionFoundationController(QuestionFoundationService service) {
        this.service = service;
    }

    @PostMapping("/admin/taxonomy/releases/candidates")
    @ResponseStatus(HttpStatus.CREATED)
    public TaxonomyCandidateResult registerTaxonomyCandidate(
            @Valid @RequestBody TaxonomyCandidateRegistrationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.registerTaxonomyCandidate(request, jwt.getSubject());
    }

    @GetMapping("/admin/taxonomy/releases/{releaseId}/verify")
    public Map<String, Object> verifyTaxonomyRelease(@PathVariable long releaseId) {
        return service.verifyTaxonomyRelease(releaseId);
    }

    @PostMapping("/admin/question-imports")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportBatchResult stageQuestions(
            @Valid @RequestBody ImportBatchRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.stageQuestions(request, jwt.getSubject());
    }

    @PostMapping("/admin/question-imports/preview")
    public ImportPreviewResult previewQuestions(@Valid @RequestBody ImportBatchRequest request) {
        return service.previewQuestions(request);
    }

    @GetMapping("/admin/question-imports")
    public List<Map<String, Object>> importBatches() {
        return service.importBatches();
    }

    @GetMapping("/admin/question-imports/{batchId}/items")
    public List<Map<String, Object>> importItems(@PathVariable long batchId) {
        return service.importItems(batchId);
    }

    @PostMapping("/admin/question-imports/items/{itemId}/materialize")
    @ResponseStatus(HttpStatus.CREATED)
    public MaterializedQuestion materializeQuestion(
            @PathVariable long itemId,
            @Valid @RequestBody MaterializeRequest request) {
        return service.materializeQuestion(itemId, request);
    }

    @GetMapping("/teacher/questions/{questionId}/versions")
    public List<Map<String, Object>> questionVersionHistory(@PathVariable long questionId) {
        return service.questionVersionHistory(questionId);
    }

    @PostMapping("/teacher/question-versions/{versionId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public MaterializedQuestion createQuestionRevision(
            @PathVariable long versionId,
            @Valid @RequestBody QuestionRevisionRequest request) {
        return service.createQuestionRevision(versionId, request);
    }

    @PostMapping("/teacher/question-versions/{versionId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public void reviewQuestion(
            @PathVariable long versionId,
            @Valid @RequestBody QuestionReviewRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        service.reviewQuestion(versionId, request, jwt.getSubject());
    }

    @PostMapping("/admin/question-versions/{versionId}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void publishQuestion(@PathVariable long versionId) {
        service.publishQuestionVersion(versionId);
    }

    @PostMapping("/teacher/question-groups")
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionGroupResult createQuestionGroup(
            @Valid @RequestBody QuestionGroupRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.createQuestionGroup(request, jwt.getSubject());
    }

    @PostMapping("/teacher/question-groups/{groupId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionGroupResult createQuestionGroupVersion(
            @PathVariable long groupId,
            @Valid @RequestBody QuestionGroupRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.createQuestionGroupVersion(groupId, request, jwt.getSubject());
    }

    @GetMapping("/teacher/question-groups")
    public List<Map<String, Object>> questionGroups() {
        return service.questionGroups();
    }

    @GetMapping("/teacher/question-group-versions/{versionId}")
    public Map<String, Object> questionGroupVersion(@PathVariable long versionId) {
        return service.questionGroupVersion(versionId);
    }

    @PostMapping("/teacher/question-group-versions/{versionId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public void addQuestionGroupItem(
            @PathVariable long versionId,
            @Valid @RequestBody QuestionGroupItemRequest request) {
        service.addQuestionGroupItem(versionId, request);
    }

    @DeleteMapping("/teacher/question-group-versions/{versionId}/items/{questionVersionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeQuestionGroupItem(
            @PathVariable long versionId, @PathVariable long questionVersionId) {
        service.removeQuestionGroupItem(versionId, questionVersionId);
    }

    @PostMapping("/teacher/question-group-versions/{versionId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public void reviewQuestionGroup(
            @PathVariable long versionId,
            @Valid @RequestBody QuestionGroupReviewRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        service.reviewQuestionGroup(versionId, request, jwt.getSubject());
    }

    @PostMapping("/admin/question-group-versions/{versionId}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void publishQuestionGroup(@PathVariable long versionId, @AuthenticationPrincipal Jwt jwt) {
        service.publishQuestionGroup(versionId, jwt.getSubject());
    }

    @GetMapping("/teacher/question-versions/review-queue")
    public List<Map<String, Object>> questionReviewQueue() {
        return service.questionReviewQueue();
    }

    @PostMapping("/teacher/question-versions/{versionId}/mapping-sets")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> createMappingSet(
            @PathVariable long versionId,
            @Valid @RequestBody MappingSetRequest request) {
        return Map.of("mappingSetId", service.createMappingSet(versionId, request));
    }

    @PostMapping("/teacher/question-mapping-sets/{mappingSetId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public void reviewMappingSet(
            @PathVariable long mappingSetId,
            @Valid @RequestBody MappingSetReviewRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        service.reviewMappingSet(mappingSetId, request, jwt.getSubject());
    }

    @GetMapping("/teacher/question-mapping-sets/review-queue")
    public List<Map<String, Object>> mappingSetReviewQueue() {
        return service.mappingSetReviewQueue();
    }

    @PostMapping("/admin/question-patterns/candidates")
    @ResponseStatus(HttpStatus.CREATED)
    public PatternCandidateResult createPatternCandidate(
            @Valid @RequestBody PatternCandidateRequest request) {
        return service.createPatternCandidate(request);
    }

    @PostMapping("/teacher/question-pattern-versions/{versionId}/skill-mappings")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> mapPatternToSkill(
            @PathVariable long versionId,
            @Valid @RequestBody PatternSkillMappingRequest request) {
        return Map.of("mappingId", service.mapPatternToSkill(versionId, request));
    }

    @PostMapping("/teacher/question-pattern-skill-mappings/{mappingId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public void reviewPatternSkillMapping(
            @PathVariable long mappingId,
            @Valid @RequestBody PatternSkillReviewRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        service.reviewPatternSkillMapping(mappingId, request, jwt.getSubject());
    }

    @PostMapping("/teacher/question-pattern-versions/{versionId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public void reviewPattern(
            @PathVariable long versionId,
            @Valid @RequestBody PatternReviewRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        service.reviewPattern(versionId, request, jwt.getSubject());
    }

    @GetMapping("/teacher/question-pattern-versions/review-queue")
    public List<Map<String, Object>> patternReviewQueue() {
        return service.patternReviewQueue();
    }

    @GetMapping("/student/questions")
    public List<Map<String, Object>> publishedQuestions(@RequestParam(required = false) String exam) {
        return service.publishedQuestions(exam);
    }

    @GetMapping("/teacher/question-patterns/approved")
    public List<Map<String, Object>> approvedPatterns(
            @RequestParam long skillId,
            @RequestParam long taxonomyReleaseId) {
        return service.approvedPatterns(skillId, taxonomyReleaseId);
    }
}
