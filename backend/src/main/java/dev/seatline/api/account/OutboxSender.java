package dev.seatline.api.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sends what {@link Mailer} put in the outbox, through any SMTP service (Amazon SES, Resend, ...).
 * Only runs when SEATLINE_SMTP_HOST is set; without it, emails stay in the table where development
 * and the tests read them. A message that fails is tried again on later rounds, up to 10 times.
 * With several API servers two could send the same message at once; "for update skip locked"
 * makes each one take different rows.
 */
@Component
@ConditionalOnExpression("!'${spring.mail.host:}'.isBlank()")
class OutboxSender {

    private static final Logger log = LoggerFactory.getLogger(OutboxSender.class);
    static final int MAX_ATTEMPTS = 10;

    private final JdbcTemplate jdbc;
    private final JavaMailSender mail;
    private final String from;

    OutboxSender(JdbcTemplate jdbc, JavaMailSender mail, @Value("${seatline.mail-from}") String from) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.from = from;
    }

    record Email(long id, String to, String subject, String body) {}

    @Scheduled(fixedDelay = 3_000, initialDelay = 3_000)
    void sendSome() {
        try {
            sendBatch(50);
        } catch (RuntimeException e) {
            log.warn("sending emails failed this round, will retry: {}", e.toString());
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public int sendBatch(int max) {
        List<Email> batch = jdbc.query("""
                select id, to_address, subject, body from outbox_emails
                where sent_at is null and attempts < ? order by id limit ? for update skip locked""",
                (rs, n) -> new Email(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                MAX_ATTEMPTS, max);
        int sent = 0;
        for (Email e : batch) {
            try {
                SimpleMailMessage m = new SimpleMailMessage();
                m.setFrom(from);
                m.setTo(e.to());
                m.setSubject(e.subject());
                m.setText(e.body());
                mail.send(m);
                jdbc.update("update outbox_emails set sent_at = now(), attempts = attempts + 1 where id = ?", e.id());
                sent++;
            } catch (RuntimeException failed) {
                jdbc.update("update outbox_emails set attempts = attempts + 1 where id = ?", e.id());
                log.warn("email {} to the mail server failed: {}", e.id(), failed.toString());
            }
        }
        return sent;
    }
}
