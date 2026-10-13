package vn.smartassessment.api.framework.controller;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.smartassessment.api.framework.dto.CompetencyFrameworkDtos;
import vn.smartassessment.api.framework.service.CompetencyFrameworkService;

@RestController
@RequestMapping("/api")
public class CompetencyFrameworkController {
    private final CompetencyFrameworkService service;

    public CompetencyFrameworkController(CompetencyFrameworkService service) {
        this.service = service;
    }

    @GetMapping("/exams")
    public List<CompetencyFrameworkDtos.Exam> exams() {
        return service.getExams();
    }

    @GetMapping("/exams/{examId}")
    public CompetencyFrameworkDtos.Exam exam(@PathVariable Long examId) {
        return service.getExam(examId);
    }

    @GetMapping("/exams/{examId}/versions")
    public List<CompetencyFrameworkDtos.ExamVersion> examVersions(@PathVariable Long examId) {
        return service.getExamVersions(examId);
    }

    @GetMapping("/exam-versions/{examVersionId}/sections")
    public List<CompetencyFrameworkDtos.Section> sections(@PathVariable Long examVersionId) {
        return service.getSections(examVersionId);
    }

    @GetMapping("/sections/{sectionId}/competencies")
    public List<CompetencyFrameworkDtos.Competency> competencies(@PathVariable Long sectionId) {
        return service.getCompetencies(sectionId);
    }

    @GetMapping("/competencies/{competencyId}/domains")
    public List<CompetencyFrameworkDtos.Domain> domains(@PathVariable Long competencyId) {
        return service.getDomains(competencyId);
    }

    @GetMapping("/domains/{domainId}/topics")
    public List<CompetencyFrameworkDtos.Topic> topics(@PathVariable Long domainId) {
        return service.getTopics(domainId);
    }

    @GetMapping("/topics/{topicId}/skills")
    public List<CompetencyFrameworkDtos.Skill> skills(@PathVariable Long topicId) {
        return service.getSkills(topicId);
    }
}
