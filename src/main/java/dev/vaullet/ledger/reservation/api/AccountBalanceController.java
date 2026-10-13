package dev.vaullet.ledger.reservation.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import dev.vaullet.ledger.reservation.api.dto.BalanceResponse;
import dev.vaullet.ledger.reservation.service.LedgerService;
import dev.vaullet.ledger.wallet.service.WalletService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read side of the ledger — ADR-004's {@code /v1/accounts/{id}/balance}.
 *
 * <p>A separate controller from {@code ReservationController} because it is a separate resource:
 * the path is rooted at an account, and folding it into the reservations controller would give that
 * class two {@code @RequestMapping} roots and no single thing it is about. Both live in the same
 * slice, which is the boundary that actually matters.
 *
 * <p>This endpoint is advisory and says so. A balance read outside a transaction is stale the
 * instant it is returned, and ADR-001 failed precisely because a caller treated one as a decision.
 * The only place the balance invariant is decided is {@code POST /v1/reservations}.
 *
 * <p>The account's totals come from {@code LedgerService} and the per-wallet figures from
 * {@code WalletService}, in separate reads. Under concurrent holds the two can disagree by whatever
 * moved in between — one more reason nobody decides anything on this response. The totals are read
 * first because that call is the one that answers 404 for an account the ledger has never seen;
 * {@code balancesOf} answers it with an empty list.
 */
@Validated
@RestController
@RequestMapping("/v1/accounts")
@Tag(name = "Balances", description = "Read an account's posted, held, available and withdrawable money")
class AccountBalanceController {

    private final LedgerService ledger;
    private final WalletService wallets;

    AccountBalanceController(LedgerService ledger, WalletService wallets) {
        this.ledger = ledger;
        this.wallets = wallets;
    }

    @GetMapping("/{accountId}/balance")
    @PreAuthorize("hasAuthority('SCOPE_ledger:read')")
    @Operation(
            summary = "Read an account balance",
            description = "Advisory only. Never branch on this to decide whether funds are sufficient — "
                    + "the value is stale the moment it is serialised. Call POST /v1/reservations and "
                    + "handle INSUFFICIENT_FUNDS instead. available and withdrawable count only what a hold could "
                    + "draw on (ACTIVE wallets, grants not past their expiry); wallets lists each wallet's "
                    + "totals, and GET /v1/wallets/{id}/balance its buckets.")
    @ApiResponse(responseCode = "200", description = "The account's totals and each wallet's")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
    BalanceResponse balance(@PathVariable UUID accountId) {
        LedgerService.Balance totals = ledger.balance(accountId);
        return BalanceResponse.from(totals, wallets.currency(), wallets.balancesOf(accountId));
    }
}
