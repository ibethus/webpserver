package io.webpserver.filter;

import io.webpserver.config.AppConfig;
import io.webpserver.exception.ErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

@Provider
@ApplicationScoped
public class AuthFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(AuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROOT_PATH = "/";

    private final AppConfig config;

    @Inject
    public AuthFilter(AppConfig config) {
        this.config = config;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (config.apiKey().isEmpty()) {
            LOG.debug("Auth disabled: no API key configured");
            return;
        }

        String method = requestContext.getMethod();
        String path = requestContext.getUriInfo().getPath();

        if (HttpMethod.DELETE.equals(method)) {
            LOG.debugf("Auth required: DELETE %s", path);
            validateBearer(requestContext);
            return;
        }

        if (HttpMethod.POST.equals(method) && ROOT_PATH.equals(path) && config.requireApiKeyForUpload()) {
            LOG.debugf("Auth required: POST %s (require-api-key-for-upload=true)", path);
            validateBearer(requestContext);
        }
    }

    private void validateBearer(ContainerRequestContext requestContext) {
        String authHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);

        if (authHeader == null) {
            LOG.warn("Rejected request: missing Authorization header");
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Missing Authorization header."))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
            return;
        }

        if (!authHeader.startsWith(BEARER_PREFIX)) {
            LOG.warn("Rejected request: malformed Authorization header");
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Invalid Authorization header format."))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
            return;
        }

        String providedToken = authHeader.substring(BEARER_PREFIX.length());
        String expectedToken = config.apiKey().get();

        if (!providedToken.equals(expectedToken)) {
            LOG.warn("Rejected request: invalid API key");
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Invalid API key."))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
            return;
        }

        LOG.debug("Request authorized successfully");
    }
}
