package io.github.sanskar121543.payments.transfer;

import io.github.sanskar121543.payments.web.Problems;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/transfers")
class TransferController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final TransferService transferService;
    private final TransferQueries transferQueries;

    TransferController(TransferService transferService, TransferQueries transferQueries) {
        this.transferService = transferService;
        this.transferQueries = transferQueries;
    }

    @PostMapping
    ResponseEntity<?> create(@RequestHeader(IDEMPOTENCY_KEY) @Size(min = 1, max = 255) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        return switch (transferService.transfer(request)) {
            case TransferOutcome.Completed completed -> ResponseEntity
                    .created(URI.create("/v1/transfers/" + completed.transfer().getId()))
                    .body(TransferResponse.of(completed.transfer(), completed.entries()));
            case TransferOutcome.Failed failed -> {
                ProblemDetail problem = Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient-funds",
                        "Insufficient funds", "Account " + failed.transfer().getFromAccountId()
                                + " cannot cover " + failed.transfer().getAmountCents() + " cents");
                problem.setProperty("transferId", failed.transfer().getId());
                problem.setProperty("status", failed.transfer().getStatus());
                yield ResponseEntity.unprocessableContent().body(problem);
            }
            case TransferOutcome.AccountNotFound missing -> ResponseEntity.unprocessableContent()
                    .body(Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "account-not-found", "Unknown account",
                            "Account " + missing.accountId() + " does not exist"));
        };
    }

    @GetMapping("/{id}")
    TransferResponse get(@PathVariable long id) {
        return transferQueries.get(id);
    }

    @GetMapping
    List<TransferResponse> recent(@RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit) {
        return transferQueries.recent(limit);
    }
}
