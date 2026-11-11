package dev.tvremote.core;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * What the TV is playing, for the "now playing" bar of the remotes: the app, the title, where it is and how long it is, and
 * play / pause / seek. `dumpsys media_session` has neither the length nor a way to seek, so this is asked from the media
 * sessions themselves; the shell (where app_process runs it, as for Meta and Caps) may read and drive them.
 *
 * It keeps running: a line of JSON each time something changes, read by NowPlaying.kt. Commands come on stdin, one per line
 * (play, pause, seek <ms>, next, prev, or an empty heartbeat); it quits when stdin closes or stays quiet for 30 s, so a remote
 * that went away leaves nothing behind on the TV.
 */
public final class Playing {
    private Playing() { }

    private static MediaSessionManager sessions;
    private static final List<MediaController> watched = new ArrayList<>();
    private static Handler main;
    private static String last = "";
    private static long lastPos, lastAt;
    private static float lastSpeed;
    private static volatile long heard = SystemClock.elapsedRealtime();

    private static final MediaController.Callback changed = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { report(); }
        @Override public void onMetadataChanged(MediaMetadata m) { report(); }
        @Override public void onSessionDestroyed() { watch(); }
    };

    private static Context ctx;
    private static final java.util.Map<String, String> labels = new java.util.HashMap<>();

    /** The app's own name, for a player that tells no title (FPT Play): the bar then says which app plays. */
    private static String label(String pkg) {
        if (!labels.containsKey(pkg)) {
            String l = null;
            try { android.content.pm.PackageManager pm = ctx.getPackageManager(); l = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); } catch (Throwable ignored) { }
            labels.put(pkg, l);
        }
        return labels.get(pkg);
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        ctx = (Context) at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
        main = new Handler(Looper.myLooper());
        try {
            mediaServices();
            sessions = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            sessions.addOnActiveSessionsChangedListener(list -> watch(), null, main);
        } catch (Throwable e) {
            System.out.println("{\"error\":" + q(String.valueOf(e)) + "}");
            System.exit(0);
        }
        loadStamps();
        watch();
        started = true;
        Thread in = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in))) {
                for (String line; (line = r.readLine()) != null; ) {
                    heard = SystemClock.elapsedRealtime();
                    final String c = line.trim();
                    if (!c.isEmpty()) main.post(() -> command(c));
                }
            } catch (Throwable ignored) { }
            System.exit(0);
        });
        in.setDaemon(true);
        in.start();
        main.postDelayed(new Runnable() {                             // stdin does not always close when the remote's link drops
            @Override public void run() {
                if (SystemClock.elapsedRealtime() - heard > 30000) System.exit(0);
                main.postDelayed(this, 5000);
            }
        }, 5000);
        main.postDelayed(new Runnable() {                             // leaving a paused app tells its session nothing: looked at
            @Override public void run() { report(); main.postDelayed(this, 2000); }
        }, 2000);
        Looper.loop();
    }

    /** Android 12+: an app's process is handed the media services as it starts; app_process is not, so it is done here. */
    private static void mediaServices() {
        for (String name : new String[] { "android.media.MediaFrameworkPlatformInitializer", "android.media.MediaFrameworkInitializer" }) try {
            Class<?> init = Class.forName(name);                      // the first since Android 13, the second in 12
            Class<?> services = Class.forName("android.media.MediaServiceManager");
            if (init.getMethod("getMediaServiceManager").invoke(null) == null)
                init.getMethod("setMediaServiceManager", services).invoke(null, services.getConstructor().newInstance());
        } catch (ClassNotFoundException | NoSuchMethodException older) {
        } catch (Throwable e) {
            System.err.println("media services: " + e);
        }
    }

    /** Per app: the update time its last state carried, and when that state was first seen here. YouTube (Google TV) sends
     *  "playing from 0:00" stamped about two minutes in the past, so its videos seemed to start at 2:15; a state is never older
     *  than its arrival, so a stamp from before then is replaced by the arrival. */
    private static final java.util.Map<String, long[]> stamps = new java.util.HashMap<>();
    private static boolean started;                                  // states there before this ran: their arrival is unknown

    private static final java.io.File STAMPS = new java.io.File("/data/local/tmp/tvr-playing.txt");   // kept for the next run: a remote reopened mid-video

    private static synchronized long since(String pkg, PlaybackState s) {
        long t = s.getLastPositionUpdateTime(), now = SystemClock.elapsedRealtime();
        long[] k = stamps.get(pkg);
        if (k == null || k[0] != t) {
            k = new long[] { t, started && t < now - 2000 ? now : t };
            stamps.put(pkg, k);
            if (k[1] != t) saveStamps();
        }
        return k[1];
    }

    private static synchronized void loadStamps() {
        try (BufferedReader r = new BufferedReader(new java.io.FileReader(STAMPS))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.split(" ");
                if (f.length == 3) stamps.put(f[0], new long[] { Long.parseLong(f[1]), Long.parseLong(f[2]) });
            }
        } catch (Throwable none) { }
    }

    private static synchronized void saveStamps() {
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String, long[]> e : stamps.entrySet()) b.append(e.getKey()).append(' ').append(e.getValue()[0]).append(' ').append(e.getValue()[1]).append('\n');
        try (java.io.FileWriter w = new java.io.FileWriter(STAMPS)) { w.write(b.toString()); } catch (Throwable ignored) { }
    }

    private static void watch() {
        for (MediaController c : watched) try { c.unregisterCallback(changed); } catch (Throwable ignored) { }
        watched.clear();
        try { watched.addAll(sessions.getActiveSessions(null)); } catch (Throwable ignored) { }
        for (MediaController c : watched) try { c.registerCallback(changed, main); } catch (Throwable ignored) { }
        report();
    }

    /** The session the person means: one that plays, else a paused one whose app is on the screen, else none. A paused app
     *  left for another keeps its session (the bar would stay for good); one with no title (FPT Play) still counts. */
    private static MediaController current() {
        MediaController paused = null;
        for (MediaController c : watched) {
            PlaybackState s = c.getPlaybackState();
            int st = s == null ? 0 : s.getState();
            if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) return c;
            if (paused == null && st == PlaybackState.STATE_PAUSED && inFront(c.getPackageName())) paused = c;
        }
        return paused;
    }

    /** A paused session of an app no longer on the screen (music paused, then Home): [current] leaves it out, TV Home's
     *  "Now playing" widget offers to play it again ("play <pkg>"). */
    private static MediaController held() {
        for (MediaController c : watched) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PAUSED) return c;
        }
        return null;
    }

    private static MediaController of(String pkg) {
        for (MediaController c : watched) if (c.getPackageName().equals(pkg)) return c;
        return null;
    }

    private static boolean inFront(String pkg) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses())
                if (p.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND && p.pkgList != null)
                    for (String k : p.pkgList) if (k.equals(pkg)) return true;
            return false;
        } catch (Throwable cannotTell) { return true; }               // as before: the paused one stays
    }

    private static String title(MediaMetadata m) {
        if (m == null) return null;
        CharSequence t = m.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (t == null || t.length() == 0) t = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        return t == null || t.length() == 0 ? null : t.toString();
    }

    /** The line under the title: the artist, the channel, the album. */
    private static String sub(MediaMetadata m) {
        if (m != null) for (String k : new String[] { MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM }) {
            CharSequence v = m.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return null;
    }

    private static void report() {
        MediaController c = current();
        String line;
        if (c == null) {                                              // none for the remotes' bar; TV Home's widget also names an app paused behind it
            MediaController h = held();
            line = h == null ? "{\"state\":\"none\"}" : "{\"state\":\"none\",\"held\":{\"pkg\":" + q(h.getPackageName()) + ",\"app\":" + q(label(h.getPackageName()))
                + ",\"title\":" + q(title(h.getMetadata())) + ",\"sub\":" + q(sub(h.getMetadata())) + "}}";
        }
        else {
            PlaybackState s = c.getPlaybackState();
            MediaMetadata m = c.getMetadata();
            int st = s == null ? 0 : s.getState();
            long pos = s == null ? 0 : s.getPosition();
            float speed = s == null ? 0 : s.getPlaybackSpeed();
            long since = s == null ? 0 : since(c.getPackageName(), s);
            if (st == PlaybackState.STATE_PLAYING && since > 0)        // where it is now, not at its last report
                pos += (long) ((SystemClock.elapsedRealtime() - since) * speed);
            String sub = sub(m);
            long dur = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            line = "{\"state\":" + q(st == PlaybackState.STATE_PLAYING ? "playing" : st == PlaybackState.STATE_BUFFERING ? "buffering" : "paused")
                + ",\"pkg\":" + q(c.getPackageName()) + ",\"app\":" + q(label(c.getPackageName())) + ",\"title\":" + q(title(m)) + ",\"sub\":" + q(sub)
                + ",\"pos\":" + Math.max(0, pos) + ",\"dur\":" + Math.max(0, dur) + ",\"speed\":" + speed
                + ",\"seek\":" + ((s != null && (s.getActions() & PlaybackState.ACTION_SEEK_TO) != 0) ? "true" : "false") + "}";
        }
        // a position that only moved on with time is not news; one that jumped (a seek on the TV) is
        String key = line.replaceAll("\"pos\":\\d+", "");
        long now = SystemClock.elapsedRealtime();
        long pos = c == null ? 0 : Long.parseLong(line.replaceAll(".*\"pos\":(\\d+).*", "$1"));
        boolean jumped = Math.abs(pos - (lastPos + (long) ((now - lastAt) * lastSpeed))) > 2000;
        if (key.equals(last) && !jumped) return;
        last = key; lastPos = pos; lastAt = now;
        lastSpeed = c != null && line.contains("\"state\":\"playing\"") ? c.getPlaybackState().getPlaybackSpeed() : 0;
        System.out.println(line);
        System.out.flush();
    }

    private static void command(String c) {
        MediaController m = c.startsWith("play ") ? of(c.substring(5).trim()) : current();   // "play <pkg>": that app, even paused behind Home
        if (m == null) return;
        if (c.startsWith("play ")) c = "play";
        MediaController.TransportControls t = m.getTransportControls();
        try {
            if (c.equals("play")) t.play();
            else if (c.equals("pause")) t.pause();
            else if (c.equals("next")) t.skipToNext();
            else if (c.equals("prev")) t.skipToPrevious();
            else if (c.startsWith("seek ")) t.seekTo(Long.parseLong(c.substring(5).trim()));
        } catch (Throwable ignored) { }
        last = "";                                                    // say where it is now, even if the app does not
        main.postDelayed(Playing::report, 400);
    }

    private static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }
}
