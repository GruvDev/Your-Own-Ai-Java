package com.semanticdocs.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request and response shapes for the auth endpoints.
 *
 * <p>These are deliberately separate from the User entity. If a controller accepted a User
 * directly, a caller could POST {"role":"ADMIN"} and promote themselves - the classic
 * mass-assignment vulnerability. DTOs make the accepted fields explicit.
 */
public final class AuthDtos {

    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 100, message = "Use at least 8 characters")
            String password,
            @Size(max = 120) String displayName) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    public record AuthResponse(
            String token,
            String tokenType,
            long expiresInSeconds,
            UserSummary user) {
    }

    public record UserSummary(Long id, String email, String displayName, String role) {
        public static UserSummary from(User user) {
            return new UserSummary(user.getId(), user.getEmail(),
                    user.getDisplayName(), user.getRole());
        }
    }

    private AuthDtos() {
    }
}
