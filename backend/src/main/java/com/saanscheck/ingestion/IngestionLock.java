package com.saanscheck.ingestion;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Cluster-wide mutual exclusion for ingestion runs: a PostgreSQL session advisory lock held on one
 * dedicated connection for the whole run. A second run (same instance or another backend instance) does not
 * wait; it is skipped. If the process dies, PostgreSQL drops the connection and releases the lock, so a
 * crash can never leave ingestion locked.
 */
@Component
public class IngestionLock {

    /** Arbitrary constant, distinct from the ML lifecycle lock. */
    static final long KEY = 0x494E4745L;   // "INGE"

    private final JdbcTemplate jdbc;

    public IngestionLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Runs {@code work} while holding the lock; empty if another run holds it. */
    public <T> Optional<T> withLock(Supplier<T> work) {
        return jdbc.execute((ConnectionCallback<Optional<T>>) con -> {
            boolean locked;
            try (PreparedStatement ps = con.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    locked = rs.getBoolean(1);
                }
            }
            if (!locked) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(work.get());
            } finally {
                try (PreparedStatement ps = con.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    ps.setLong(1, KEY);
                    ps.execute();
                }
            }
        });
    }
}
