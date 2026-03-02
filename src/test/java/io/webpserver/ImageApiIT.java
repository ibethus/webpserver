package io.webpserver;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@DisplayName("Image API Integration Tests")
public class ImageApiIT {

    private static final String API_KEY = "test-api-key";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$");
    private byte[] sampleJpg;
    private byte[] samplePng;
    private byte[] sampleGif;
    private byte[] sampleWebp;
    private byte[] invalidBin;

    @BeforeEach
    public void loadFixtures() throws IOException {
        sampleJpg = Files.readAllBytes(Paths.get("src/test/resources/fixtures/sample.jpg"));
        samplePng = Files.readAllBytes(Paths.get("src/test/resources/fixtures/sample.png"));
        sampleGif = Files.readAllBytes(Paths.get("src/test/resources/fixtures/sample.gif"));
        sampleWebp = Files.readAllBytes(Paths.get("src/test/resources/fixtures/sample.webp"));
        invalidBin = Files.readAllBytes(Paths.get("src/test/resources/fixtures/invalid.bin"));
    }

    @Test
    @DisplayName("POST / with JPEG multipart should return 201 with filename")
    public void testUploadJpegMultipart() {
        given()
                .multiPart("file", "sample.jpg", sampleJpg)
                .when()
                .post("/")
                .then()
                .statusCode(201)
                .contentType(ContentType.JSON)
                .body("filename", notNullValue())
                .body("filename", endsWith(".webp"));
    }

    @Test
    @DisplayName("POST / with PNG multipart should return 201 with filename")
    public void testUploadPngMultipart() {
        given()
                .multiPart("file", "sample.png", samplePng)
                .when()
                .post("/")
                .then()
                .statusCode(201)
                .body("filename", endsWith(".webp"));
    }

    @Test
    @DisplayName("POST / with GIF multipart should return 201 with filename")
    public void testUploadGifMultipart() {
        given()
                .multiPart("file", "sample.gif", sampleGif)
                .when()
                .post("/")
                .then()
                .statusCode(201)
                .body("filename", endsWith(".webp"));
    }

    @Test
    @DisplayName("POST / with WebP multipart should return 201 with filename")
    public void testUploadWebpMultipart() {
        given()
                .multiPart("file", "sample.webp", sampleWebp)
                .when()
                .post("/")
                .then()
                .statusCode(201)
                .body("filename", endsWith(".webp"));
    }

    @Test
    @DisplayName("POST / with invalid format should return 400")
    public void testUploadInvalidFormat() {
        given()
                .multiPart("file", "invalid.bin", invalidBin)
                .when()
                .post("/")
                .then()
                .statusCode(400)
                .body("error", containsString("not supported"));
    }

    @Test
    @DisplayName("POST / with oversized file should return 413")
    public void testUploadOversized() throws IOException {
        byte[] oversized = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(oversized, (byte) 0xFF);
        oversized[0] = (byte) 0xFF;
        oversized[1] = (byte) 0xD8;
        oversized[2] = (byte) 0xFF;
        
        given()
                .multiPart("file", "oversized.jpg", oversized)
                .when()
                .post("/")
                .then()
                .statusCode(413);
    }

    @Test
    @DisplayName("POST / JSON with valid URL should return 201")
    public void testUploadFromUrl() {
        given()
                .header("Authorization", "Bearer " + API_KEY)
                .contentType(ContentType.JSON)
                .body("{\"url\":\"https://httpbin.org/image/jpeg\"}")
                .when()
                .post("/")
                .then()
                .statusCode(201)
                .body("filename", notNullValue());
    }

    @Test
    @DisplayName("POST / JSON with invalid URL should return 422")
    public void testUploadFromInvalidUrl() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"url\":\"http://invalid-domain-that-does-not-exist-12345.example\"}")
                .when()
                .post("/")
                .then()
                .statusCode(422);
    }

    @Test
    @DisplayName("GET /{filename} should return 200 with WebP image")
    public void testGetImageOriginal() {
        Response uploadResp = given()
                .multiPart("file", "sample.jpg", sampleJpg)
                .post("/");
        String filename = uploadResp.path("filename");

        given()
                .when()
                .get("/" + filename)
                .then()
                .statusCode(200)
                .contentType("image/webp")
                .header("X-Cache", "MISS");
    }

    @Test
    @DisplayName("GET /{filename} second request should have cache HIT")
    public void testGetImageCacheHit() {
        Response uploadResp = given()
                .multiPart("file", "sample.png", samplePng)
                .post("/");
        String filename = uploadResp.path("filename");

        given()
                .when()
                .get("/" + filename)
                .then()
                .statusCode(200)
                .header("X-Cache", "MISS");

        given()
                .when()
                .get("/" + filename)
                .then()
                .statusCode(200)
                .header("X-Cache", "HIT");
    }

    @Test
    @DisplayName("GET /{filename}?w=100 should return resized image")
    public void testGetImageWithWidth() {
        Response uploadResp = given()
                .multiPart("file", "sample.png", samplePng)
                .post("/");
        String filename = uploadResp.path("filename");

        given()
                .queryParam("w", 100)
                .when()
                .get("/" + filename)
                .then()
                .statusCode(200)
                .contentType("image/webp")
                .header("X-Cache", "MISS");
    }

    @Test
    @DisplayName("GET /{filename}?w=100&h=100 should return resized image")
    public void testGetImageWithWidthAndHeight() {
        Response uploadResp = given()
                .multiPart("file", "sample.png", samplePng)
                .post("/");
        String filename = uploadResp.path("filename");

        given()
                .queryParam("w", 100)
                .queryParam("h", 100)
                .when()
                .get("/" + filename)
                .then()
                .statusCode(200)
                .contentType("image/webp");
    }

    @Test
    @DisplayName("GET /{filename}?w=invalid should return 400")
    public void testGetImageInvalidSize() {
        Response uploadResp = given()
                .multiPart("file", "sample.png", samplePng)
                .post("/");
        String filename = uploadResp.path("filename");

        given()
                .queryParam("w", 150)
                .when()
                .get("/" + filename)
                .then()
                .statusCode(400)
                .body("error", containsString("Size"));
    }

    @Test
    @DisplayName("GET /nonexistent.webp should return 404")
    public void testGetNonexistentImage() {
        given()
                .when()
                .get("/nonexistent-00000000-0000-0000-0000-000000000000.webp")
                .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("DELETE /{filename} without auth should return 401 if API_KEY set")
    public void testDeleteWithoutAuth() {
        Response uploadResp = given()
                .multiPart("file", "sample.png", samplePng)
                .post("/");
        String filename = uploadResp.path("filename");

        given()
                .when()
                .delete("/" + filename)
                .then()
                .statusCode(401);
    }

    @Test
    @DisplayName("DELETE /{filename} with valid Bearer token should return 200")
    public void testDeleteWithAuth() {
        Response uploadResp = given()
                .multiPart("file", "sample.png", samplePng)
                .post("/");
        String filename = uploadResp.path("filename");

        given()
                .header("Authorization", "Bearer " + API_KEY)
                .when()
                .delete("/" + filename)
                .then()
                .statusCode(200)
                .body("cached_files_removed", greaterThanOrEqualTo(0));
    }

    @Test
    @DisplayName("DELETE /{filename} with invalid Bearer token should return 401")
    public void testDeleteWithInvalidAuth() {
        Response uploadResp = given()
                .multiPart("file", "sample.png", samplePng)
                .post("/");
        String filename = uploadResp.path("filename");

        given()
                .header("Authorization", "Bearer invalid-token")
                .when()
                .delete("/" + filename)
                .then()
                .statusCode(401);
    }

    @Test
    @DisplayName("DELETE /nonexistent.webp should return 404")
    public void testDeleteNonexistentImage() {
        given()
                .header("Authorization", "Bearer " + API_KEY)
                .when()
                .delete("/nonexistent-00000000-0000-0000-0000-000000000000.webp")
                .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("GET /liveness should return 200")
    public void testLiveness() {
        given()
                .when()
                .get("/liveness")
                .then()
                .statusCode(200)
                .body("status", equalTo("ok"));
    }

    @Test
    @DisplayName("GET /{filename} should cache HIT after resize variant is created")
    public void testResizeAndCacheHit() {
        Response uploadResp = given()
                .multiPart("file", "sample.png", samplePng)
                .post("/");
        String filename = uploadResp.path("filename");

        given()
                .queryParam("w", 100)
                .when()
                .get("/" + filename)
                .then()
                .statusCode(200)
                .header("X-Cache", "MISS");

        given()
                .queryParam("w", 100)
                .when()
                .get("/" + filename)
                .then()
                .statusCode(200)
                .header("X-Cache", "HIT");
    }

    @Test
    @DisplayName("POST / should return valid UUID.webp filename format")
    public void testUploadFilenameFormat() {
        Response resp = given()
                .multiPart("file", "sample.jpg", sampleJpg)
                .post("/");

        String filename = resp.path("filename");
        String uuid = filename.replace(".webp", "");
        assertTrue(UUID_PATTERN.matcher(uuid).matches(),
                "Filename should be valid UUIDv4 with .webp extension");
    }
}
