package io.webpserver.exception;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public class FileTooLargeException extends WebApplicationException {
    public FileTooLargeException(int maxSizeMb) {
        super(Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE)
                .entity(new ErrorResponse("File size exceeds the " + maxSizeMb + " MB limit."))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
