import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * A stand-in for ADS-B Exchange's stats endpoint (/api/receive/, what adsbexchange-stats posts
 * to), for the smoke test. It accepts the uploads, unzips them, and keeps a JSON summary up to
 * date after each one: how many arrived, the UUID in the header and in the body, the headers the
 * script sends, the most aircraft in one upload, and whether any carried the receiver distances.
 *
 * Usage: java FakeStatsServer.java <port> <summary.json> <seconds>
 * Binds 127.0.0.1; the Android emulator reaches it as 10.0.2.2.
 */
public class FakeStatsServer {
    static int uploads, maxAircraft, errors;
    static boolean anyRange, gzipHeader, formType;
    static final Set<String> headerUuids = new LinkedHashSet<>(), bodyUuids = new LinkedHashSet<>();
    static String error;

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        Path summary = Path.of(args[1]);
        long seconds = (long) Double.parseDouble(args[2]);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        Pattern uuid = Pattern.compile("\"uuid\"\\s*:\\s*\"([^\"]*)\"");
        Pattern aircraftItem = Pattern.compile("\\{\"hex\":");
        server.createContext("/api/receive/", ex -> {
            int status = 200;
            try (InputStream in = new GZIPInputStream(ex.getRequestBody())) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                synchronized (FakeStatsServer.class) {
                    uploads++;
                    String h = ex.getRequestHeaders().getFirst("adsbx-uuid");
                    if (h != null) headerUuids.add(h);
                    Matcher m = uuid.matcher(body);
                    if (m.find()) bodyUuids.add(m.group(1));
                    gzipHeader |= "gzip".equals(ex.getRequestHeaders().getFirst("Content_Encoding"));
                    formType |= "application/x-www-form-urlencoded".equals(ex.getRequestHeaders().getFirst("Content-Type"));
                    anyRange |= body.contains("\"r_dst\"");
                    int n = 0;
                    Matcher a = aircraftItem.matcher(body);
                    while (a.find()) n++;
                    maxAircraft = Math.max(maxAircraft, n);
                    write(summary);
                }
            } catch (Exception e) {
                status = 400;
                synchronized (FakeStatsServer.class) {
                    errors++;
                    error = String.valueOf(e);
                    write(summary);
                }
            }
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
        write(summary);
        Thread.sleep(seconds * 1000);
        server.stop(0);
    }

    static void write(Path summary) {
        String json = "{\"uploads\":" + uploads + ",\"errors\":" + errors
            + ",\"headerUuids\":" + list(headerUuids) + ",\"bodyUuids\":" + list(bodyUuids)
            + ",\"gzipHeader\":" + gzipHeader + ",\"formType\":" + formType
            + ",\"maxAircraft\":" + maxAircraft + ",\"anyRange\":" + anyRange
            + ",\"error\":" + (error == null ? "null" : "\"" + error.replace("\"", "'") + "\"") + "}";
        try {
            Path tmp = Path.of(summary + ".tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, summary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    static String list(Set<String> s) {
        StringBuilder b = new StringBuilder("[");
        for (String v : s) b.append(b.length() > 1 ? "," : "").append('"').append(v).append('"');
        return b.append(']').toString();
    }
}
