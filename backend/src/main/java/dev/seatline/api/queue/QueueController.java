package dev.seatline.api.queue;

import dev.seatline.api.web.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
class QueueController {

    private final WaitingRoom room;

    QueueController(WaitingRoom room) {
        this.room = room;
    }

    @PostMapping("/events/{id}/queue")
    WaitingRoom.Status join(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return room.join(id, CurrentUser.of(jwt).id());
    }

    /** Polled by the waiting room page every few seconds. */
    @GetMapping("/events/{id}/queue")
    WaitingRoom.Status status(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return room.status(id, CurrentUser.of(jwt).id());
    }
}
