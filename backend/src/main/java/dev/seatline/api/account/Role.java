package dev.seatline.api.account;

/** What a signed-in user may do. Every route in SecurityConfig names the roles it allows. */
public enum Role {
    FAN,
    ORGANIZER,
    DOOR_STAFF,
    ADMIN
}
