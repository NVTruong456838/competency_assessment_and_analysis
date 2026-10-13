package vn.smartassessment.api.framework.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import vn.smartassessment.api.common.ApiExceptionHandler;
import vn.smartassessment.api.config.JwtConfig;
import vn.smartassessment.api.config.SecurityConfig;
import vn.smartassessment.api.framework.dto.CompetencyFrameworkDtos;
import vn.smartassessment.api.framework.service.CompetencyFrameworkService;

@WebMvcTest(controllers = CompetencyFrameworkController.class, properties = {
        "app.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.jwt.issuer=https://smart-assessment.local"
})
@Import({SecurityConfig.class, JwtConfig.class, ApiExceptionHandler.class})
class CompetencyFrameworkControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CompetencyFrameworkService service;

    @Test
    void rejectsRequestsWithoutJwt() throws Exception {
        mockMvc.perform(get("/api/exams"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"STUDENT", "TEACHER", "ADMIN"})
    void allowsEveryExistingRoleToReadFramework(String role) throws Exception {
        when(service.getExams()).thenReturn(List.of(
                new CompetencyFrameworkDtos.Exam(1L, "TSA", "Đánh giá tư duy", null, "ACTIVE"),
                new CompetencyFrameworkDtos.Exam(2L, "HSA", "Đánh giá năng lực", null, "ACTIVE")));

        mockMvc.perform(get("/api/exams")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        [
                          {"examId":1,"code":"TSA","name":"Đánh giá tư duy","description":null,"status":"ACTIVE"},
                          {"examId":2,"code":"HSA","name":"Đánh giá năng lực","description":null,"status":"ACTIVE"}
                        ]
                        """));
    }

    @Test
    void returnsBadRequestForInvalidId() throws Exception {
        mockMvc.perform(get("/api/exams/not-a-number").with(jwt()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsNotFoundForMissingExam() throws Exception {
        when(service.getExam(900L))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));

        mockMvc.perform(get("/api/exams/900").with(jwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsEmptyArrayForAnExistingParentWithNoChildren() throws Exception {
        when(service.getExamVersions(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/exams/1/versions").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(service).getExamVersions(1L);
    }

    @Test
    void usesExplicitExamVersionForSectionLookup() throws Exception {
        when(service.getSections(12L)).thenReturn(List.of());

        mockMvc.perform(get("/api/exam-versions/12/sections").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(service).getSections(12L);
    }
}
