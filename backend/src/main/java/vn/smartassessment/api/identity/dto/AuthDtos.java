package vn.smartassessment.api.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Size(min = 2, max = 255) String fullName,
            @Email @Size(max = 255) String email,
            @Size(max = 20) String phoneNumber,
            @NotBlank @Size(min = 8, max = 72) String password) {}

    public record LoginRequest(
            @NotBlank @Size(max = 255) @com.fasterxml.jackson.annotation.JsonAlias("email")
            String emailOrPhone,
            @NotBlank @Size(max = 72) String password) {}

    public record UserView(
            Long id,
            Long userId,
            String email,
            String phoneNumber,
            String fullName,
            String username,
            String role,
            String status,
            List<String> roles) {}

    public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserView user) {}

    public record ErrorResponse(String error) {}
}
