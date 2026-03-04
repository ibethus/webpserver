package io.webpserver.filter;

import io.webpserver.config.AppConfig;
import io.webpserver.exception.ErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Provider
@ApplicationScoped
public class RateLimitFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(RateLimitFilter.class);
    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_CF_CONNECTING_IP = "CF-Connecting-IP";
    private static final String ROOT_PATH = "/";
    private static final String UNKNOWN_IP = "unknown";
    private static final long WINDOW_MINUTE_MS = 60_000L;
    private static final long WINDOW_HOUR_MS = 3_600_000L;
    private static final long WINDOW_DAY_MS = 86_400_000L;

    private static final Map<String, IpBucket> BUCKETS = new ConcurrentHashMap<>();

    private final AppConfig config;

    @Inject
    public RateLimitFilter(AppConfig config) {
        this.config = config;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!HttpMethod.POST.equals(requestContext.getMethod())) {
            return;
        }

        if (!ROOT_PATH.equals(requestContext.getUriInfo().getPath())) {
            return;
        }

        String remoteAddr = requestContext.getHeaderString(HEADER_X_FORWARDED_FOR);
        if (remoteAddr == null || remoteAddr.isEmpty()) {
            remoteAddr = requestContext.getHeaderString(HEADER_CF_CONNECTING_IP);
        }
        if (remoteAddr == null || remoteAddr.isEmpty()) {
            remoteAddr = requestContext.getSecurityContext().getUserPrincipal() != null
                    ? requestContext.getSecurityContext().getUserPrincipal().getName()
                    : UNKNOWN_IP;
        }

        IpBucket bucket = BUCKETS.computeIfAbsent(remoteAddr, k -> new IpBucket());
        long now = System.currentTimeMillis();

        if (!bucket.isAllowed(now, config.maxUploadsPerMinute(), WINDOW_MINUTE_MS)) {
            LOG.warnf("Rate limit exceeded (per minute) for IP: %s", remoteAddr);
            requestContext.abortWith(Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity(new ErrorResponse("Rate limit exceeded: per minute."))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
            return;
        }

        if (!bucket.isAllowed(now, config.maxUploadsPerHour(), WINDOW_HOUR_MS)) {
            LOG.warnf("Rate limit exceeded (per hour) for IP: %s", remoteAddr);
            requestContext.abortWith(Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity(new ErrorResponse("Rate limit exceeded: per hour."))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
            return;
        }

        if (!bucket.isAllowed(now, config.maxUploadsPerDay(), WINDOW_DAY_MS)) {
            LOG.warnf("Rate limit exceeded (per day) for IP: %s", remoteAddr);
            requestContext.abortWith(Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity(new ErrorResponse("Rate limit exceeded: per day."))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
            return;
        }

        LOG.infof("Rate limit check passed for IP: %s", remoteAddr);
        bucket.recordRequest(now);
    }

    private static class IpBucket {
        private final Deque<Long> timestamps = new ArrayDeque<>();

        synchronized boolean isAllowed(long now, int limit, long windowMs) {
            evictOldTimestamps(now, windowMs);
            return timestamps.size() < limit;
        }

        synchronized void recordRequest(long now) {
            timestamps.addLast(now);
        }

        private void evictOldTimestamps(long now, long windowMs) {
            long cutoff = now - windowMs;
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.removeFirst();
            }
        }
    }
}
