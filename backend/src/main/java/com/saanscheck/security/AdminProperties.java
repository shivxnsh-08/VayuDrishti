package com.saanscheck.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The dedicated administrator account ({@code saanscheck.admin}, from ADMIN_EMAIL / ADMIN_PASSWORD in .env).
 *
 * @param email         the admin's sign-in email; empty = no admin is provisioned
 * @param password      the admin's initial password (hashed with BCrypt on creation; never stored or logged in clear)
 * @param required      true in production: a missing or weak admin configuration stops the application
 * @param passwordReset deliberate credential rotation: on the next start, set the admin's password to
 *                      {@code password}; remove the flag again afterwards
 */
@ConfigurationProperties(prefix = "saanscheck.admin")
public record AdminProperties(String email, String password, boolean required, boolean passwordReset) {

    public boolean configured() {
        return email != null && !email.isBlank();
    }

    /** Why the password is not acceptable for an administrator, or null if it is. */
    public static String weakness(String password, String email) {
        if (password == null || password.isBlank()) {
            return "ADMIN_PASSWORD is empty";
        }
        if (password.length() < 12) {
            return "ADMIN_PASSWORD must be at least 12 characters";
        }
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            return "ADMIN_PASSWORD must be at most 72 bytes (BCrypt limit)";
        }
        int classes = (password.chars().anyMatch(Character::isLowerCase) ? 1 : 0)
                + (password.chars().anyMatch(Character::isUpperCase) ? 1 : 0)
                + (password.chars().anyMatch(Character::isDigit) ? 1 : 0)
                + (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c)) ? 1 : 0);
        if (classes < 3) {
            return "ADMIN_PASSWORD must mix at least three of: lower case, upper case, digits, symbols";
        }
        String lower = password.toLowerCase(java.util.Locale.ROOT);
        if (email != null && !email.isBlank() && lower.contains(email.toLowerCase(java.util.Locale.ROOT).split("@")[0])) {
            return "ADMIN_PASSWORD must not contain the admin email name";
        }
        for (String common : new String[] {"password", "admin", "vayudrishti", "saanscheck", "123456", "qwerty"}) {
            if (lower.contains(common)) {
                return "ADMIN_PASSWORD must not contain common words such as '" + common + "'";
            }
        }
        return null;
    }
}
