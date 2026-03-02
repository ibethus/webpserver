package io.webpserver.resource;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirements;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/liveness")
@Tag(name = "health")
public class LivenessResource {

    @Schema(name = "LivenessResponse")
    public record LivenessResponse(
        @Schema(description = "Service status.", example = "ok", enumeration = {"ok"})
        String status) {}

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    @Operation(
        operationId = "liveness",
        summary = "Liveness probe",
        description = "Returns HTTP 200 unconditionally. Used by Docker healthcheck and Kubernetes liveness probes. Never requires authentication."
    )
    @SecurityRequirements
    @APIResponse(
        responseCode = "200",
        description = "Service is alive.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = LivenessResponse.class))
    )
    public Response liveness() {
        return Response.ok(new LivenessResponse("ok")).build();
    }
}
