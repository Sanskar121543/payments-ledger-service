package io.github.sanskar121543.payments.transfer;

import io.github.sanskar121543.payments.common.LedgerMetrics;
import io.github.sanskar121543.payments.idempotency.StoredResponse;
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
@RequestMapping("/v1/transfers")
class TransferController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final TransferService transferService;
    private final TransferQueries transferQueries;
    private final LedgerMetrics metrics;

    TransferController(TransferService transferService, TransferQueries transferQueries, LedgerMetrics metrics) {
        this.transferService = transferService;
        this.transferQueries = transferQueries;
        this.metrics = metrics;
    }

    @PostMapping
    ResponseEntity<String> create(@RequestAttribute(ApiKeyFilter.CLIENT_ID_ATTRIBUTE) String clientId,
            @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 1, max = 255) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        StoredResponse response = transferService.transfer(clientId, idempotencyKey, request);
        metrics.write("transfer", response);
        return response.toResponseEntity();
    }

    @PostMapping("/{id}/reversals")
    ResponseEntity<String> reverse(@RequestAttribute(ApiKeyFilter.CLIENT_ID_ATTRIBUTE) String clientId,
            @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 1, max = 255) String idempotencyKey,
            @PathVariable long id) {
        StoredResponse response = transferService.reverse(clientId, idempotencyKey, id);
        metrics.write("reversal", response);
        return response.toResponseEntity();
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
