package vn.smartassessment.api.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Size(min = 2, max = 255) String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72) String password) {}

    public record LoginRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 72) String password) {}

    public record UserView(Long id, String email, String fullName, String username, String role) {}

    public record AuthResponse(String accessToken, String tokenType, UserView user) {}

    public record ErrorResponse(String error) {}
}
