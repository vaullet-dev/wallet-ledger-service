package io.vaullet.ledger.common.error;

import java.io.Serial;

/** Thrown when a resource addressed by the caller does not exist. Maps to 404. */
public class ResourceNotFoundException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String resource, Object identifier) {
        super(ErrorType.RESOURCE_NOT_FOUND, "%s '%s' was not found".formatted(resource, identifier));
    }
}
