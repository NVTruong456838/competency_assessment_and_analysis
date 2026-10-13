package vn.smartassessment.api.identity.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test")
public class TestRoleController {
    @GetMapping("/student")
    public Map<String, String> student() {
        return Map.of("role", "STUDENT", "message", "Bạn có quyền truy cập API dành cho học sinh.");
    }

    @GetMapping("/teacher")
    public Map<String, String> teacher() {
        return Map.of("role", "TEACHER", "message", "Bạn có quyền truy cập API dành cho giáo viên.");
    }

    @GetMapping("/admin")
    public Map<String, String> admin() {
        return Map.of("role", "ADMIN", "message", "Bạn có quyền truy cập API dành cho quản trị viên.");
    }
}
