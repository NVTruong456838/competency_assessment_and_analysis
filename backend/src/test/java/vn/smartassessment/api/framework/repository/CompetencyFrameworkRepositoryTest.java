package vn.smartassessment.api.framework.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import vn.smartassessment.api.framework.dto.CompetencyFrameworkDtos;

@ExtendWith(MockitoExtension.class)
class CompetencyFrameworkRepositoryTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CompetencyFrameworkRepository repository =
            new CompetencyFrameworkRepository(jdbcTemplate);

    @Test
    void mapsSectionCompetenciesThroughJoinTable() throws Exception {
        when(jdbcTemplate.query(
                contains("FROM section_competencies"),
                org.mockito.ArgumentMatchers.<RowMapper<CompetencyFrameworkDtos.Competency>>any(),
                eq(42L))).thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<CompetencyFrameworkDtos.Competency> mapper = invocation.getArgument(1);
                    ResultSet result = mock(ResultSet.class);
                    when(result.getLong("competency_id")).thenReturn(7L);
                    when(result.getString("competency_code")).thenReturn("TSA_MATH_QUANT");
                    when(result.getString("competency_name")).thenReturn("Tư duy định lượng");
                    when(result.getString("description")).thenReturn("Năng lực toán học");
                    when(result.getString("status")).thenReturn("ACTIVE");
                    when(result.getBigDecimal("weight")).thenReturn(new BigDecimal("0.5000"));
                    when(result.getBoolean("is_primary")).thenReturn(true);

                    assertThat(sql).contains("JOIN competencies");
                    assertThat(sql).contains("section_competency.section_id = ?");
                    return List.of(mapper.mapRow(result, 0));
                });

        List<CompetencyFrameworkDtos.Competency> result =
                repository.findCompetenciesBySection(42L);

        assertThat(result).containsExactly(new CompetencyFrameworkDtos.Competency(
                7L,
                "TSA_MATH_QUANT",
                "Tư duy định lượng",
                "Năng lực toán học",
                "ACTIVE",
                new BigDecimal("0.5000"),
                true));
    }
}
