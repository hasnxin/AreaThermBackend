package com.areatherm.api;

import com.areatherm.security.AppUser;
import com.areatherm.security.AppUserService;
import com.areatherm.security.JwtProperties;
import com.areatherm.security.JwtService;
import com.areatherm.security.dto.AuthResponse;
import com.areatherm.security.dto.LoginRequest;
import com.areatherm.security.dto.RegisterRequest;
import com.areatherm.security.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The only {@code /api/v1/**} endpoints {@link com.areatherm.security.SecurityConfig}
 * leaves open to anonymous callers -- see its {@code PUBLIC_PATHS}. Every
 * other controller in this package now needs a bearer token obtained from
 * {@link #login}.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
public class AuthController {

    private final AppUserService appUserService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;

    public AuthController(AppUserService appUserService, JwtService jwtService,
                           PasswordEncoder passwordEncoder, JwtProperties jwtProperties) {
        this.appUserService = appUserService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.jwtProperties = jwtProperties;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new user", description = "role defaults to ENGINEER when omitted.")
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        AppUser user = appUserService.register(request.email(), request.displayName(), request.password(), request.role());
        return UserResponse.from(user);
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange email + password for a bearer JWT")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        AppUser user = appUserService.findByEmail(request.email())
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        String token = jwtService.generateToken(user);
        return new AuthResponse(token, "Bearer", jwtProperties.getExpirationMinutes());
    }
}
