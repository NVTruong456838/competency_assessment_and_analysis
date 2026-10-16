package vn.smartassessment.api.question.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.smartassessment.api.common.ApiExceptionHandler;
import vn.smartassessment.api.config.JwtConfig;
import vn.smartassessment.api.config.SecurityConfig;
import vn.smartassessment.api.question.service.QuestionFoundationService;

@WebMvcTest(controllers = QuestionFoundationController.class, properties = {
        "app.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.jwt.issuer=https://smart-assessment.local"
})
@Import({SecurityConfig.class, JwtConfig.class, ApiExceptionHandler.class})
class QuestionFoundationControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuestionFoundationService service;

    @Test
    void restrictsImportPreviewToAdmins() throws Exception {
        mockMvc.perform(post("/api/admin/question-imports/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceName":"fixture","sourceLocator":"test.json","questions":[{}]}
                                """)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TEACHER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void restrictsQuestionReviewQueueToTeachersAndAdmins() throws Exception {
        mockMvc.perform(get("/api/teacher/question-versions/review-queue")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STUDENT"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void restrictsQuestionRevisionToTeachersAndAdmins() throws Exception {
        mockMvc.perform(post("/api/teacher/question-versions/12/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"authorType":"HUMAN","content":{}}
                                """)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_STUDENT"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void restrictsPublishedQuestionListingToStudents() throws Exception {
        mockMvc.perform(get("/api/student/questions")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TEACHER"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }
}
