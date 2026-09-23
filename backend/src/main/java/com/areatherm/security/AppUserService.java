package com.areatherm.security;

import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and lookup for {@link AppUser}, plus the
 * {@link UserDetailsService} the JWT filter uses to turn a validated
 * token's email back into an authenticated principal. Password hashing
 * lives here (not in the controller) so {@code AppUser.passwordHash} is
 * never set from a raw password anywhere else.
 */
@Service
public class AppUserService implements UserDetailsService {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    public AppUserService(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * @param role defaults to {@link AppUser.UserRole#ENGINEER} (matching the
     *             {@code app_user.role} column's own default) when null.
     * @throws EmailAlreadyRegisteredException if {@code email} is already taken --
     *         checked up front via {@link AppUserRepository#existsByEmail}, and
     *         also caught around the save itself so a concurrent registration
     *         racing past that check still surfaces as this exception rather
     *         than a raw {@link DataIntegrityViolationException} from the
     *         table's unique constraint.
     */
    @Transactional
    public AppUser register(String email, String displayName, String rawPassword, AppUser.UserRole role) {
        if (appUserRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }

        AppUser user = new AppUser();
        user.setEmail(email);
        user.setDisplayName(displayName);
        user.setRole(role != null ? role : AppUser.UserRole.ENGINEER);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));

        try {
            return appUserRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            throw new EmailAlreadyRegisteredException(email, ex);
        }
    }

    @Transactional(readOnly = true)
    public Optional<AppUser> findByEmail(String email) {
        return appUserRepository.findByEmail(email);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        AppUser user = appUserRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("No user registered with email " + email));
        return new AppUserPrincipal(user);
    }
}
