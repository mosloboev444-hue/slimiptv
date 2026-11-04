package com.slimiptv.player;

import android.app.Activity;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {

    static class Channel {
        String name;
        String group;
        String url;
        String logo;
        int chno;
        Channel(String n, String g, String u, String l, int c) {
            name = n; group = g; url = u; logo = l; chno = c;
        }
    }

    // автообновление плейлиста
    private static final String PLAYLIST_URL =
            "https://raw.githubusercontent.com/mosloboev444-hue/slimiptv/main/playlist/ru.m3u";
    private static final String PREFS = "slimiptv";
    private static final long REFRESH_INTERVAL = 24L * 60 * 60 * 1000;

    private final List<Channel> channels = new ArrayList<>();
    private volatile boolean updating = false;
    private boolean autoResumed = false;
    private ListView list;
    private LinearLayout listPanel;
    private TextureView surface;
    private TextView tvChannel, tvStatus, tvCount;
    private ChannelAdapter adapter;

    private MediaPlayer player;
    private Surface videoSurface;
    private Channel current;
    private int currentIndex = -1;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(R.layout.activity_main);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        surface = findViewById(R.id.surface);
        listPanel = findViewById(R.id.listPanel);
        list = findViewById(R.id.list);
        tvChannel = findViewById(R.id.tvChannel);
        tvStatus = findViewById(R.id.tvStatus);
        tvCount = findViewById(R.id.tvCount);

        surface.setSurfaceTextureListener(this);

        adapter = new ChannelAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> play(position));

        loadPlaylist();
        if (current == null) showList(true);   // список показываем, только если автозапуск не сработал
    }

    // ---------- плейлист ----------
    private File cachedPlaylist() {
        return new File(getFilesDir(), "ru.m3u");
    }

    private void loadPlaylist() {
        channels.clear();
        String source = "встроенный";
        File cache = cachedPlaylist();
        File download = new File("/sdcard/Download/ru.m3u");

        if (cache.exists() && readInto(cache)) {
            source = "обновлённый " + new java.text.SimpleDateFormat("dd.MM HH:mm", new java.util.Locale("ru"))
                    .format(new java.util.Date(cache.lastModified()));
        } else if (download.exists() && readInto(download)) {
            source = "из Download";
        } else {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("ru.m3u")))) {
                parse(r);
            } catch (Exception e) {
                toast("Не удалось прочитать плейлист: " + e.getMessage());
            }
        }
        refreshList(source);
        maybeAutoUpdate(false);
        resumeLastChannel();
    }

    /** При запуске сразу включает последний канал, а если его нет — первый из списка. */
    private void resumeLastChannel() {
        if (channels.isEmpty() || autoResumed) return;
        autoResumed = true;
        String lastUrl = getSharedPreferences(PREFS, MODE_PRIVATE).getString("last_url", null);
        int index = 0;
        if (lastUrl != null) {
            for (int i = 0; i < channels.size(); i++) {
                if (lastUrl.equals(channels.get(i).url)) { index = i; break; }
            }
        }
        play(index);
    }
    private boolean readInto(File file) {
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            parse(r);
            return !channels.isEmpty();
        } catch (Exception e) {
            channels.clear();
            return false;
        }
    }

    private void refreshList(String source) {
        adapter.notifyDataSetChanged();
        safeText(tvCount, channels.size() + " каналов · " + source);
        if (!channels.isEmpty() && listPanel.getVisibility() == View.VISIBLE) list.setSelection(0);
    }

    /** Скачивает свежий плейлист, если он старше суток или принудительно. */
    private void maybeAutoUpdate(final boolean force) {
        if (updating) return;
        long last = getSharedPreferences(PREFS, MODE_PRIVATE).getLong("last_update", 0L);
        if (!force && System.currentTimeMillis() - last < REFRESH_INTERVAL) return;
        updating = true;
        safeText(tvCount, "обновление плейлиста…");
        new Thread(() -> {
            java.io.InputStream in = null;
            java.io.FileOutputStream out = null;
            java.net.HttpURLConnection c = null;
            boolean ok = false;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL(PLAYLIST_URL).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "SlimIPTV/1.3");
                if (c.getResponseCode() == 200) {
                    File tmp = new File(getFilesDir(), "ru_new.m3u");
                    out = new java.io.FileOutputStream(tmp);
                    in = c.getInputStream();
                    byte[] buf = new byte[8192];
                    int n, total = 0;
                    while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
                    out.flush(); out.close(); out = null;
                    if (total > 2000) {
                        File dst = cachedPlaylist();
                        if (dst.exists()) dst.delete();
                        ok = tmp.renameTo(dst);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                try { if (in != null) in.close(); } catch (Throwable ignored) { }
                try { if (out != null) out.close(); } catch (Throwable ignored) { }
                try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
            }
            final boolean success = ok;
            ui.post(() -> {
                updating = false;
                if (success) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putLong("last_update", System.currentTimeMillis()).apply();
                    int prev = currentIndex;
                    channels.clear();
                    String src = "обновлённый " + new java.text.SimpleDateFormat("dd.MM HH:mm",
                            new java.util.Locale("ru")).format(new java.util.Date());
                    if (readInto(cachedPlaylist())) {
                        refreshList(src);
                        currentIndex = Math.min(prev, Math.max(0, channels.size() - 1));
                        toast("Плейлист обновлён: " + channels.size() + " каналов");
                    } else {
                        loadPlaylist();
                    }
                } else {
                    refreshList("встроенный");
                    if (force) toast("Не удалось обновить плейлист — остался текущий");
                }
            });
        }, "iptv-update").start();
    }

    private void parse(BufferedReader r) throws Exception {
        String line;
        String pendingName = null, pendingGroup = null, pendingLogo = null;
        int pendingChno = 9999;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                int comma = line.lastIndexOf(',');
                pendingName = comma > 0 ? line.substring(comma + 1).trim() : "Без названия";
                pendingGroup = attr(line, "group-title");
                pendingLogo = attr(line, "tvg-logo");
                pendingChno = attrInt(line, "tvg-chno");
            } else if (!line.startsWith("#")) {
                if (pendingName != null) {
                    channels.add(new Channel(pendingName, pendingGroup, line, pendingLogo, pendingChno));
                    pendingName = null; pendingGroup = null; pendingLogo = null; pendingChno = 9999;
                }
            }
        }
    }

    private static String attr(String line, String key) {
        String needle = key + "=\"";
        int i = line.indexOf(needle);
        if (i < 0) return null;
        int start = i + needle.length();
        int end = line.indexOf('"', start);
        return end > start ? line.substring(start, end) : null;
    }

    private static int attrInt(String line, String key) {
        String v = attr(line, key);
        try { return v == null ? 9999 : Integer.parseInt(v.trim()); } catch (Exception e) { return 9999; }
    }

    // ---------- воспроизведение ----------
    private volatile int playToken = 0;
    private Thread prepareThread;
    private final java.util.HashSet<String> badUrls = new java.util.HashSet<>();
    private int skipStreak = 0;
    private String channelInput = "";
    private static final int MAX_SKIP = 15;          // сколько подряд пробуем, прежде чем сдаться
    private static final long PREPARE_TIMEOUT = 12000L;

    private void play(final int index) {
        if (index < 0 || index >= channels.size()) return;
        final Channel ch = channels.get(index);
        current = ch;
        currentIndex = index;
        showList(false);
        safeText(tvChannel, ch.name);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("last_url", ch.url).putString("last_name", ch.name).apply();
        safeText(tvStatus, "Подключение…");
        releasePlayer();
        final int token = ++playToken;
        final boolean[] prepared = {false};

        // если канал не отдал поток за 12 секунд — считаем недоступным и уходим дальше
        ui.postDelayed(() -> {
            if (token == playToken && !prepared[0]) failChannel(index, token);
        }, PREPARE_TIMEOUT);

        prepareThread = new Thread(() -> {
            MediaPlayer mp = null;
            try {
                mp = new MediaPlayer();
                mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
                final MediaPlayer fmp = mp;
                if (videoSurface != null && videoSurface.isValid()) fmp.setSurface(videoSurface);
                fmp.setOnPreparedListener(m -> ui.post(() -> {
                    if (token != playToken) return;
                    try {
                        safeText(tvStatus, "");
                        m.start();
                        prepared[0] = true;
                        skipStreak = 0;               // канал заработал — счётчик сбрасываем
                    } catch (Throwable th) {
                        failChannel(index, token);
                    }
                }));
                fmp.setOnInfoListener((m, what, extra) -> {
                    ui.post(() -> {
                        if (token != playToken) return;
                        if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) safeText(tvStatus, "Буферизация…");
                        if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) safeText(tvStatus, "");
                    });
                    return true;
                });
                fmp.setOnErrorListener((m, what, extra) -> {
                    ui.post(() -> failChannel(index, token));
                    return true;
                });
                fmp.setOnCompletionListener(m -> ui.post(() -> {
                    if (token == playToken) safeText(tvStatus, "Поток завершён");
                }));

                fmp.setDataSource(MainActivity.this, Uri.parse(ch.url));
                if (token != playToken) { releaseQuietly(fmp); return; }
                player = fmp;
                fmp.prepareAsync();
            } catch (Throwable th) {
                releaseQuietly(mp);
                ui.post(() -> failChannel(index, token));
            }
        }, "iptv-prepare");
        prepareThread.setDaemon(true);
        prepareThread.start();
    }

    /** Канал не отдал поток: помечаем и сразу переходим к следующему. */
    private void failChannel(int index, int token) {
        if (token != playToken) return;
        if (index >= 0 && index < channels.size()) badUrls.add(channels.get(index).url);

        if (++skipStreak > MAX_SKIP) {
            skipStreak = 0;
            safeText(tvStatus, "Не удалось найти рабочий канал");
            showList(true);
            return;
        }

        int next = nextGoodIndex(index);
        if (next < 0) {
            safeText(tvStatus, "Доступных каналов не осталось");
            showList(true);
            return;
        }
        safeText(tvStatus, "Канал недоступен — переключаю…");
        play(next);
    }

    /** Следующий канал, который ещё не признан битым. */
    private int nextGoodIndex(int from) {
        int n = channels.size();
        for (int step = 1; step <= n; step++) {
            int i = ((from + step) % n + n) % n;
            if (!badUrls.contains(channels.get(i).url)) return i;
        }
        return -1;
    }

    private void releasePlayer() {
        playToken++;
        final MediaPlayer old = player;
        player = null;
        if (old != null) {
            new Thread(() -> releaseQuietly(old), "iptv-release").start();
        }
    }

    private static void releaseQuietly(MediaPlayer mp) {
        if (mp == null) return;
        try { mp.reset(); } catch (Throwable ignored) { }
        try { mp.release(); } catch (Throwable ignored) { }
    }

    private void safeText(TextView tv, String s) {
        try { tv.setText(s); } catch (Throwable ignored) { }
    }
    private void showList(boolean show) {
        listPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        tvChannel.setVisibility(show ? View.GONE : View.VISIBLE);
        // SurfaceView перекрывает интерфейс: показываем его только в плеере
        surface.setVisibility(show ? View.GONE : View.VISIBLE);
        if (show) {
            // список открыт — картинки нет, поэтому и звук не должен идти
            pauseQuietly();
            list.requestFocus();
            if (currentIndex >= 0) list.setSelection(currentIndex);
        }
    }

    // ---------- клавиши ----------
    private void appendChannelDigit(int digit) {
        if (channelInput.length() >= 4) channelInput = "";
        channelInput += digit;
        safeText(tvStatus, "Канал " + channelInput);
        ui.removeCallbacks(applyChannelInput);
        ui.postDelayed(applyChannelInput, 1600L);
    }

    private final Runnable applyChannelInput = new Runnable() {
        @Override public void run() {
            String s = channelInput;
            channelInput = "";
            if (s.isEmpty()) return;
            int num;
            try { num = Integer.parseInt(s); } catch (Exception e) { return; }
            if (num >= 1 && num <= channels.size()) {
                play(num - 1);
            } else {
                safeText(tvStatus, "Канала " + num + " нет");
            }
        }
    };

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        // цифры — прямой ввод номера канала
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            appendChannelDigit(code - KeyEvent.KEYCODE_0);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DEL) {
            channelInput = "";
            ui.removeCallbacks(applyChannelInput);
            safeText(tvStatus, "");
            return true;
        }
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_HEADSETHOOK) {
            MediaPlayer mp = player;
            if (mp != null) {
                try {
                    if (mp.isPlaying()) { mp.pause(); safeText(tvStatus, "Пауза"); }
                    else { mp.start(); safeText(tvStatus, ""); }
                } catch (Throwable ignored) { }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_CHANNEL_UP) { switchChannel(1); return true; }
        if (code == KeyEvent.KEYCODE_MEDIA_PREVIOUS || code == KeyEvent.KEYCODE_CHANNEL_DOWN) { switchChannel(-1); return true; }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (listPanel.getVisibility() == View.GONE) { switchChannel(-1); return true; }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (listPanel.getVisibility() == View.GONE) { switchChannel(1); return true; }
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (listPanel.getVisibility() == View.GONE) { showList(true); return true; }
                break;
            case KeyEvent.KEYCODE_MENU:
                showList(listPanel.getVisibility() == View.GONE);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (listPanel.getVisibility() == View.GONE) { showList(true); return true; }
                break;
        }
        return super.onKeyDown(code, e);
    }

    private void switchChannel(int delta) {
        if (channels.isEmpty()) return;
        int next = currentIndex < 0 ? 0 : (currentIndex + delta + channels.size()) % channels.size();
        play(next);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------- surface ----------
    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        videoSurface = new Surface(st);
        if (current != null) play(currentIndex);
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    @Override protected void onPause() {
        super.onPause();
        // уходя с экрана (HOME, другое приложение) — глушим звук
        pauseQuietly();
    }

    @Override protected void onResume() {
        super.onResume();
        if (player == null && currentIndex >= 0 && !channels.isEmpty()) {
            play(currentIndex);      // вернулись в приложение — включаем канал заново
        } else {
            resumeQuietly();
        }
    }

    @Override protected void onStop() {
        super.onStop();
        // если приложение полностью свернули — освобождаем плеер
        if (!isChangingConfigurations() && !isFinishing()) releasePlayer();
    }

    @Override protected void onDestroy() { releasePlayer(); super.onDestroy(); }

    private void pauseQuietly() {
        MediaPlayer mp = player;
        if (mp == null) return;
        try { if (mp.isPlaying()) mp.pause(); } catch (Throwable ignored) { }
    }

    private void resumeQuietly() {
        MediaPlayer mp = player;
        if (mp == null) return;
        try { if (!mp.isPlaying()) mp.start(); } catch (Throwable ignored) { }
    }

    // ---------- адаптер ----------
    private final HashMap<String, Bitmap> logoCache = new HashMap<>();
    private final ExecutorService logoPool = Executors.newFixedThreadPool(3);

    private void loadLogo(final String url, final ImageView view) {
        Bitmap cached = logoCache.get(url);
        if (cached != null) { view.setImageBitmap(cached); return; }
        logoPool.execute(() -> {
            Bitmap bmp = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setInstanceFollowRedirects(true);
                try (InputStream in = c.getInputStream()) {
                    bmp = BitmapFactory.decodeStream(in);
                }
                c.disconnect();
            } catch (Throwable ignored) { }
            final Bitmap result = bmp;
            if (result == null) return;
            synchronized (logoCache) { logoCache.put(url, result); }
            ui.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(result);
            });
        });
    }

    private class ChannelAdapter extends BaseAdapter {
        @Override public int getCount() { return channels.size(); }
        @Override public Object getItem(int i) { return channels.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(18), dp(10), dp(18), dp(10));

            final Channel ch = channels.get(position);

            ImageView logo = new ImageView(MainActivity.this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(40));
            lp.rightMargin = dp(16);
            row.addView(logo, lp);
            if (ch.logo != null && !ch.logo.isEmpty()) {
                logo.setTag(ch.logo);
                loadLogo(ch.logo, logo);
            }

            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);

            TextView name = new TextView(MainActivity.this);
            name.setText(ch.name);
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
            texts.addView(name);

            if (ch.group != null && !ch.group.isEmpty()) {
                TextView group = new TextView(MainActivity.this);
                group.setText(ch.group);
                group.setTextColor(Color.parseColor("#8FB3D9"));
                group.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                texts.addView(group);
            }
            row.addView(texts);
            return row;
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}