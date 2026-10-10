package vn.smartassessment.api.dashboard.dto;

import java.math.BigDecimal;

public final class DashboardDtos {
    private DashboardDtos() {}

    public record StudentAttempts(long total, long completed, long inProgress) {}

    public record StudentDashboard(StudentAttempts attempts, BigDecimal averageAccuracyPercent) {}

    public record TeacherClasses(long total, long students) {}

    public record TeacherTests(long total, long published) {}

    public record TeacherDashboard(TeacherClasses classes, TeacherTests tests) {}

    public record AdminUsers(long total, long active, long students, long teachers, long admins) {}

    public record AdminContent(long classes, long tests, long publishedTests, long questions) {}

    public record AdminDashboard(AdminUsers users, AdminContent content) {}
}
