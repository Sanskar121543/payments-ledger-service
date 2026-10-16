package io.github.sanskar121543.payments.ledger;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InvariantController {

    private final InvariantService invariantService;

    InvariantController(InvariantService invariantService) {
        this.invariantService = invariantService;
    }

    @GetMapping("/v1/ledger/invariants")
    InvariantReport check() {
        return invariantService.check();
    }
}
