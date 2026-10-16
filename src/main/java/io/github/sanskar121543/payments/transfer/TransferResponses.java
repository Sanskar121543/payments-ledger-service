package io.github.sanskar121543.payments.transfer;

import io.github.sanskar121543.payments.idempotency.ResponseFactory;
import io.github.sanskar121543.payments.idempotency.StoredResponse;
import io.github.sanskar121543.payments.ledger.LedgerEntry;
import io.github.sanskar121543.payments.web.Problems;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
class TransferResponses {

    private final ResponseFactory responses;

    TransferResponses(ResponseFactory responses) {
        this.responses = responses;
    }

    StoredResponse created(Transfer transfer, List<LedgerEntry> entries) {
        return responses.json(HttpStatus.CREATED.value(), TransferResponse.of(transfer, entries, null));
    }

    StoredResponse insufficientFunds(Transfer transfer) {
        ProblemDetail problem = Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient-funds",
                "Insufficient funds", "Account " + transfer.getFromAccountId() + " cannot cover "
                        + transfer.getAmountCents() + " cents");
        problem.setProperty("transferId", transfer.getId());
        // Not "status": that member is reserved for the HTTP status code in RFC 9457.
        problem.setProperty("transferStatus", transfer.getStatus());
        return responses.problem(problem);
    }

    StoredResponse accountNotFound(long accountId) {
        return responses.problem(Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "account-not-found",
                "Unknown account", "Account " + accountId + " does not exist"));
    }

    StoredResponse transferNotFound(long transferId) {
        return responses.problem(Problems.of(HttpStatus.NOT_FOUND, "transfer-not-found", "Not found",
                "Transfer " + transferId + " does not exist"));
    }

    StoredResponse notReversible(Transfer transfer) {
        return responses.problem(Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "not-reversible",
                "Transfer cannot be reversed", "Only COMPLETED transfers can be reversed; transfer "
                        + transfer.getId() + " is a " + transfer.getStatus() + " " + transfer.getKind()));
    }

    StoredResponse alreadyReversed(Transfer transfer) {
        return responses.problem(Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, "already-reversed",
                "Transfer already reversed", "Transfer " + transfer.getId() + " already has a completed reversal"));
    }
}
