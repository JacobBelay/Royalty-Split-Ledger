package com.jacobbelay.royalty.security;

/**
 * A row of {@code app_user}: an account that can sign in.
 *
 * <p>{@code collaboratorId} is the whole point of this record. A role alone cannot answer "may this request
 * read collaborator 7's balances", because every ARTIST has the same role; only the LINK from a user to a
 * collaborator can. V2__app_user.sql enforces that an ARTIST always has one and an ADMIN never does, so
 * {@link AccessPolicy} never has to handle a half-defined user.
 *
 * <p>{@code passwordHash} is BCrypt output, never a password. It is in this record because
 * {@link AuthService} needs it to verify a sign-in; it is never put in a response DTO.
 */
public record AppUser(long id, String username, String passwordHash, Role role, Long collaboratorId) {
}
