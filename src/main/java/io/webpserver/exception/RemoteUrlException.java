package io.webpserver.exception;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public class RemoteUrlException extends WebApplicationException {

    private static final int HTTP_UNPROCESSABLE_ENTITY = 422;

    public RemoteUrlException(String message) {
        super(Response.status(HTTP_UNPROCESSABLE_ENTITY)
                .entity(new ErrorResponse(message))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
