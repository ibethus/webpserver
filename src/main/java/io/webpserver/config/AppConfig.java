package io.webpserver.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "webpserver")
public interface AppConfig {

    @WithDefault("/images")
    String imagesDir();

    Optional<String> apiKey();

    @WithDefault("false")
    boolean requireApiKeyForUpload();

    @WithDefault("16")
    double maxSizeMb();

    Optional<List<Integer>> validSizes();

    @WithDefault("20")
    int maxUploadsPerMinute();

    @WithDefault("100")
    int maxUploadsPerHour();

    @WithDefault("1000")
    int maxUploadsPerDay();

    @WithDefault("75.0")
    float webpQuality();

    @WithDefault("false")
    boolean webpLossless();
}
