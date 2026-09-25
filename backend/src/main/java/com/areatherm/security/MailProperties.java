package com.areatherm.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code areatherm.mail.*} block in application.yml ({@code from},
 * {@code verification-code-expiry-minutes}). The actual SMTP transport
 * settings ({@code spring.mail.*} — host/port/username/password) are bound
 * separately by Spring Boot's own auto-configuration; this only covers the
 * AreaTherm-specific values {@link EmailService}/{@link AppUserService} need.
 */
@Component
@ConfigurationProperties(prefix = "areatherm.mail")
@Getter
@Setter
public class MailProperties {

    /** "From" address on outgoing mail — normally the same account as spring.mail.username. */
    private String from;

    /** How long a freshly issued verification code stays valid. */
    private long verificationCodeExpiryMinutes;

    /**
     * True once a real "from" address is set (i.e. {@code AREATHERM_MAIL_USERNAME}
     * is populated) -- used by {@link AppUserService} to decide whether issuing a
     * verification code makes sense at all. With no SMTP credentials configured,
     * {@link EmailService} would only ever log a swallowed send failure and the
     * user would be stuck with a code that never arrives, so registration skips
     * the code and auto-verifies instead.
     */
    public boolean isConfigured() {
        return from != null && !from.isBlank();
    }
}
