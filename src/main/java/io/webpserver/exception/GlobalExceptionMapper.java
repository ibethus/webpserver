package io.webpserver.exception;

import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Throwable> {
        @Override
        public Response toResponse(Throwable exception) {
                Response.ResponseBuilder responseBuilder = switch (exception) {
                        case NotFoundException ignored -> Response.status(Response.Status.NOT_FOUND)
                                        .entity(new ErrorResponse("Not found."))
                                        .type(MediaType.APPLICATION_JSON);
                        case NotAllowedException ignored -> Response.status(Response.Status.NOT_ACCEPTABLE)
                                        .entity(new ErrorResponse("Not allowed."))
                                        .type(MediaType.APPLICATION_JSON);
                        case WebApplicationException wae -> Response.fromResponse(wae.getResponse())
                                        .type(MediaType.APPLICATION_JSON);
                        default -> Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                                        .entity(new ErrorResponse("Internal server error."))
                                        .type(MediaType.APPLICATION_JSON);
                };
                return responseBuilder.build();
        }
}
