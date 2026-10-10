package vn.smartassessment.api.dashboard;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.smartassessment.api.dashboard.dto.DashboardDtos;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/student/dashboard")
    public DashboardDtos.StudentDashboard student(@AuthenticationPrincipal Jwt jwt) {
        return dashboardService.student(jwt.getClaim("uid"));
    }

    @GetMapping("/teacher/dashboard")
    public DashboardDtos.TeacherDashboard teacher(@AuthenticationPrincipal Jwt jwt) {
        return dashboardService.teacher(jwt.getClaim("uid"));
    }

    @GetMapping("/admin/dashboard")
    public DashboardDtos.AdminDashboard admin() {
        return dashboardService.admin();
    }
}
