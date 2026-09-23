package com.areatherm.security;

import com.areatherm.api.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless, JWT-only security chain for the whole API.
 *
 * <ul>
 *   <li>No HTTP session ({@link SessionCreationPolicy#STATELESS}) -- every
 *       request must carry its own bearer token; nothing is kept server-side
 *       between requests.</li>
 *   <li>CSRF disabled -- CSRF defends session-cookie-authenticated browser
 *       state; there is no session here, and every request already needs an
 *       explicit {@code Authorization} header a cross-site form can't forge.</li>
 *   <li>{@code /api/v1/auth/**}, Swagger/OpenAPI, and the H2 console
 *       (local profile only, see application-local.yml) are reachable with
 *       no token; every other {@code /api/v1/**} path requires one, checked
 *       by {@link JwtAuthenticationFilter}.</li>
 *   <li>Neither {@code formLogin()} nor {@code httpBasic()} is configured (a
 *       pure JSON/JWT API needs neither), which means Spring Security's own
 *       fallback entry point -- a bare {@code Http403ForbiddenEntryPoint} --
 *       would otherwise answer an unauthenticated request with a plain 403.
 *       The explicit entry point/access-denied handlers below return
 *       API_SPEC.md's {@code {status, error, message, path}} envelope with
 *       the correct 401/403 instead, so an auth failure looks like every
 *       other error response in the API rather than a bare status line.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/api-docs/**",
            "/h2-console/**",
            "/error"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                     JwtAuthenticationFilter jwtAuthenticationFilter,
                                                     ObjectMapper objectMapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) -> writeError(
                                response, objectMapper, HttpStatus.UNAUTHORIZED,
                                "Authentication is required to access this resource", request.getRequestURI()))
                        .accessDeniedHandler((request, response, accessDeniedException) -> writeError(
                                response, objectMapper, HttpStatus.FORBIDDEN,
                                "You do not have permission to access this resource", request.getRequestURI()))
                )
                // H2 console renders its own UI in a frame; relax frame-options
                // for same-origin only (still blocks third-party framing).
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void writeError(HttpServletResponse response, ObjectMapper objectMapper, HttpStatus status,
                             String message, String path) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiErrorResponse body = new ApiErrorResponse(status.value(), status.getReasonPhrase(), message, path);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
