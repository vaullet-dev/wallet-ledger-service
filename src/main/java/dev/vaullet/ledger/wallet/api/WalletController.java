package dev.vaullet.ledger.wallet.api;

import dev.vaullet.ledger.common.api.ListResponse;
import dev.vaullet.ledger.wallet.api.dto.OpenWalletRequest;
import dev.vaullet.ledger.wallet.api.dto.UpdateWalletStatusRequest;
import dev.vaullet.ledger.wallet.api.dto.WalletBalanceResponse;
import dev.vaullet.ledger.wallet.api.dto.WalletResponse;
import dev.vaullet.ledger.wallet.api.dto.WalletSearchParameters;
import dev.vaullet.ledger.wallet.service.WalletPage;
import dev.vaullet.ledger.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * HTTP entry point for wallets, {@code /v1/wallets}: open one, find it, freeze it, close it, and read
 * its money.
 *
 * <p>A wallet groups ADR-004's buckets; it does not replace them. {@code REAL} holds the account's
 * cash, {@code BONUS} its promotional grants, and an account has at most one of each. Reservations
 * still allocate across buckets — a wallet only decides whether its buckets may be spent from.
 *
 * <p>Translation only, like {@code ReservationController}: bind and validate, call one
 * {@link WalletService} method, shape the response. No rules, no transaction, no try/catch. The
 * failures this resource adds ({@code WalletClosedException}, {@code WalletNotEmptyException}, and
 * {@code ResourceNotFoundException} for an unknown id) are {@code ApplicationException}s, so the
 * platform handler turns them into problem documents with nothing added to {@code LedgerErrorBridge}.
 *
 * <p>The verbs follow the resource: opening is {@code POST}, the freeze is {@code PATCH} of the
 * status, and closing is {@code DELETE}. A closed wallet is not deleted — its row and buckets stay,
 * because the journal points at them — but it is gone as far as spending, freezing and reopening are
 * concerned, which is what {@code DELETE} promises a caller.
 *
 * <p>The {@code @PreAuthorize} rules sit here, on the controller, for the reason
 * {@code ReservationController} gives: it is this service's convention until the rules move down onto
 * the service methods together. Reads need {@code ledger:read}; everything that changes a wallet needs
 * {@code ledger:write}.
 */
@Validated
@RestController
@RequestMapping("/v1/wallets")
@Tag(name = "Wallets", description = "An account's money containers — REAL for cash, BONUS for promotional grants")
class WalletController {

    private final WalletService wallets;

    WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_ledger:write')")
    @Operation(
            summary = "Open a wallet",
            description = "Opens the account's wallet of this type, and the account itself if this is its "
                    + "first wallet. A REAL wallet starts with its CASH bucket; a BONUS wallet starts with no "
                    + "buckets, which grants add. An account has at most one wallet of each type, so repeating "
                    + "the request returns the same wallet — no Idempotency-Key needed.")
    @ApiResponse(responseCode = "201", description = "Opened, or the account's existing wallet of that type returned")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED")
    @ApiResponse(
            responseCode = "409",
            description = "WALLET_CLOSED — the account's wallet of that type is closed, and closing is terminal")
    ResponseEntity<WalletResponse> open(@Valid @RequestBody OpenWalletRequest request, UriComponentsBuilder uriBuilder) {
        WalletService.Opened opened = wallets.open(request.accountId(), request.walletType());

        URI location = uriBuilder
                .path("/v1/wallets/{id}")
                .buildAndExpand(opened.wallet().walletId())
                .toUri();

        // 201 whether or not this call created the wallet, like POST /v1/reservations and
        // auth-service's POST /v1/accounts: a caller's retry must behave like its first attempt, so
        // opened.created() stays off the wire.
        return ResponseEntity.created(location).body(WalletResponse.from(opened.wallet()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_ledger:read')")
    @Operation(
            summary = "Search wallets",
            description = "Wallets matching every filter given, oldest first, a page at a time. Send next_cursor "
                    + "back as cursor for the next page; a page without next_cursor is the last.")
    @ApiResponse(responseCode = "200", description = "One page. next_cursor is absent on the last.")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_FAILED — a filter value that does not exist, a limit outside 1 to 100, "
                    + "or a cursor this service did not issue")
    ListResponse<WalletResponse> search(@Valid @ParameterObject WalletSearchParameters query) {
        WalletPage page = wallets.search(query.toSearch());
        return ListResponse.of(page.data(), page.nextCursor(), WalletResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_ledger:read')")
    @Operation(summary = "Fetch a wallet", description = "Its type and status. Its money is on its balance.")
    @ApiResponse(responseCode = "200", description = "The wallet")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
    WalletResponse get(@PathVariable UUID id) {
        return WalletResponse.from(wallets.get(id));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_ledger:write')")
    @Operation(
            summary = "Freeze or unfreeze a wallet",
            description = "FROZEN keeps the money where it is and stops it being spent: reservations allocate "
                    + "around the wallet, and its available and withdrawable read zero. ACTIVE lifts the freeze. "
                    + "Setting the status the wallet already has changes nothing, so a retry is safe. Closing "
                    + "is DELETE.")
    @ApiResponse(responseCode = "200", description = "The wallet, in the status asked for")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED — status missing, unknown, or CLOSED")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "WALLET_CLOSED — a closed wallet stays closed")
    WalletResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody UpdateWalletStatusRequest request) {
        return WalletResponse.from(wallets.changeStatus(id, request.walletStatus()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('SCOPE_ledger:write')")
    @Operation(
            summary = "Close a wallet",
            description = "Only when it is empty: nothing posted, nothing held, no debt. Closing is terminal — "
                    + "the wallet cannot be frozen, unfrozen or reopened, and the account cannot open another "
                    + "of its type. Its record stays, because the journal points at it. Closing a closed "
                    + "wallet changes nothing, so a retry is safe.")
    @ApiResponse(responseCode = "204", description = "Closed, or already closed")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "WALLET_NOT_EMPTY — it still has posted money, a hold or a debt")
    void close(@PathVariable UUID id) {
        wallets.close(id);
    }

    @GetMapping("/{id}/balance")
    @PreAuthorize("hasAuthority('SCOPE_ledger:read')")
    @Operation(
            summary = "Read a wallet balance",
            description = "Advisory only, like every balance read: stale the moment it is serialised. Never "
                    + "branch on it to decide whether funds are sufficient — call POST /v1/reservations and "
                    + "handle INSUFFICIENT_FUNDS. available and withdrawable are zero unless the wallet is "
                    + "ACTIVE; buckets lists every bucket, CASH first, then by spend priority.")
    @ApiResponse(responseCode = "200", description = "The wallet's totals and buckets")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
    WalletBalanceResponse balance(@PathVariable UUID id) {
        return WalletBalanceResponse.from(wallets.balance(id));
    }
}
