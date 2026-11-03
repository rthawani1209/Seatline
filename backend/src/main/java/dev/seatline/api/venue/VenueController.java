package dev.seatline.api.venue;

import dev.seatline.api.web.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
class VenueController {

    record RowRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9]{1,3}") String label,
                      @Min(1) @Max(80) int seats, Set<Integer> accessible, Set<Integer> limitedView) {}

    record SectionRequest(@NotBlank @Size(max = 30) String name, @NotEmpty @Size(max = 60) List<@Valid RowRequest> rows) {}

    record VenueRequest(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 60) String city,
                        @NotEmpty @Size(max = 30) List<@Valid SectionRequest> sections) {}

    private final VenueService venues;

    VenueController(VenueService venues) {
        this.venues = venues;
    }

    @PostMapping("/venues")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VenueRequest r) {
        List<VenueService.SectionSpec> sections = r.sections().stream()
                .map(s -> new VenueService.SectionSpec(s.name(), s.rows().stream()
                        .map(row -> new VenueService.RowSpec(row.label(), row.seats(),
                                row.accessible() == null ? Set.of() : row.accessible(),
                                row.limitedView() == null ? Set.of() : row.limitedView()))
                        .toList()))
                .toList();
        return Map.of("id", venues.create(r.name(), r.city(), sections, CurrentUser.of(jwt).id()));
    }

    @GetMapping("/venues/{id}")
    VenueService.Venue get(@PathVariable UUID id) {
        return venues.get(id);
    }
}
