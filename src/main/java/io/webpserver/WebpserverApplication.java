package io.webpserver;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.servers.Server;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@ApplicationPath("/")
@OpenAPIDefinition(
    info = @Info(
        title = "webpserver",
        version = "1.0.0",
        description = """
            Self-hosted minimalist image service.

            All images are converted to WebP on upload and served in WebP format.
            Resized variants are generated on demand and cached on disk.
            Animated GIFs are converted to animated WebP.

            ## Authentication

            Authentication uses a static Bearer token configured via the `API_KEY` environment variable.

            - Upload: requires Bearer token only if `REQUIRE_API_KEY_FOR_UPLOAD=true`.
            - Delete: always requires Bearer token if `API_KEY` is set.
            - Get / Liveness: always public.

            ## Cache behaviour

            A GET request with `w` and/or `h` parameters generates a resized variant.
            The variant is written to disk as `{uuid}_{w}x{h}.webp` and served from cache on subsequent requests.
            The in-memory index is rebuilt from disk on startup.
            """
    ),
    servers = @Server(url = "http://localhost:8080", description = "Local development"),
    tags = {
        @Tag(name = "images", description = "Image upload, retrieval and deletion"),
        @Tag(name = "health",  description = "Service health")
    }
)
@SecurityScheme(
    securitySchemeName = "BearerAuth",
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    description = "Static API key configured via the `API_KEY` environment variable. Pass as `Authorization: Bearer <key>`."
)
public class WebpserverApplication extends Application {}
