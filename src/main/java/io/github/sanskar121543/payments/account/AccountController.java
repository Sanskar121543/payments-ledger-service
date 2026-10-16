package io.github.sanskar121543.payments.account;

import io.github.sanskar121543.payments.ledger.EntryResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/accounts")
class AccountController {

    private final AccountService accountService;

    AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.create(request.name());
        return ResponseEntity.created(URI.create("/v1/accounts/" + account.getId()))
                .body(AccountResponse.from(account));
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
