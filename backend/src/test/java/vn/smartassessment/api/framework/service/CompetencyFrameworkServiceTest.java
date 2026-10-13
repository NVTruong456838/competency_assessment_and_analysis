package vn.smartassessment.api.framework.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.smartassessment.api.framework.dto.CompetencyFrameworkDtos;
import vn.smartassessment.api.framework.repository.CompetencyFrameworkRepository;

@ExtendWith(MockitoExtension.class)
class CompetencyFrameworkServiceTest {
    @Mock
    private CompetencyFrameworkRepository repository;

    @InjectMocks
    private CompetencyFrameworkService service;

    @Test
    void returnsEmptyChildrenWhenParentExists() {
        when(repository.existsExam(3L)).thenReturn(true);
        when(repository.findExamVersions(3L)).thenReturn(List.of());

        assertThat(service.getExamVersions(3L)).isEmpty();
        verify(repository).findExamVersions(3L);
    }

    @Test
    void returnsNotFoundWhenParentDoesNotExist() {
        when(repository.existsSection(42L)).thenReturn(false);

        assertThatThrownBy(() -> service.getCompetencies(42L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void looksUpSectionsOnlyForTheExplicitExamVersion() {
        when(repository.existsExamVersion(12L)).thenReturn(true);
        when(repository.findSections(12L)).thenReturn(List.of());

        assertThat(service.getSections(12L)).isEmpty();

        verify(repository).findSections(12L);
    }

    @Test
    void propagatesDatabaseFailuresInsteadOfReturningEmptyList() {
        when(repository.existsTopic(9L)).thenReturn(true);
        when(repository.findSkills(9L)).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.getSkills(9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }

    @Test
    void returnsExamDetailsFromRepository() {
        CompetencyFrameworkDtos.Exam exam =
                new CompetencyFrameworkDtos.Exam(1L, "TSA", "Đánh giá tư duy", null, "ACTIVE");
        when(repository.findExamById(1L)).thenReturn(java.util.Optional.of(exam));

        assertThat(service.getExam(1L)).isEqualTo(exam);
    }
}
