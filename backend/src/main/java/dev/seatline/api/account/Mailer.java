package dev.seatline.api.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends account and ticket emails by writing them to the outbox table, in the same transaction as
 * whatever they're about. {@link OutboxSender} delivers them when SMTP is set up; in development
 * and the tests they stay in the table to be read.
 */
@Component
public class Mailer {

    private final JdbcTemplate jdbc;

    public Mailer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void send(String to, String subject, String body) {
        jdbc.update("insert into outbox_emails (to_address, subject, body) values (?, ?, ?)", to, subject, body);
    }
}
