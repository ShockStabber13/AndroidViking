package com.shockstabber.lightningbridge;

import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.net.NetworkInterface;
import java.net.Inet4Address;

/**
 * Small, local-LAN-only request and handoff server.
 * The Switch posts an HTTPS ViKiNG FiLE page; Android displays the normal
 * website/Turnstile flow; the Switch polls for the signed CDN URL.
 * Does not relay file bytes, verification responses, or browser cookies.
 */
final class BridgeServer implements AutoCloseable {
    static final int PORT = 48371;
    private static final int MAX_LINE = 2048;
    interface RequestListener { void onRequest(String pageUrl); }
    private final ServerSocket listener;
    private final Thread worker;
    private volatile boolean running = true;
    private volatile String signedUrl;
    private volatile String pageUrl;
    private final String pin;
    private volatile RequestListener requestListener;
    private int failedPins;
    private long failureWindow;

    BridgeServer(String pairedPin) throws IOException {
        if (pairedPin == null || !pairedPin.matches("[0-9]{6}"))
            throw new IllegalArgumentException("Invalid AndroidViking pairing PIN");
        listener = new ServerSocket(PORT);
        pin = pairedPin;
        worker = new Thread(this::serve, "Lightning-LAN-Handoff");
        worker.setDaemon(true);
        worker.start();
    }

    String pin() { return pin; }
    String ip() { return lanIpv4(); }
    String endpoint() {
        return "http://" + ip() + ":" + PORT + "/handoff?pin=" + pin;
    }
    void setRequestListener(RequestListener listener) { requestListener = listener; }
    synchronized void beginPage(String url) {
        pageUrl = url;
        signedUrl = null;
    }
    synchronized void setSignedUrl(String url) {
        if (pageUrl != null) signedUrl = url;
    }

    private synchronized boolean allowPin(String supplied) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - failureWindow > 60_000) {
            failureWindow = now;
            failedPins = 0;
        }
        if (failedPins >= 8) return false; // rate-limit LAN guessing
        boolean correct = java.security.MessageDigest.isEqual(
                pin.getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.US_ASCII));
        if (!correct) failedPins++;
        return correct;
    }

    static String lanIpv4() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface n = interfaces.nextElement();
                if (!n.isUp() || n.isLoopback()) continue;
                Enumeration<InetAddress> addresses = n.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()) continue;
                    String ip = address.getHostAddress();
                    if (n.getName().startsWith("wlan") || n.getName().startsWith("ap")
                            || n.getName().startsWith("swlan")) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Exception ignored) {}
        return fallback != null ? fallback : "PHONE_IP";
    }

    static boolean isFilePage(String value) {
        if (value == null || value.length() > 1024) return false;
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            String path = uri.getRawPath();
            return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                    && (host.equalsIgnoreCase("vik1ngfile.site")
                            || host.equalsIgnoreCase("vikingfile.com"))
                    && path != null && path.startsWith("/f/") && path.length() > 3
                    && uri.getRawUserInfo() == null;
        } catch (Exception ignored) { return false; }
    }

    private void serve() {
        while (running) {
            try (Socket client = listener.accept()) {
                client.setSoTimeout(3000);
                process(client);
            } catch (IOException ex) {
                if (running) Log.w("LightningBridge", "LAN request failed");
            } catch (Exception ex) {
                Log.w("LightningBridge", "Bad LAN request");
            }
        }
    }

    private void process(Socket socket) throws Exception {
        BufferedReader input = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String line = input.readLine();
        if (line == null || line.length() > MAX_LINE) return;
        String[] request = line.split(" ");
        if (request.length < 3) { respond(socket, 400, "{\"error\":\"Bad request\"}"); return; }
        URI uri;
        try { uri = new URI(request[1]); }
        catch (Exception ex) { respond(socket, 400, "{\"error\":\"Bad URI\"}"); return; }
        if (!"/request".equals(uri.getPath()) && !"/handoff".equals(uri.getPath())) {
            respond(socket, 404, "{\"error\":\"Not found\"}"); return;
        }
        String supplied = "";
        if (uri.getRawQuery() != null) {
            for (String p : uri.getRawQuery().split("&")) {
                if (p.startsWith("pin=")) { supplied = p.substring(4); break; }
            }
        }
        if (!allowPin(supplied)) {
            respond(socket, 403, "{\"error\":\"Not paired\"}");
            return;
        }

        if ("GET".equals(request[0]) && "/handoff".equals(uri.getPath())) {
            JSONObject state = new JSONObject();
            String direct = signedUrl;
            if (pageUrl == null) state.put("status", "idle");
            else if (direct == null) state.put("status", "waiting");
            else { state.put("status", "ready"); state.put("url", direct); }
            respond(socket, 200, state.toString());
            return;
        }
        if (!"POST".equals(request[0]) || !"/request".equals(uri.getPath())) {
            respond(socket, 405, "{\"error\":\"Unsupported method\"}"); return;
        }
        int length = -1;
        String header;
        while ((header = input.readLine()) != null && !header.isEmpty()) {
            if (header.length() > MAX_LINE) return;
            if (header.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                try { length = Integer.parseInt(header.substring(15).trim()); }
                catch (NumberFormatException ignored) { length = -1; }
            }
        }
        if (length <= 0 || length > 2048) {
            respond(socket, 400, "{\"error\":\"Invalid body length\"}"); return;
        }
        char[] buffer = new char[length];
        int total = 0;
        while (total < length) {
            int n = input.read(buffer, total, length - total);
            if (n <= 0) return;
            total += n;
        }
        JSONObject json = new JSONObject(new String(buffer));
        String requested = json.optString("url", "");
        if (!isFilePage(requested)) {
            respond(socket, 400, "{\"error\":\"Invalid ViKiNG FiLE URL\"}");
            return;
        }
        beginPage(requested);
        RequestListener callback = requestListener;
        if (callback != null) callback.onRequest(requested);
        respond(socket, 200, "{\"status\":\"waiting\"}");
    }

    private static void respond(Socket client, int code, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = client.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }

    @Override public void close() throws IOException {
        running = false;
        listener.close();
        worker.interrupt();
    }
}
