package com.bgu.ocr.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import javax.imageio.ImageIO;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Against a tiny HTTP server inside the test: no internet needed. */
public class ImageDownloaderTest {

    private HttpServer server;
    private String base;
    private final ImageDownloader downloader = new ImageDownloader(Duration.ofSeconds(2), Duration.ofSeconds(1));

    @Before
    public void startServer() throws IOException {
        byte[] png = pngBytes(40, 20);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok.png", exchange -> respond(exchange, 200, png));
        server.createContext("/missing.png", exchange -> respond(exchange, 404, new byte[0]));
        server.createContext("/junk.png", exchange -> respond(exchange, 200, "not an image".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/moved.png", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ok.png");
            respond(exchange, 302, new byte[0]);
        });
        server.createContext("/slow.png", exchange -> {
            try {
                Thread.sleep(3000);   // longer than the 1 s request timeout
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, png);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stopServer() {
        server.stop(0);
    }

    private static byte[] pngBytes(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private String failureOf(String url) throws InterruptedException {
        try {
            downloader.fetch(url);
            fail("expected an IOException for " + url);
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    @Test
    public void downloadsAnImage() throws Exception {
        BufferedImage image = downloader.fetch(base + "/ok.png");

        assertEquals(40, image.getWidth());
        assertEquals(20, image.getHeight());
    }

    @Test
    public void followsRedirects() throws Exception {
        assertEquals(40, downloader.fetch(base + "/moved.png").getWidth());
    }

    @Test
    public void httpErrorIsReportedWithItsStatus() throws Exception {
        assertEquals("HTTP 404", failureOf(base + "/missing.png"));
    }

    @Test
    public void bytesThatAreNotAnImageAreReported() throws Exception {
        assertEquals("unsupported or corrupt image format", failureOf(base + "/junk.png"));
    }

    @Test(timeout = 5000)
    public void hungServerTimesOut() throws Exception {
        assertTrue(failureOf(base + "/slow.png").toLowerCase().contains("timed out"));
    }

    @Test
    public void nonHttpUrlIsRejected() throws Exception {
        assertEquals("unsupported URL scheme: ftp", failureOf("ftp://example.com/a.png"));
        assertEquals("invalid URL", failureOf("http://bad url with spaces"));
    }
}
