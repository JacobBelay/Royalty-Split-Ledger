package com.jacobbelay.royalty.security;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;

/**
 * Reads and writes {@code app_user}. Plain SQL via {@link JdbcClient}, matching the rest of the project
 * (see collaborator/CollaboratorRepository.java for the same shape and the reasoning in pom.xml).
 */
@Repository
public class AppUserRepository {

    private static final String COLUMNS = "id, username, password_hash, role, collaborator_id";

    private static final RowMapper<AppUser> MAPPER = (rs, rowNum) -> new AppUser(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            Role.valueOf(rs.getString("role")),
            nullableLong(rs, "collaborator_id"));

    /**
     * {@code ResultSet.getLong} turns SQL NULL into 0, which for a collaborator id would be a wrong answer
     * rather than a missing one, so we ask {@code wasNull()} and return a real null.
     */
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private final JdbcClient jdbc;

    public AppUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a user. A duplicate username hits the UNIQUE constraint and surfaces as Spring's
     * {@code DuplicateKeyException}, which api/GlobalExceptionHandler.java already turns into HTTP 409 —
     * so two admins racing to create the same username cannot produce two accounts.
     */
    public AppUser create(String username, String passwordHash, Role role, Long collaboratorId) {
        return jdbc.sql("INSERT INTO app_user (username, password_hash, role, collaborator_id) "
                        + "VALUES (:username, :hash, :role, :collaboratorId) RETURNING " + COLUMNS)
                .param("username", username)
                .param("hash", passwordHash)
                .param("role", role.name())
                // Types.BIGINT tells the driver the SQL type of a null, which it cannot infer from a Java null.
                .param("collaboratorId", collaboratorId, Types.BIGINT)
                .query(MAPPER)
                .single();
    }

    public Optional<AppUser> findByUsername(String username) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE username = :username")
                .param("username", username)
                .query(MAPPER)
                .optional();
    }

    public boolean anyUserExists() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user)").query(Boolean.class).single();
    }
}
