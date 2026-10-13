package vn.smartassessment.api.framework.service;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import vn.smartassessment.api.framework.dto.CompetencyFrameworkDtos;
import vn.smartassessment.api.framework.repository.CompetencyFrameworkRepository;

@Service
public class CompetencyFrameworkService {
    private final CompetencyFrameworkRepository repository;

    public CompetencyFrameworkService(CompetencyFrameworkRepository repository) {
        this.repository = repository;
    }

    public List<CompetencyFrameworkDtos.Exam> getExams() {
        return repository.findExams();
    }

    public CompetencyFrameworkDtos.Exam getExam(Long examId) {
        return repository.findExamById(examId)
                .orElseThrow(() -> notFound("Không tìm thấy kỳ thi."));
    }

    public List<CompetencyFrameworkDtos.ExamVersion> getExamVersions(Long examId) {
        requireExists(repository.existsExam(examId), "Không tìm thấy kỳ thi.");
        return repository.findExamVersions(examId);
    }

    public List<CompetencyFrameworkDtos.Section> getSections(Long examVersionId) {
        requireExists(repository.existsExamVersion(examVersionId), "Không tìm thấy phiên bản kỳ thi.");
        return repository.findSections(examVersionId);
    }

    public List<CompetencyFrameworkDtos.Competency> getCompetencies(Long sectionId) {
        requireExists(repository.existsSection(sectionId), "Không tìm thấy phần thi.");
        return repository.findCompetenciesBySection(sectionId);
    }

    public List<CompetencyFrameworkDtos.Domain> getDomains(Long competencyId) {
        requireExists(repository.existsCompetency(competencyId), "Không tìm thấy năng lực.");
        return repository.findDomains(competencyId);
    }

    public List<CompetencyFrameworkDtos.Topic> getTopics(Long domainId) {
        requireExists(repository.existsDomain(domainId), "Không tìm thấy miền năng lực.");
        return repository.findTopics(domainId);
    }

    public List<CompetencyFrameworkDtos.Skill> getSkills(Long topicId) {
        requireExists(repository.existsTopic(topicId), "Không tìm thấy chủ đề.");
        return repository.findSkills(topicId);
    }

    private static void requireExists(boolean exists, String message) {
        if (!exists) {
            throw notFound(message);
        }
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
