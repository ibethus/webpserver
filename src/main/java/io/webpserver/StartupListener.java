package io.webpserver;

import org.jboss.logging.Logger;

import dev.matrixlab.webp4j.WebPCodec;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class StartupListener {

    private static final Logger LOG = Logger.getLogger(StartupListener.class);

    @Startup
    void displaySystem() {
        long maxMemMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        String arch = System.getProperty("os.arch");
        LOG.infof("RAM max heap : %d MB", maxMemMB);
        LOG.infof("Architecture : %s", arch);
        LOG.infof("libwebp native: %s", WebPCodec.isAvailable() ? "present" : "missing -> application will fail");
    }
}
