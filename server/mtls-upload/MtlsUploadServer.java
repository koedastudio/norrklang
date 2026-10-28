import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Receives Norrklang mTLS certificate requests (spec: specs/mtls/protocol.md §4).
 *
 * <p>GET:  {@code /?csr=<PKCS#10 PEM>} (URL-encoded)
 * <p>POST: {@code /} with the PKCS#10 PEM as a {@code text/plain} body
 *
 * <p>Each CSR is validated (PKCS#10 shape, EC P-256) and saved as-is under
 * the data directory as {@code <thumbprint>.csr}, where thumbprint is the
 * SHA-256 hex of the CSR's SubjectPublicKeyInfo — stable across
 * regenerations, unlike the raw CSR bytes (ECDSA signatures vary). A signer
 * (specs/mtls/sign-csr.sh) drops the signed certificate into the same
 * directory as {@code <thumbprint>.crt}, which this server then serves at
 * {@code GET /<thumbprint>.crt}.
 *
 * <p>Successful uploads answer 200 with the thumbprint; started with
 * {@code -location <base>}, the response also carries
 * {@code Location: <base>/<thumbprint>.crt}. Every request is appended to
 * {@code requests.jsonl}.
 *
 * <p>Started with {@code --crt <file>}, the root CA certificate is served
 * at {@code GET /ca.crt}.
 *
 * <p>Runs from the JDK alone: {@code java MtlsUploadServer.java [port]}
 */
public final class MtlsUploadServer {

    private static final Pattern PEM_BODY = Pattern.compile(
            "-----BEGIN [A-Z0-9 ]+-----(.*?)-----END [A-Z0-9 ]+-----", Pattern.DOTALL);
    private static final Pattern SERVABLE = Pattern.compile("^[0-9a-f]{64}\\.(crt|csr)$");
    private static final byte[] OID_EC_PUBLIC_KEY = hex("06072a8648ce3d0201");
    private static final byte[] OID_P256 = hex("06082a8648ce3d030107");

    private final Path dataDir;
    private final String location;
    private final Path caCert;

    private MtlsUploadServer(Path dataDir, String location, Path caCert) {
        this.dataDir = dataDir;
        this.location = location;
        this.caCert = caCert;
    }

    public static void main(String[] args) throws IOException {
        int port = 8080;
        Path dataDir = Path.of("data");
        String location = null;
        Path caCert = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-location" -> location = args[++i];
                case "-data-dir" -> dataDir = Path.of(args[++i]);
                case "--crt" -> caCert = Path.of(args[++i]);
                default -> port = Integer.parseInt(args[i]);
            }
        }
        if (caCert != null && !Files.isRegularFile(caCert)) {
            System.err.println("--crt: no such file: " + caCert.toAbsolutePath());
            System.exit(1);
            return;
        }
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mtls-upload");
            t.setDaemon(true);
            return t;
        }));
        MtlsUploadServer server = new MtlsUploadServer(dataDir, location, caCert);
        http.createContext("/", server::handle);
        try {
            Files.createDirectories(dataDir);
            http.start();
        } catch (BindException e) {
            System.err.println("port " + port + " is already in use");
            System.exit(1);
            return;
        }
        System.out.println("mtls-upload listening on http://localhost:" + port
                + " (data dir: " + dataDir.toAbsolutePath() + ")");
    }

    private void handle(HttpExchange ex) {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        try {
            if ("GET".equals(method) && "/health".equals(path)) {
                respond(ex, 200, "ok\n");
                return;
            }
            if ("GET".equals(method) && "/ca.crt".equals(path)) {
                serveCa(ex);
                return;
            }
            if ("GET".equals(method) && SERVABLE.matcher(path.substring(1)).matches()) {
                serve(ex, path.substring(1));
                return;
            }
            if ("GET".equals(method)) {
                String csr = queryParam(ex.getRequestURI().getRawQuery(), "csr");
                if (csr == null || csr.isBlank()) {
                    appendLog(method, path, null, null, null, "missing 'csr' query parameter");
                    respond(ex, 400, "missing 'csr' query parameter\n");
                    return;
                }
                upload(ex, method, path, csr);
            } else if ("POST".equals(method)) {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (body.isBlank()) {
                    appendLog(method, path, null, null, body, "empty body");
                    respond(ex, 400, "empty body\n");
                    return;
                }
                upload(ex, method, path, body);
            } else {
                appendLog(method, path, null, null, null, "method not allowed");
                respond(ex, 405, "method not allowed\n");
            }
        } catch (IllegalArgumentException e) {
            respondQuietly(ex, 400, e.getMessage() + "\n");
        } catch (IOException e) {
            respondQuietly(ex, 500, "write failed: " + e.getMessage() + "\n");
        } catch (Exception e) {
            respondQuietly(ex, 500, "internal error\n");
        } finally {
            ex.close();
        }
    }

    private void upload(HttpExchange ex, String method, String path, String csr)
            throws IOException {
        String thumbprint = null;
        try {
            byte[] spki = spkiOfCsr(pemToDer(csr));
            requireP256(spki);
            thumbprint = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(spki));
            Path csrFile = dataDir.resolve(thumbprint + ".csr");
            Files.writeString(csrFile, csr.strip() + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IllegalArgumentException e) {
            appendLog(method, path, null, null, csr, e.getMessage());
            respond(ex, 400, e.getMessage() + "\n");
            return;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        if (location != null) {
            String base = location.endsWith("/")
                    ? location.substring(0, location.length() - 1) : location;
            ex.getResponseHeaders().add("Location", base + "/" + thumbprint + ".crt");
        }
        appendLog(method, path, thumbprint, thumbprint + ".csr", csr, null);
        respond(ex, 200, "ok " + thumbprint + "\n");
    }

    private void serveCa(HttpExchange ex) {
        if (caCert == null) {
            respondQuietly(ex, 404, "not found\n");
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(caCert);
            ex.getResponseHeaders().add("Content-Type", "application/x-pem-file");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            respondQuietly(ex, 500, "read failed\n");
        }
    }

    private void serve(HttpExchange ex, String name) {
        Path f = dataDir.resolve(name);
        if (!Files.isRegularFile(f)) {
            respondQuietly(ex, 404, "not found\n");
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(f);
            ex.getResponseHeaders().add("Content-Type", "application/x-pem-file");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            respondQuietly(ex, 500, "read failed\n");
        }
    }

    private synchronized void appendLog(String method, String path, String thumbprint,
            String file, String csr, String error) throws IOException {
        StringBuilder json = new StringBuilder("{\"ts\":\"").append(Instant.now().toString())
                .append("\",\"method\":\"").append(jsonEscape(method))
                .append("\",\"path\":\"").append(jsonEscape(path))
                .append("\",\"thumbprint\":").append(thumbprint == null ? "null" : '"' + thumbprint + '"')
                .append(",\"file\":").append(file == null ? "null" : '"' + jsonEscape(file) + '"')
                .append(",\"csr\":").append(csr == null ? "null" : '"' + jsonEscape(csr) + '"')
                .append(",\"error\":").append(error == null ? "null" : '"' + jsonEscape(error) + '"')
                .append("}\n");
        Files.writeString(dataDir.resolve("requests.jsonl"), json,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Extracts the SubjectPublicKeyInfo from a PKCS#10 DER encoding. */
    private static byte[] spkiOfCsr(byte[] der) {
        try {
            if ((der[0] & 0xff) != 0x30) throw new IllegalArgumentException("not a PKCS#10 CSR");
            int p = headerEnd(der, 0);
            if ((der[p] & 0xff) != 0x30) throw new IllegalArgumentException("not a PKCS#10 CSR");
            p = headerEnd(der, p);
            p = skip(der, p);   // version
            p = skip(der, p);   // subject
            int spkiStart = p;
            if ((der[p] & 0xff) != 0x30) throw new IllegalArgumentException("not a PKCS#10 CSR");
            int spkiEnd = headerEnd(der, p) + contentLen(der, p);
            return Arrays.copyOfRange(der, spkiStart, spkiEnd);
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("not a PKCS#10 CSR");
        }
    }

    /** Checks the SPKI is an EC P-256 key: SEQUENCE { SEQUENCE { OID, curve OID }, BIT STRING }. */
    private static void requireP256(byte[] spki) {
        if ((spki[0] & 0xff) != 0x30) throw new IllegalArgumentException("not a PKCS#10 CSR");
        int p = headerEnd(spki, 0);
        if ((spki[p] & 0xff) != 0x30) throw new IllegalArgumentException("not an EC CSR");
        p = headerEnd(spki, p);
        if (!hasPrefix(spki, p, OID_EC_PUBLIC_KEY)) throw new IllegalArgumentException("not an EC CSR");
        p = skip(spki, p);
        if (!hasPrefix(spki, p, OID_P256)) throw new IllegalArgumentException("not a P-256 CSR");
        p = skip(spki, p);
        if ((spki[p] & 0xff) != 0x03) throw new IllegalArgumentException("not an EC CSR");
    }

    private static int headerEnd(byte[] d, int off) {
        int lenByte = d[off + 1] & 0xff;
        return lenByte < 0x80 ? off + 2 : off + 2 + (lenByte & 0x7f);
    }

    private static int contentLen(byte[] d, int off) {
        int lenByte = d[off + 1] & 0xff;
        if (lenByte < 0x80) return lenByte;
        int n = lenByte & 0x7f;
        int len = 0;
        for (int i = 0; i < n; i++) len = (len << 8) | (d[off + 2 + i] & 0xff);
        return len;
    }

    private static int skip(byte[] d, int off) {
        return headerEnd(d, off) + contentLen(d, off);
    }

    private static boolean hasPrefix(byte[] d, int off, byte[] prefix) {
        if (off + prefix.length > d.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (d[off + i] != prefix[i]) return false;
        }
        return true;
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    private static byte[] pemToDer(String pem) {
        Matcher m = PEM_BODY.matcher(pem);
        if (m.find()) {
            try {
                return Base64.getDecoder().decode(m.group(1).replaceAll("\\s+", ""));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("bad base64 in PEM");
            }
        }
        try {
            return Base64.getDecoder().decode(pem.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("PEM or base64 DER expected");
        }
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private void respondQuietly(HttpExchange ex, int status, String body) {
        try {
            send(ex, status, body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Client already gone.
        }
    }

    private static void send(HttpExchange ex, int status, byte[] bytes) throws IOException {
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            if (key.equals(name)) return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
