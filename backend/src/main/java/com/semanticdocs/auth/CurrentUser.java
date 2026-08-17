package com.semanticdocs.auth;

import com.semanticdocs.common.ApiExceptions;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Small helper so services can ask "who is calling?" without every method taking a
 * Principal parameter. Reads from the SecurityContext, which the JWT filter populated.
 */
@Component
public class CurrentUser {

    private final UserRepository userRepository;

    public CurrentUser(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiExceptions.BadRequestException("Not signed in");
        }
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Account no longer exists"));
    }
}
