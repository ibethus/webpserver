package io.webpserver.service;

import dev.matrixlab.webp4j.WebPCodec;
import io.webpserver.config.AppConfig;
import io.webpserver.exception.UnsupportedFormatException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

@ApplicationScoped
public class ConversionService {

    private static final Logger LOG = Logger.getLogger(ConversionService.class);
    private static final String FORMAT_JPEG = "jpeg";
    private static final String FORMAT_PNG = "png";
    private static final String FORMAT_GIF = "gif";
    private static final String FORMAT_WEBP = "webp";

    private final AppConfig config;

    @Inject
    public ConversionService(AppConfig config) {
        this.config = config;
    }

    public String detectFormat(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            throw new UnsupportedFormatException();
        }

        if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) {
            LOG.debug("Detected format: jpeg");
            return FORMAT_JPEG;
        }

        if (bytes.length >= 8
                && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A) {
            LOG.debug("Detected format: png");
            return FORMAT_PNG;
        }

        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            LOG.debug("Detected format: gif");
            return FORMAT_GIF;
        }

        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            LOG.debug("Detected format: webp");
            return FORMAT_WEBP;
        }

        LOG.warnf("Unsupported format, first bytes: %02x %02x %02x %02x",
                bytes[0] & 0xFF, bytes[1] & 0xFF, bytes[2] & 0xFF, bytes[3] & 0xFF);
        throw new UnsupportedFormatException();
    }

    public byte[] toWebP(byte[] inputBytes, String format) throws IOException {
        LOG.infof("Converting %s to WebP (%d bytes input)", format, inputBytes.length);
        if (FORMAT_GIF.equals(format)) {
            if (config.webpLossless()) {
                return WebPCodec.encodeGifToWebPLossless(inputBytes);
            } else {
                return WebPCodec.encodeGifToWebP(inputBytes);
            }
        }

        BufferedImage img;
        if (FORMAT_WEBP.equals(format)) {
            return inputBytes;
        } else {
            img = ImageIO.read(new ByteArrayInputStream(inputBytes));
            if (img == null) {
                throw new UnsupportedFormatException();
            }
        }

        return encodeBufferedImage(img);
    }

    public byte[] encodeBufferedImage(BufferedImage img) throws IOException {
        if (config.webpLossless()) {
            return WebPCodec.encodeLosslessImage(img);
        } else {
            return WebPCodec.encodeImage(img, config.webpQuality());
        }
    }

    public BufferedImage decodeWebP(byte[] webpBytes) throws IOException {
        return WebPCodec.decodeImage(webpBytes);
    }

    public boolean isAnimated(byte[] webpBytes) {
        if (webpBytes == null || webpBytes.length < 12) {
            return false;
        }

        if (!(webpBytes[0] == 'R' && webpBytes[1] == 'I' && webpBytes[2] == 'F' && webpBytes[3] == 'F'
                && webpBytes[8] == 'W' && webpBytes[9] == 'E' && webpBytes[10] == 'B' && webpBytes[11] == 'P')) {
            return false;
        }

        int offset = 12;
        while (offset + 8 <= webpBytes.length) {
            String fourCC = new String(webpBytes, offset, 4);
            int sizeLE = ((webpBytes[offset + 4] & 0xFF)
                    | ((webpBytes[offset + 5] & 0xFF) << 8)
                    | ((webpBytes[offset + 6] & 0xFF) << 16)
                    | ((webpBytes[offset + 7] & 0xFF) << 24));

            if ("ANIM".equals(fourCC)) {
                return true;
            }

            if ("VP8 ".equals(fourCC) || "VP8L".equals(fourCC)) {
                return false;
            }

            int chunkSize = sizeLE + 8;
            if (chunkSize % 2 != 0) {
                chunkSize++;
            }
            offset += chunkSize;
        }

        return false;
    }

    public BufferedImage resize(BufferedImage src, int targetWidth, int targetHeight) {
        if (targetWidth <= 0 && targetHeight <= 0) {
            return src;
        }

        int srcWidth = src.getWidth();
        int srcHeight = src.getHeight();
        int finalWidth, finalHeight;

        if (targetWidth > 0 && targetHeight > 0) {
            double srcAspect = (double) srcWidth / srcHeight;
            double targetAspect = (double) targetWidth / targetHeight;

            int scaledWidth, scaledHeight;
            if (srcAspect > targetAspect) {
                scaledHeight = targetHeight;
                scaledWidth = (int) (targetHeight * srcAspect);
            } else {
                scaledWidth = targetWidth;
                scaledHeight = (int) (targetWidth / srcAspect);
            }

            Image scaled = src.getScaledInstance(scaledWidth, scaledHeight, Image.SCALE_SMOOTH);
            int type = src.getType() == BufferedImage.TYPE_INT_RGB ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
            BufferedImage scaledImg = new BufferedImage(scaledWidth, scaledHeight, type);
            Graphics2D g2d = scaledImg.createGraphics();
            g2d.drawImage(scaled, 0, 0, null);
            g2d.dispose();

            int offsetX = (scaledWidth - targetWidth) / 2;
            int offsetY = (scaledHeight - targetHeight) / 2;
            BufferedImage cropped = new BufferedImage(targetWidth, targetHeight, type);
            Graphics2D g2dCropped = cropped.createGraphics();
            g2dCropped.drawImage(scaledImg, -offsetX, -offsetY, null);
            g2dCropped.dispose();

            return cropped;
        } else if (targetWidth > 0) {
            finalWidth = targetWidth;
            finalHeight = (int) (targetWidth * (double) srcHeight / srcWidth);
        } else {
            finalHeight = targetHeight;
            finalWidth = (int) (targetHeight * (double) srcWidth / srcHeight);
        }

        int type = src.getType() == BufferedImage.TYPE_INT_RGB ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
        Image scaled = src.getScaledInstance(finalWidth, finalHeight, Image.SCALE_SMOOTH);
        BufferedImage result = new BufferedImage(finalWidth, finalHeight, type);
        Graphics2D g2d = result.createGraphics();
        g2d.drawImage(scaled, 0, 0, null);
        g2d.dispose();

        return result;
    }
}
