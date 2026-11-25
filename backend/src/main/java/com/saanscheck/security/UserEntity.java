package com.saanscheck.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** The one JPA entity: plain CRUD with no geo or time-series concerns. */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 16)
    private String role = "USER";

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected UserEntity() {
    }

    public UserEntity(String email, String passwordHash, String role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    void markLogin(Instant at) {
        this.lastLoginAt = at;
    }

    /** Only the startup admin provisioner changes roles and passwords; no API does. */
    void provisionAsAdmin() {
        this.role = "ADMIN";
    }

    void rotatePasswordHash(String hash) {
        this.passwordHash = hash;
    }
}
