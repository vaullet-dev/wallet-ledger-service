package dev.vaullet.ledger.common.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The body of every list in this API, ADR-011 §8's shape: {@code {"data": [...], "next_cursor":
 * "..."}}.
 *
 * <p>{@code next_cursor} is left out on the last page — the platform's {@code non_null} inclusion —
 * so "is there more" is "is the key there". A page can hold fewer items than the limit and still not
 * be the last one; only the missing cursor says the list has ended.
 *
 * <p>The cursor is passed through as the service issued it. It is a keyset position, and only the
 * query that wrote it can read it back, so this layer treats it as opaque, exactly as a caller must.
 *
 * <p>Here rather than in a feature's {@code dto} package because the shape is a platform rule, not
 * a wallet one: the next list (an account's transactions, ADR-011's own example) answers with the
 * same body.
 */
public record ListResponse<T>(
        List<T> data,
        @Schema(
                        example = "djE6MTc5MTg4MjkwMDAwMDAwMDowYjlmM2MxZS01ZDJhLTRmOGItOWMzZC03ZTZmNWE0YjNjMmQ",
                        description = "Send as `cursor` for the next page. Absent on the last page.")
                @Nullable String nextCursor) {

    public static <S, T> ListResponse<T> of(List<S> items, @Nullable String nextCursor, Function<S, T> toResponse) {
        return new ListResponse<>(items.stream().map(toResponse).toList(), nextCursor);
    }
}
