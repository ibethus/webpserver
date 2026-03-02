package io.webpserver.exception;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public class InvalidSizeException extends WebApplicationException {
    public InvalidSizeException(int requestedSize) {
        super(Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse("Requested size " + requestedSize + " is not in the list of valid sizes."))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
