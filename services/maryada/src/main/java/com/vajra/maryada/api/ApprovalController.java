package com.vajra.maryada.api;

import com.vajra.maryada.risk.ApprovalRequest;
import com.vajra.maryada.risk.ApprovalService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/approvals")
class ApprovalController {

    private final ApprovalService service;

    ApprovalController(ApprovalService service) {
        this.service = service;
    }

    @PostMapping
    ApprovalResponse approve(@Valid @RequestBody ApprovalRequest request) {
        return service.approve(request);
    }
}
