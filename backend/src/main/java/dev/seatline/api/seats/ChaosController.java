package dev.seatline.api.seats;

import dev.seatline.api.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The chaos panel: watch the ledger nodes and break them on purpose. Admins only. */
@RestController
class ChaosController {

    private final LedgerAdmin admin;

    ChaosController(LedgerAdmin admin) {
        this.admin = admin;
    }

    @GetMapping("/admin/ledger")
    List<LedgerAdmin.Node> nodes() {
        return on().nodes();
    }

    @PostMapping("/admin/ledger/{id}/crash")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void crash(@PathVariable int id) {
        on().crash(id);
    }

    @PostMapping("/admin/ledger/{id}/revive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revive(@PathVariable int id) {
        on().revive(id);
    }

    @PostMapping("/admin/ledger/{id}/cut-off")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cutOff(@PathVariable int id) {
        on().cutOff(id);
    }

    @PostMapping("/admin/ledger/heal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void heal() {
        on().healAll();
    }

    private LedgerAdmin on() {
        if (!admin.enabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "chaos_off");
        }
        return admin;
    }
}
