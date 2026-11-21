package com.vajra.coreservice.api;

import com.vajra.coreservice.oms.KillResult;
import com.vajra.coreservice.oms.KillService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Called only by risk-service with CORE_INTERNAL_TOKEN ({@link InternalTokenFilter}). Not under /api. */
@RestController
class InternalController {

    record KillRequest(@NotBlank String reason) {}

    private final KillService killService;

    InternalController(KillService killService) {
        this.killService = killService;
    }

    @PostMapping("/internal/v1/kill")
    KillResult kill(@Valid @RequestBody KillRequest request) {
        return killService.kill(request.reason());
    }

    @PostMapping("/internal/v1/resume")
    ResponseEntity<Void> resume() {
        killService.resume();
        return ResponseEntity.noContent().build();
    }
}
