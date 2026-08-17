package com.semanticdocs.auth;

import com.semanticdocs.common.ApiExceptions;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration and login. Controllers stay thin; the rules live here. */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new ApiExceptions.ConflictException("That email is already registered");
        }
        User user = new User(
                email,
                passwordEncoder.encode(request.password()),
                request.displayName() == null || request.displayName().isBlank()
                        ? email.split("@")[0]
                        : request.displayName().trim());
        userRepository.save(user);
        return buildResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiExceptions.BadRequestException(
                        "Email or password is incorrect"));

        // Same message for "no such user" and "wrong password" on purpose: a different
        // message would let an attacker discover which emails have accounts.
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiExceptions.BadRequestException("Email or password is incorrect");
        }
        return buildResponse(user);
    }

    private AuthDtos.AuthResponse buildResponse(User user) {
        return new AuthDtos.AuthResponse(
                jwtService.generateToken(user),
                "Bearer",
                jwtService.expirySeconds(),
                AuthDtos.UserSummary.from(user));
    }
}
