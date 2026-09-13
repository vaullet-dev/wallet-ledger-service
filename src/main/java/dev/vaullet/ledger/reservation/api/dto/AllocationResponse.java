package dev.vaullet.ledger.reservation.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import dev.vaullet.ledger.reservation.service.LedgerService;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * How much of a hold a single bucket funds.
 *
 * <p>Returned rather than summed away because the split is the answer to a question callers
 * genuinely ask: an 80 stake funded by 60 of bonus and 20 of cash has different withdrawability and
 * different wagering consequences than the same 80 taken from cash alone (ADR-004). A caller that
 * only ever reads the total can ignore the array; a caller that shows a user why their withdrawable
 * balance moved cannot reconstruct it from one number.
 */
@Schema(name = "Allocation", description = "One bucket's contribution to a hold")
public record AllocationResponse(
        UUID bucketId,
        @Schema(example = "BONUS") String bucketType,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "60.00") BigDecimal amount) {

    static AllocationResponse from(LedgerService.Allocation allocation) {
        return new AllocationResponse(allocation.bucketId(), allocation.bucketType(), allocation.amount());
    }
}
