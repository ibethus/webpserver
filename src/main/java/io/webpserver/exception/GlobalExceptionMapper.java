package io.webpserver.exception;

import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionMapper.class);

    @Override
    public Response toResponse(Throwable exception) {
        if (exception instanceof NotFoundException || exception instanceof NotAllowedException) {
            // Treat both "path not found" and "method not allowed" as 404
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("Not found."))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }

        if (exception instanceof WebApplicationException wae) {
            // Typed exceptions thrown by service/resource code carry their own status — pass through
            LOG.debugf("WebApplicationException: HTTP %d — %s", wae.getResponse().getStatus(), exception.getMessage());
            return wae.getResponse();
        }

        LOG.errorf(exception, "Unhandled exception: %s", exception.getMessage());
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new ErrorResponse("Internal server error."))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
