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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.net.NetworkInterface;
import java.net.Inet4Address;

/** Tiny local-only handoff server. No file bytes are proxied through Android. */
final class BridgeServer implements AutoCloseable {
    private static final int MAX_LINE = 2048;
    private final ServerSocket listener;
    private final Thread worker;
    private volatile boolean running = true;
    private volatile String signedUrl;
    private volatile String token;

    BridgeServer() throws IOException {
        listener = new ServerSocket(0); // OS-selected port, available on local Wi-Fi/hotspot
        rotateSession();
        worker = new Thread(this::serve, "Lightning-LAN-Handoff");
        worker.setDaemon(true);
        worker.start();
    }

    int port() { return listener.getLocalPort(); }
    String token() { return token; }
    synchronized void rotateSession() {
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        StringBuilder result = new StringBuilder(random.length * 2);
        for (byte b : random) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        token = result.toString();
        signedUrl = null;
    }
    void setSignedUrl(String value) { signedUrl = value; }

    String endpoint() {
        return "http://" + lanIpv4() + ":" + port() + "/handoff?key=" + token;
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
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                    String ip = address.getHostAddress();
                    if (n.getName().startsWith("wlan") || n.getName().startsWith("ap") || n.getName().startsWith("swlan")) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Exception ignored) { }
        return fallback != null ? fallback : "PHONE_IP";
    }

    private void serve() {
        while (running) {
            try (Socket client = listener.accept()) {
                client.setSoTimeout(2500);
                process(client);
            } catch (IOException ex) {
                if (running) Log.w("LightningBridge", "LAN request failed: " + ex.getClass().getSimpleName());
            } catch (Exception ex) {
                Log.w("LightningBridge", "Invalid LAN request");
            }
        }
    }

    private void process(Socket client) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
        String request = input.readLine();
        if (request == null || request.length() > MAX_LINE) return;
        String[] fields = request.split(" ");
        if (fields.length < 2 || !"GET".equals(fields[0])) { respond(client, 405, "{\"error\":\"GET only\"}"); return; }
        URI u;
        try { u = new URI(fields[1]); }
        catch (Exception ex) { respond(client, 400, "{\"error\":\"Bad request\"}"); return; }
        if (!"/handoff".equals(u.getPath())) { respond(client, 404, "{\"error\":\"Not found\"}"); return; }
        String key = "";
        if (u.getRawQuery() != null) for (String p : u.getRawQuery().split("&")) {
            if (p.startsWith("key=")) { key = URLDecoder.decode(p.substring(4), "UTF-8"); break; }
        }
        if (!constantTimeEquals(key, token)) {
            respond(client, 403, "{\"error\":\"Unauthorized\"}"); return;
        }
        JSONObject data = new JSONObject();
        String direct = signedUrl;
        if (direct == null) data.put("status", "waiting");
        else { data.put("status", "ready"); data.put("url", direct); }
        respond(client, 200, data.toString());
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(x, y);
    }

    private static void respond(Socket socket, int status, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : "Error";
        String header = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }

    @Override public void close() throws IOException {
        running = false;
        listener.close();
        worker.interrupt();
    }
}
