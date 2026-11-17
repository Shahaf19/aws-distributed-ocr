package com.bgu.ocr.worker;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Downloads an image from the internet. Both timeouts keep a hung server from outlasting the task's visibility
 * timeout (Config.TASK_VISIBILITY_SECONDS), after which another Worker would take the same image.
 */
public final class ImageDownloader implements ImageSource {

    private final HttpClient http;
    private final Duration requestTimeout;

    public ImageDownloader(Duration connectTimeout, Duration requestTimeout) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.requestTimeout = requestTimeout;
    }

    /** 10 s to connect, 30 s for the whole download: well under the 120 s task visibility. */
    public static ImageDownloader withDefaults() {
        return new ImageDownloader(Duration.ofSeconds(10), Duration.ofSeconds(30));
    }

    /**
     * @throws IOException with a short, human-readable reason (it becomes the output's error line): invalid URL,
     *                     HTTP status other than 200, timeout, or bytes that aren't a supported image (e.g. WebP)
     */
    @Override
    public BufferedImage fetch(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(toHttpUri(url))
                .timeout(requestTimeout)
                .header("User-Agent", "Mozilla/5.0 (compatible; ocr-worker)")   // some hosts refuse Java's default
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
        if (image == null) {
            throw new IOException("unsupported or corrupt image format");
        }
        return image;
    }

    private static URI toHttpUri(String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid URL", e);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("unsupported URL scheme: " + uri.getScheme());
        }
        return uri;
    }
}
