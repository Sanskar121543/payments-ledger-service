package io.github.sanskar121543.payments.account;

import io.github.sanskar121543.payments.common.LedgerMetrics;
import io.github.sanskar121543.payments.idempotency.StoredResponse;
import io.github.sanskar121543.payments.ledger.EntryResponse;
import io.github.sanskar121543.payments.web.ApiKeyFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/accounts")
class AccountController {

    private final AccountService accountService;
    private final LedgerMetrics metrics;

    AccountController(AccountService accountService, LedgerMetrics metrics) {
        this.accountService = accountService;
        this.metrics = metrics;
    }

    @PostMapping
    ResponseEntity<String> create(@RequestAttribute(ApiKeyFilter.CLIENT_ID_ATTRIBUTE) String clientId,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 255) String idempotencyKey,
            @Valid @RequestBody CreateAccountRequest request) {
        StoredResponse response = accountService.create(clientId, idempotencyKey, request);
        metrics.write("account", response);
        return response.toResponseEntity();
    }

    @GetMapping
    List<AccountResponse> list() {
        return accountService.list().stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{id}")
    AccountResponse get(@PathVariable long id) {
        return AccountResponse.from(accountService.get(id));
    }

    @GetMapping("/{id}/entries")
    List<EntryResponse> entries(@PathVariable long id,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return accountService.entries(id, limit).stream().map(EntryResponse::from).toList();
    }
}
