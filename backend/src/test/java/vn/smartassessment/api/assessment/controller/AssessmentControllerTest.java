package vn.smartassessment.api.assessment.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.AttemptSummary;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.ExamQuestion;
import vn.smartassessment.api.assessment.dto.AssessmentDtos.ExamSession;
import vn.smartassessment.api.assessment.service.AssessmentService;
import vn.smartassessment.api.common.ApiExceptionHandler;
import vn.smartassessment.api.config.JwtConfig;
import vn.smartassessment.api.config.SecurityConfig;

@WebMvcTest(controllers = AssessmentController.class, properties = {
        "app.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.jwt.issuer=https://smart-assessment.local"
})
@Import({SecurityConfig.class, JwtConfig.class, ApiExceptionHandler.class})
class AssessmentControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssessmentService service;

    @Test
    void restrictsTestPublicationToAdmins() throws Exception {
        mockMvc.perform(post("/api/admin/assessments/test-versions/12/publish")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TEACHER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void restrictsStudentAttemptDataToStudents() throws Exception {
        mockMvc.perform(get("/api/student/assessments/attempts/12")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TEACHER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void examQuestionResponseDoesNotExposeAnswerKeysOrExplanations() throws Exception {
        ExamQuestion question = new ExamQuestion(
                21, 1, 31, "SINGLE_CHOICE", "SINGLE_OPTION", "Synthetic prompt",
                "M1", BigDecimal.valueOf(0.5), "Synthetic section", null, null,
                List.of(Map.of("key", "A", "text", "Option A")),
                List.of(), List.of(), null, 0, false);
        when(service.getExamSession(12, 7)).thenReturn(new ExamSession(
                new AttemptSummary(12, "IN_PROGRESS", Instant.now(), Instant.now().plusSeconds(300),
                        300, 1, 0, 0),
                "Synthetic test", "TSA", List.of(question)));

        mockMvc.perform(get("/api/student/assessments/attempts/12")
                        .with(jwt().jwt(token -> token.claim("uid", 7L))
                                .authorities(new SimpleGrantedAuthority("ROLE_STUDENT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].stem").value("Synthetic prompt"))
                .andExpect(jsonPath("$.questions[0].options[0].key").value("A"))
                .andExpect(jsonPath("$.questions[0].correctAnswer").doesNotExist())
                .andExpect(jsonPath("$.questions[0].explanation").doesNotExist());
    }

    @Test
    void requiresAuthenticationForStudentAttemptRoutes() throws Exception {
        mockMvc.perform(get("/api/student/assessments/attempts/12"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsMalformedBlueprintWithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/teacher/assessments/blueprints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TEACHER"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
