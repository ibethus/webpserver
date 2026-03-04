package io.webpserver.exception;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public class RemoteUrlException extends WebApplicationException {
    public RemoteUrlException(String message) {
        this(message, Response.Status.BAD_REQUEST.getStatusCode());
    }

    public RemoteUrlException(String message, int status) {
        super(Response.status(status)
                .entity(new ErrorResponse(message))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
