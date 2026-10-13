package dev.vaullet.ledger.wallet.api.dto;

import dev.vaullet.ledger.wallet.service.WalletSearch;
import dev.vaullet.ledger.wallet.service.WalletStatus;
import dev.vaullet.ledger.wallet.service.WalletType;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.BindParam;

/**
 * The query of {@code GET /v1/wallets}: which wallets, and which page.
 *
 * <p>One bound object rather than five {@code @RequestParam}s, so a bad value fails on a model
 * attribute — which the platform handler answers as {@code VALIDATION_FAILED} with an {@code errors}
 * array, the same body a bad JSON field gets. A bare request parameter that does not convert is
 * answered by Spring's own handler, without the {@code code} a client branches on. {@code type} and
 * {@code status} are strings with a pattern for the same reason, as in {@link OpenWalletRequest}.
 *
 * <p>Query parameters are snake_case like the JSON (ADR-011 §8), but the platform's naming strategy
 * only reaches bodies, so {@code account_id} is named twice: {@code @BindParam} for the binding,
 * {@code @Parameter} for the OpenAPI document.
 *
 * <p>An empty value is the same as leaving the parameter out: {@code ?type=} asks for every type,
 * not for wallets of no type.
 *
 * @param cursor the {@code next_cursor} of the previous page. Opaque, and only the service reads it;
 *     the size bound is a guard against junk, with room above the 75 characters a cursor takes today.
 */
public record WalletSearchParameters(
        @BindParam("account_id")
                @Parameter(name = "account_id", description = "Only this account's wallets",
                        example = "7e57a11c-0000-4000-8000-000000000001")
                @Nullable UUID accountId,
        @Pattern(regexp = "REAL|BONUS", message = "must be REAL or BONUS")
                @Schema(allowableValues = {"REAL", "BONUS"}, example = "REAL", description = "Only wallets of this type")
                @Nullable String type,
        @Pattern(regexp = "ACTIVE|FROZEN|CLOSED", message = "must be ACTIVE, FROZEN or CLOSED")
                @Schema(allowableValues = {"ACTIVE", "FROZEN", "CLOSED"}, example = "ACTIVE",
                        description = "Only wallets in this status")
                @Nullable String status,
        @Min(1)
                @Max(WalletSearch.MAX_LIMIT)
                @Schema(example = "50", defaultValue = "50", description = "How many wallets at most. Up to 100.")
                @Nullable Integer limit,
        @Size(max = 128)
                @Schema(example = "djE6MTc5MTg4MjkwMDAwMDAwMDowYjlmM2MxZS01ZDJhLTRmOGItOWMzZC03ZTZmNWE0YjNjMmQ",
                        description = "The next_cursor of the previous page. Omit for the first page.")
                @Nullable String cursor) {

    public WalletSearchParameters {
        type = blankToNull(type);
        status = blankToNull(status);
        cursor = blankToNull(cursor);
    }

    /** The search as the service takes it, with the default page size filled in. */
    public WalletSearch toSearch() {
        return new WalletSearch(
                accountId,
                type == null ? null : WalletType.valueOf(type),
                status == null ? null : WalletStatus.valueOf(status),
                limit == null ? WalletSearch.DEFAULT_LIMIT : limit,
                cursor);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
