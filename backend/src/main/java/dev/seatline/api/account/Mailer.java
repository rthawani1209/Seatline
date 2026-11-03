package dev.seatline.api.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends account emails. For now they go to the outbox table, where development and tests read
 * them; a real sender (SES) picks them up from there in the deployment.
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
