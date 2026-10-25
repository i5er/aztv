package sa.urtv.home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A tiny web page on the home Wi-Fi for sending photos from a phone to one account. It runs only while the
 * TV shows «صوري» (PhotosActivity) and answers only with the 4-digit code shown there; too many wrong codes
 * and the screen picks a new one.
 */
final class PhotoServer {
    interface Listener {
        void changed();
        void tooManyTries();
    }

    static final int FIRST_PORT = 8765;
    private static final int MAX_BODY = 40 * 1024 * 1024, MAX_FAILS = 12;

    private final Context c;
    private final String pid;
    private final Listener listener;
    private volatile String code;
    private volatile ServerSocket server;
    private int fails;

    PhotoServer(Context c, String pid, String code, Listener listener) {
        this.c = c.getApplicationContext();
        this.pid = pid;
        this.code = code;
        this.listener = listener;
    }

    void setCode(String code) { this.code = code; fails = 0; }

    /** The port it listens on, or -1. */
    int start() {
        for (int port = FIRST_PORT; port < FIRST_PORT + 10; port++) {
            try {
                server = new ServerSocket(port);
                break;
            } catch (IOException ignored) { }
        }
        if (server == null) return -1;
        final ServerSocket ss = server;
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    new Thread(() -> handle(s), "photo-client").start();
                } catch (IOException e) {
                    break;
                }
            }
        }, "photo-server");
        t.setDaemon(true);
        t.start();
        return ss.getLocalPort();
    }

    void stop() {
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        server = null;
    }

    // ---------------------------------------------------------------- one request

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(60_000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) return;
            String method = req[0], target = req[1];
            int length = 0;
            for (String l : lines) {
                if (l.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    try { length = Integer.parseInt(l.substring(15).trim()); } catch (NumberFormatException ignored) { }
                }
            }
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            Map<String, String> args = parse(query);

            if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                send(out, 200, "text/html; charset=utf-8", asset("phone/index.html"));
                return;
            }
            if (method.equals("GET") && path.startsWith("/fonts/") && path.matches("/fonts/[A-Za-z-]+\\.otf")) {
                send(out, 200, "font/otf", asset(path.substring(1)));
                return;
            }
            if (!code.equals(args.get("k"))) {
                boolean tooMany;
                synchronized (this) { tooMany = ++fails >= MAX_FAILS; }
                if (tooMany) listener.tooManyTries();
                sendJson(out, 403, new JSONObject().put("error", "code"));
                return;
            }
            synchronized (this) { fails = 0; }
            Profiles.Profile p = Profiles.find(c, pid);
            if (p == null) { sendJson(out, 410, new JSONObject().put("error", "gone")); return; }

            if (method.equals("GET") && path.equals("/api/state")) {
                JSONArray photos = new JSONArray();
                for (File f : Profiles.photos(c, pid)) photos.put(f.getName());
                sendJson(out, 200, new JSONObject().put("name", p.name).put("emoji", p.emoji)
                    .put("color", String.format("#%06X", Profiles.COLORS[p.color][0] & 0xFFFFFF))
                    .put("avatar", Profiles.avatarFile(c, pid).isFile())
                    .put("photos", photos).put("max", Profiles.MAX_PHOTOS));
            } else if (method.equals("GET") && path.equals("/api/photo")) {
                String n = args.get("n");
                File f = n != null && n.matches("[A-Za-z0-9_-]+\\.jpg") ? new File(Profiles.photosDir(c, pid), n) : null;
                if (f == null || !f.isFile()) { send(out, 404, "text/plain", new byte[0]); return; }
                send(out, 200, "image/jpeg", read(new FileInputStream(f)));
            } else if (method.equals("GET") && path.equals("/api/avatar")) {
                File f = Profiles.avatarFile(c, pid);
                if (!f.isFile()) { send(out, 404, "text/plain", new byte[0]); return; }
                send(out, 200, "image/jpeg", read(new FileInputStream(f)));
            } else if (method.equals("POST") && (path.equals("/api/upload") || path.equals("/api/avatar"))) {
                if (length <= 0 || length > MAX_BODY) { sendJson(out, 413, new JSONObject().put("error", "size")); return; }
                byte[] body = new byte[length];
                int got = 0;
                while (got < length) {
                    int n = in.read(body, got, length - got);
                    if (n < 0) return;
                    got += n;
                }
                boolean ok;
                if (path.equals("/api/avatar")) {
                    ok = Profiles.setAvatar(c, pid, body);
                } else {
                    if (Profiles.photos(c, pid).size() >= Profiles.MAX_PHOTOS) {
                        sendJson(out, 409, new JSONObject().put("error", "full")); return;
                    }
                    ok = Profiles.addPhoto(c, pid, body) != null;
                }
                if (ok) listener.changed();
                sendJson(out, ok ? 200 : 415, new JSONObject().put("ok", ok));
            } else if (method.equals("POST") && path.equals("/api/delete")) {
                boolean ok = Profiles.deletePhoto(c, pid, String.valueOf(args.get("n")));
                if (ok) listener.changed();
                sendJson(out, 200, new JSONObject().put("ok", ok));
            } else if (method.equals("POST") && path.equals("/api/avatar-reset")) {
                Profiles.clearAvatar(c, pid);
                listener.changed();
                sendJson(out, 200, new JSONObject().put("ok", true));
            } else {
                send(out, 404, "text/plain", new byte[0]);
            }
        } catch (Exception ignored) { }
    }

    /** Request line and headers, up to the blank line. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0, x;
        while ((x = in.read()) >= 0) {
            b.write(x);
            if (b.size() > 16384) return null;
            state = (x == '\r' && (state == 0 || state == 2)) ? state + 1
                  : (x == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return b.toString("ISO-8859-1");
        }
        return null;
    }

    private static Map<String, String> parse(String query) {
        Map<String, String> m = new HashMap<>();
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e <= 0) continue;
            try { m.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"), URLDecoder.decode(kv.substring(e + 1), "UTF-8")); }
            catch (Exception ignored) { }
        }
        return m;
    }

    private byte[] asset(String name) throws IOException { return read(c.getAssets().open(name)); }

    private static byte[] read(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private static void sendJson(OutputStream out, int status, JSONObject o) throws IOException {
        send(out, status, "application/json; charset=utf-8", o.toString().getBytes("UTF-8"));
    }

    private static void send(OutputStream out, int status, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\n"
            + "Content-Type: " + type + "\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Cache-Control: no-store\r\n"
            + "Connection: close\r\n\r\n";
        out.write(head.getBytes("ISO-8859-1"));
        out.write(body);
        out.flush();
    }
}
