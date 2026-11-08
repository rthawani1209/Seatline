package dev.seatline.api.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import dev.seatline.api.TestDatabase;
import dev.seatline.api.TestLedger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** With SMTP set up, the outbox is emptied into the mail server, and failures are tried again. */
@SpringBootTest(properties = {"spring.mail.host=smtp.test", "seatline.mail-from=Seatline <tickets@seatline.test>"})
@Import({TestDatabase.class, TestLedger.class})
class OutboxSenderTest {

    @Autowired OutboxSender sender;
    @Autowired Mailer mailer;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JavaMailSender mail;

    @Test
    void sendsEachEmailOnceAndRetriesOnesTheServerRefused() {
        jdbc.update("update outbox_emails set sent_at = now()"); // only this test's mail
        mailer.send("ok@example.com", "Hello", "body one");
        mailer.send("bounce@example.com", "Hello", "body two");
        doNothing().when(mail).send(any(SimpleMailMessage.class));
        doThrow(new MailSendException("server said no")).when(mail)
                .send(argThat((SimpleMailMessage m) -> m.getTo()[0].equals("bounce@example.com")));

        assertThat(sender.sendBatch(50)).isEqualTo(1);
        verify(mail).send(argThat((SimpleMailMessage m) -> m.getTo()[0].equals("ok@example.com")
                && "Seatline <tickets@seatline.test>".equals(m.getFrom()) && m.getText().equals("body one")));
        assertThat(jdbc.queryForObject("select sent_at is not null from outbox_emails where to_address = 'ok@example.com'",
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select attempts from outbox_emails where to_address = 'bounce@example.com'",
                Integer.class)).isEqualTo(1);

        // the sent one isn't sent again; the refused one is tried until it gives up
        for (int i = 0; i < OutboxSender.MAX_ATTEMPTS + 2; i++) {
            sender.sendBatch(50);
        }
        assertThat(jdbc.queryForObject("select attempts from outbox_emails where to_address = 'bounce@example.com'",
                Integer.class)).isEqualTo(OutboxSender.MAX_ATTEMPTS);
        verify(mail, org.mockito.Mockito.times(1))
                .send(argThat((SimpleMailMessage m) -> m.getTo()[0].equals("ok@example.com")));
    }
}
