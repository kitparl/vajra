package com.vajra.maryada.api;

import com.vajra.maryada.killswitch.KillSwitchService;
import com.vajra.maryada.killswitch.KillSwitchStatus;
import com.vajra.maryada.risk.KillSwitchEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/kill-switch")
class KillSwitchController {

    private static final int EVENT_LIMIT = 20;

    record ReasonRequest(@NotBlank @Size(max = 200) String reason) {}

    record KillSwitchView(boolean halted, List<KillSwitchEvent> events) {}

    private final KillSwitchService service;

    KillSwitchController(KillSwitchService service) {
        this.service = service;
    }

    @PostMapping
    KillSwitchStatus kill(@Valid @RequestBody ReasonRequest request) {
        return service.kill(request.reason());
    }

    @PostMapping("/resume")
    KillSwitchStatus resume(@Valid @RequestBody ReasonRequest request) {
        return service.resume(request.reason());
    }

    @GetMapping
    KillSwitchView status() {
        return new KillSwitchView(service.status().halted(), service.recentEvents(EVENT_LIMIT));
    }
}
