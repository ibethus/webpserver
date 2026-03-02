package io.webpserver.resource;

import java.io.IOException;
import java.nio.file.Files;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirements;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.PartType;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import io.smallrye.common.annotation.RunOnVirtualThread;
import io.webpserver.exception.ErrorResponse;
import io.webpserver.service.ImageService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
@Tag(name = "images")
public class ImageResource {

    private static final String HEADER_X_CACHE = "X-Cache";
    private static final String X_CACHE_HIT = "HIT";
    private static final String X_CACHE_MISS = "MISS";
    private static final String CACHE_CONTROL_IMMUTABLE = "public, max-age=31536000, immutable";
    private static final String CACHE_CONTROL_SHORT = "public, max-age=3600";

    private final ImageService imageService;

    @Inject
    public ImageResource(ImageService imageService) {
        this.imageService = imageService;
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    @Operation(operationId = "uploadImageMultipart", summary = "Upload an image", description = """
            Accepts an image via multipart form upload (`file` field). \
            The image is validated, converted to WebP, stored on disk and indexed in memory.

            Accepted formats: **JPEG**, **PNG**, **WebP**, **GIF** (converted to animated WebP).

            Auth is required only if `REQUIRE_API_KEY_FOR_UPLOAD=true`.""")
    @SecurityRequirements({
            @SecurityRequirement(name = ""),
            @SecurityRequirement(name = "BearerAuth")
    })
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Image successfully uploaded and converted.", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ImageService.UploadResult.class))),
            @APIResponse(responseCode = "400", description = "Unsupported image format or file exceeds size limit.", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @APIResponse(responseCode = "429", description = "Upload rate limit exceeded.", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @APIResponse(responseCode = "500", description = "Internal error during conversion or storage.", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
    })
    public Response uploadMultipart(MultipartBody body) throws IOException {
        byte[] bytes = Files.readAllBytes(body.file.filePath());
        ImageService.UploadResult result = imageService.upload(bytes);
        return Response.status(Response.Status.CREATED).entity(result).build();
    }

    @POST
    @Path("from-url")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    @Operation(operationId = "uploadImageFromUrl", summary = "Upload an image from URL", description = """
            Fetches the image at the given URL, converts it to WebP, stores it on disk and indexes it.

            Accepted formats: **JPEG**, **PNG**, **WebP**, **GIF** (converted to animated WebP).

            Auth is required only if `REQUIRE_API_KEY_FOR_UPLOAD=true`.""")
    @SecurityRequirements({
            @SecurityRequirement(name = ""),
            @SecurityRequirement(name = "BearerAuth")
    })
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Image successfully fetched and converted.", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ImageService.UploadResult.class))),
            @APIResponse(responseCode = "400", description = "Missing or empty `url` field, or unsupported image format.", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @APIResponse(responseCode = "422", description = "URL unreachable, returns non-2xx, or content is not a supported image format.", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @APIResponse(responseCode = "429", description = "Upload rate limit exceeded.", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @APIResponse(responseCode = "500", description = "Internal error during conversion or storage.", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
    })
    public Response uploadFromUrl(UploadUrlRequest body) throws IOException {
        if (body == null || body.url() == null || body.url().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Missing 'url' field."))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
        ImageService.UploadResult result = imageService.uploadFromUrl(body.url());
        return Response.status(Response.Status.CREATED).entity(result).build();
    }

    @GET
    @Path("{filename:.+}")
    @Produces("image/webp")
    @RunOnVirtualThread
    @Operation(operationId = "getImage", summary = "Retrieve an image", description = """
            Returns the image identified by `filename`.

            - **No `w`/`h`**: returns the original WebP.
            - **With `w` and/or `h`**: returns a resized variant (cover crop when both given, \
            proportional when only one is given). Variants are generated on first request and cached on disk.

            The `VALID_SIZES` configuration restricts which values are accepted for `w` and `h`.

            Animated WebP files (converted from GIF) cannot be resized — returns HTTP 400.""")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Image content.", headers = {
                    @Header(name = "X-Cache", description = "`HIT` if served from cache (in-memory index or disk), `MISS` if generated on this request.", schema = @Schema(type = SchemaType.STRING, enumeration = {
                            "HIT", "MISS" })),
                    @Header(name = "Cache-Control", description = "`public, max-age=31536000, immutable` for cached variants; `public, max-age=3600` for originals.", schema = @Schema(type = SchemaType.STRING))
            }, content = @Content(mediaType = "image/webp", schema = @Schema(type = SchemaType.STRING, format = "binary"))),
            @APIResponse(responseCode = "400", description = "Size not in `VALID_SIZES`, or resize requested on an animated WebP.", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @APIResponse(responseCode = "404", description = "Image not found.", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @APIResponse(responseCode = "500", description = "Internal error during decoding, resizing or encoding.", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
    })
    public Response getImage(
            @PathParam("filename") @Parameter(name = "filename", in = ParameterIn.PATH, required = true, description = "Filename returned by the upload endpoint. Must be `{uuidv4}.webp`.", example = "3f2a1b4c-8e7d-4f6a-9b2c-1d3e5f7a9b0c.webp", schema = @Schema(type = SchemaType.STRING, pattern = "^[a-f0-9\\-]{36}\\.webp$")) String filename,
            @QueryParam("w") @Parameter(name = "w", in = ParameterIn.QUERY, description = "Target width in pixels.", schema = @Schema(type = SchemaType.INTEGER, minimum = "1", maximum = "10000")) Integer w,
            @QueryParam("h") @Parameter(name = "h", in = ParameterIn.QUERY, description = "Target height in pixels.", schema = @Schema(type = SchemaType.INTEGER, minimum = "1", maximum = "10000")) Integer h)
            throws IOException {
        ImageService.ServeResult result = imageService.serveImage(filename, w, h);
        String cacheHeader = result.cacheHit() ? X_CACHE_HIT : X_CACHE_MISS;
        String cacheControl = result.cacheHit() ? CACHE_CONTROL_IMMUTABLE : CACHE_CONTROL_SHORT;
        return Response.ok(result.bytes())
                .header(HEADER_X_CACHE, cacheHeader)
                .header(HttpHeaders.CACHE_CONTROL, cacheControl)
                .build();
    }

    @DELETE
    @Path("{filename:.+}")
    @Produces(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    @Operation(operationId = "deleteImage", summary = "Delete an image and all its cached variants", description = """
            Deletes the original image and all resized variants from disk. \
            The entry is removed from the in-memory index.

            Authentication is always required if `API_KEY` is set.""")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Image and all variants successfully deleted.", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ImageService.DeleteResult.class))),
            @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @APIResponse(responseCode = "404", description = "Image not found.", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))),
            @APIResponse(responseCode = "500", description = "Unexpected error during deletion.", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
    })
    public Response deleteImage(
            @PathParam("filename") @Parameter(name = "filename", in = ParameterIn.PATH, required = true, description = "Filename returned by the upload endpoint.", example = "3f2a1b4c-8e7d-4f6a-9b2c-1d3e5f7a9b0c.webp", schema = @Schema(type = SchemaType.STRING, pattern = "^[a-f0-9\\-]{36}\\.webp$")) String filename)
            throws IOException {
        ImageService.DeleteResult result = imageService.deleteImage(filename);
        return Response.ok(result).build();
    }

    @Schema(name = "UploadUrlRequest")
    public record UploadUrlRequest(
            @Schema(description = "Publicly accessible URL of the image to fetch and store.", example = "https://example.com/photo.jpg", format = "uri") String url) {
    }

    public static class MultipartBody {
        @RestForm("file")
        @PartType(MediaType.APPLICATION_OCTET_STREAM)
        public FileUpload file;
    }
}
