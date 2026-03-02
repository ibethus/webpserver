package io.webpserver.exception;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public class ImageNotFoundException extends WebApplicationException {
    public ImageNotFoundException() {
        super(Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse("Image not found."))
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
