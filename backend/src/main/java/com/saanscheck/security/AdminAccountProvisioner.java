package com.saanscheck.security;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotent start-up provisioning of the dedicated administrator from ADMIN_EMAIL / ADMIN_PASSWORD, using the
 * same users table, BCrypt encoder and JWT login as everyone else (no second authentication system).
 * <ul>
 *   <li>No account with that email: create it with role ADMIN and a BCrypt hash of ADMIN_PASSWORD.</li>
 *   <li>Already an ADMIN: nothing changes - the password is NOT overwritten on restart. Rotation is deliberate:
 *       ADMIN_PASSWORD_RESET=true sets the new password once.</li>
 *   <li>The email belongs to an existing normal account: it is promoted only if ADMIN_PASSWORD matches that
 *       account's own password (proof of ownership). Otherwise nothing happens - another user's account is
 *       never taken over - and the operator is told to choose another email.</li>
 *   <li>Weak or missing configuration: logged and skipped; with ADMIN_REQUIRED=true (production) start-up fails.</li>
 * </ul>
 * Changing ADMIN_EMAIL creates/uses the new admin; a previous admin account is left as it is (never demoted
 * automatically, to avoid locking the operator out) and reported in the log. The password is never logged.
 */
@Component
public class AdminAccountProvisioner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountProvisioner.class);

    private final AdminProperties props;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;
    private final com.saanscheck.telegram.TelegramLinkService telegramLinks;

    public AdminAccountProvisioner(AdminProperties props, UserRepository users, PasswordEncoder encoder,
                                   TransactionTemplate tx, com.saanscheck.telegram.TelegramLinkService telegramLinks) {
        this.telegramLinks = telegramLinks;
        this.props = props;
        this.users = users;
        this.encoder = encoder;
        this.tx = tx;
    }

    public enum Outcome { NOT_CONFIGURED, REJECTED_WEAK_PASSWORD, CREATED, ALREADY_ADMIN, PASSWORD_ROTATED,
        PROMOTED_OWNER, REFUSED_EXISTING_USER }

    @Override
    public void run(ApplicationArguments args) {
        provision();
    }

    public Outcome provision() {
        if (!props.configured()) {
            if (props.required()) {
                throw new IllegalStateException("ADMIN_REQUIRED=true but ADMIN_EMAIL is not set");
            }
            log.warn("No dedicated admin account configured (ADMIN_EMAIL is empty); the admin dashboard is unavailable");
            return Outcome.NOT_CONFIGURED;
        }
        String email = props.email().trim().toLowerCase(Locale.ROOT);
        String weakness = AdminProperties.weakness(props.password(), email);
        if (weakness != null) {
            if (props.required()) {
                throw new IllegalStateException("Admin account not provisioned: " + weakness);
            }
            log.error("Admin account not provisioned: {}", weakness);
            return Outcome.REJECTED_WEAK_PASSWORD;
        }
        Outcome outcome;
        try {
            outcome = tx.execute(s -> provisionIn(email));
        } catch (DataIntegrityViolationException race) {
            outcome = Outcome.ALREADY_ADMIN;              // another instance created it at the same moment
        }
        unlinkTelegramFromAdmins();
        long otherAdmins = users.findAll().stream()
                .filter(u -> "ADMIN".equals(u.getRole()) && !u.getEmail().equals(email)).count();
        if (otherAdmins > 0) {
            log.warn("{} other account(s) also have the ADMIN role (e.g. a previous ADMIN_EMAIL or ADMIN_EMAILS); "
                    + "they were not changed", otherAdmins);
        }
        return outcome;
    }

    /**
     * Administrator accounts have no Telegram integration: any chat linked to an ADMIN account is removed with the
     * normal unlink mechanism (clears the chat id and pending link codes; sends nothing). Normal users are untouched;
     * notification history is kept.
     */
    private void unlinkTelegramFromAdmins() {
        int unlinked = 0;
        for (UserEntity u : users.findAll()) {
            if ("ADMIN".equals(u.getRole()) && telegramLinks.unlinkUser(u.getId())) {
                unlinked++;
            }
        }
        if (unlinked > 0) {
            log.warn("Telegram unlinked from {} administrator account(s): admins do not use Telegram", unlinked);
        }
    }

    private Outcome provisionIn(String email) {
        UserEntity existing = users.findByEmail(email).orElse(null);
        if (existing == null) {
            users.save(new UserEntity(email, encoder.encode(props.password()), "ADMIN"));
            log.info("Admin account created for the configured ADMIN_EMAIL");
            return Outcome.CREATED;
        }
        if ("ADMIN".equals(existing.getRole())) {
            if (props.passwordReset()) {
                existing.rotatePasswordHash(encoder.encode(props.password()));
                log.warn("Admin password rotated because ADMIN_PASSWORD_RESET=true - remove that flag now");
                return Outcome.PASSWORD_ROTATED;
            }
            log.info("Admin account already provisioned; password left unchanged");
            return Outcome.ALREADY_ADMIN;
        }
        if (encoder.matches(props.password(), existing.getPasswordHash())) {
            existing.provisionAsAdmin();
            log.warn("Existing account for ADMIN_EMAIL promoted to ADMIN (ADMIN_PASSWORD matched its own password)");
            return Outcome.PROMOTED_OWNER;
        }
        log.error("ADMIN_EMAIL belongs to an existing normal account whose password does not match ADMIN_PASSWORD; "
                + "it was NOT promoted. Use a dedicated admin email.");
        return Outcome.REFUSED_EXISTING_USER;
    }
}
