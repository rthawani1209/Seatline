package dev.seatline.api.ticket;

import dev.seatline.api.web.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
class DoorController {

    record ScanRequest(@NotBlank @Size(max = 300) String code, UUID eventId) {}

    private final DoorService door;

    DoorController(DoorService door) {
        this.door = door;
    }

    /** The text to put in the ticket's QR code; the app draws it and fetches a new one every 15 s. */
    @GetMapping("/me/tickets/{id}/qr")
    Map<String, String> qr(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return Map.of("code", door.qrCode(id, CurrentUser.of(jwt)));
    }

    @PostMapping("/scan")
    DoorService.Scan scan(@Valid @RequestBody ScanRequest r) {
        return door.scan(r.code(), r.eventId());
    }
}
