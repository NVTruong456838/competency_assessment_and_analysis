package vn.smartassessment.api.dashboard;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vn.smartassessment.api.dashboard.dto.DashboardDtos;

@Service
public class DashboardService {
    private final JdbcTemplate jdbcTemplate;

    public DashboardService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DashboardDtos.StudentDashboard student(Long userId) {
        DashboardDtos.StudentAttempts attempts = jdbcTemplate.queryForObject("""
                SELECT
                    COUNT(*) AS total,
                    COUNT(*) FILTER (WHERE status IN ('SUBMITTED', 'AUTO_SUBMITTED')) AS completed,
                    COUNT(*) FILTER (WHERE status = 'IN_PROGRESS') AS in_progress
                FROM attempts
                WHERE user_id = ?
                """,
                (result, rowNumber) -> new DashboardDtos.StudentAttempts(
                        result.getLong("total"),
                        result.getLong("completed"),
                        result.getLong("in_progress")),
                userId);
        BigDecimal averageAccuracy = jdbcTemplate.queryForObject("""
                SELECT COALESCE(ROUND(AVG(result.accuracy) * 100, 1), 0)
                FROM attempts attempt
                JOIN attempt_results result ON result.attempt_id = attempt.attempt_id
                WHERE attempt.user_id = ?
                  AND attempt.status IN ('SUBMITTED', 'AUTO_SUBMITTED')
                """,
                BigDecimal.class,
                userId);
        return new DashboardDtos.StudentDashboard(attempts, averageAccuracy);
    }

    public DashboardDtos.TeacherDashboard teacher(Long userId) {
        DashboardDtos.TeacherClasses classes = jdbcTemplate.queryForObject("""
                SELECT
                    COUNT(DISTINCT classroom.class_id) AS total,
                    COUNT(DISTINCT member.user_id) FILTER (
                        WHERE member.status = 'ACTIVE' AND role.role_code = 'STUDENT'
                    ) AS students
                FROM classes classroom
                LEFT JOIN class_members member ON member.class_id = classroom.class_id
                LEFT JOIN user_roles assigned_role ON assigned_role.user_id = member.user_id
                LEFT JOIN roles role ON role.role_id = assigned_role.role_id
                WHERE classroom.teacher_id = ?
                  AND classroom.status = 'ACTIVE'
                """,
                (result, rowNumber) -> new DashboardDtos.TeacherClasses(
                        result.getLong("total"),
                        result.getLong("students")),
                userId);
        DashboardDtos.TeacherTests tests = jdbcTemplate.queryForObject("""
                SELECT
                    COUNT(*) AS total,
                    COUNT(*) FILTER (WHERE status = 'PUBLISHED') AS published
                FROM tests
                WHERE created_by = ?
                """,
                (result, rowNumber) -> new DashboardDtos.TeacherTests(
                        result.getLong("total"),
                        result.getLong("published")),
                userId);
        return new DashboardDtos.TeacherDashboard(classes, tests);
    }

    public DashboardDtos.AdminDashboard admin() {
        DashboardDtos.AdminUsers users = jdbcTemplate.queryForObject("""
                SELECT
                    COUNT(DISTINCT account.user_id) AS total,
                    COUNT(DISTINCT account.user_id) FILTER (WHERE account.status = 'ACTIVE') AS active,
                    COUNT(DISTINCT account.user_id) FILTER (WHERE role.role_code = 'STUDENT') AS students,
                    COUNT(DISTINCT account.user_id) FILTER (WHERE role.role_code = 'TEACHER') AS teachers,
                    COUNT(DISTINCT account.user_id) FILTER (WHERE role.role_code = 'ADMIN') AS admins
                FROM users account
                LEFT JOIN user_roles assigned_role ON assigned_role.user_id = account.user_id
                LEFT JOIN roles role ON role.role_id = assigned_role.role_id
                """,
                (result, rowNumber) -> new DashboardDtos.AdminUsers(
                        result.getLong("total"),
                        result.getLong("active"),
                        result.getLong("students"),
                        result.getLong("teachers"),
                        result.getLong("admins")));
        DashboardDtos.AdminContent content = jdbcTemplate.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM classes) AS classes,
                    (SELECT COUNT(*) FROM tests) AS tests,
                    (SELECT COUNT(*) FROM tests WHERE status = 'PUBLISHED') AS published_tests,
                    (SELECT COUNT(*) FROM questions) AS questions
                """,
                (result, rowNumber) -> new DashboardDtos.AdminContent(
                        result.getLong("classes"),
                        result.getLong("tests"),
                        result.getLong("published_tests"),
                        result.getLong("questions")));
        return new DashboardDtos.AdminDashboard(users, content);
    }
}
