package dev.seatline.api.ticket;

import dev.seatline.api.web.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
class DoorController {

    record ScanRequest(@NotBlank @Size(max = 300) String code, @NotNull UUID eventId) {}

    record StaffRequest(@NotBlank @Email @Size(max = 254) String email) {}

    private final DoorService door;

    DoorController(DoorService door) {
        this.door = door;
    }

    /** The text to put in the ticket's QR code; the app draws it and fetches a new one every 15 s. */
    @GetMapping("/me/tickets/{id}/qr")
    Map<String, String> qr(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return Map.of("code", door.qrCode(id, CurrentUser.of(jwt)));
    }

    /** Scans happen at one event: door staff only at the events they're on the door team for. */
    @PostMapping("/scan")
    DoorService.Scan scan(@Valid @RequestBody ScanRequest r, @AuthenticationPrincipal Jwt jwt) {
        return door.scan(r.code(), r.eventId(), CurrentUser.of(jwt));
    }

    @GetMapping("/door/events")
    List<DoorService.DoorEvent> doorEvents(@AuthenticationPrincipal Jwt jwt) {
        return door.doorEvents(CurrentUser.of(jwt));
    }

    @GetMapping("/events/{id}/door-staff")
    List<DoorService.StaffMember> staff(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return door.staff(id, CurrentUser.of(jwt));
    }

    @PostMapping("/events/{id}/door-staff")
    @ResponseStatus(HttpStatus.CREATED)
    DoorService.StaffMember addStaff(@PathVariable UUID id, @Valid @RequestBody StaffRequest r,
                                     @AuthenticationPrincipal Jwt jwt) {
        return door.addStaff(id, CurrentUser.of(jwt), r.email());
    }

    @DeleteMapping("/events/{id}/door-staff/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeStaff(@PathVariable UUID id, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        door.removeStaff(id, CurrentUser.of(jwt), userId);
    }
}
