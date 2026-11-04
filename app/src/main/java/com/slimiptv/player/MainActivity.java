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
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {

    static class Channel {
        String name;
        String group;
        String url;
        Channel(String n, String g, String u) { name = n; group = g; url = u; }
    }

    private final List<Channel> channels = new ArrayList<>();
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
        showList(true);
    }

    // ---------- плейлист ----------
    private void loadPlaylist() {
        channels.clear();
        File external = new File("/sdcard/Download/ru.m3u");
        boolean fromFile = false;
        if (external.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(external))) {
                parse(r);
                fromFile = true;
            } catch (Exception ignored) { }
        }
        if (channels.isEmpty()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("ru.m3u")))) {
                parse(r);
            } catch (Exception e) {
                toast("Не удалось прочитать плейлист: " + e.getMessage());
            }
        }
        adapter.notifyDataSetChanged();
        tvCount.setText(channels.size() + " каналов" + (fromFile ? " · из Download" : " · встроенный"));
        if (!channels.isEmpty()) list.setSelection(0);
    }

    private void parse(BufferedReader r) throws Exception {
        String line;
        String pendingName = null, pendingGroup = null;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                int comma = line.lastIndexOf(',');
                pendingName = comma > 0 ? line.substring(comma + 1).trim() : "Без названия";
                int gi = line.indexOf("group-title=\"");
                if (gi >= 0) {
                    int end = line.indexOf('"', gi + 13);
                    if (end > gi) pendingGroup = line.substring(gi + 13, end);
                } else pendingGroup = null;
            } else if (!line.startsWith("#")) {
                if (pendingName != null) {
                    channels.add(new Channel(pendingName, pendingGroup, line));
                    pendingName = null; pendingGroup = null;
                }
            }
        }
    }

    // ---------- воспроизведение ----------
    private volatile int playToken = 0;
    private Thread prepareThread;

    private void play(final int index) {
        if (index < 0 || index >= channels.size()) return;
        final Channel ch = channels.get(index);
        current = ch;
        currentIndex = index;
        showList(false);
        safeText(tvChannel, ch.name);
        safeText(tvStatus, "Подключение…");
        releasePlayer();
        final int token = ++playToken;

        prepareThread = new Thread(() -> {
            MediaPlayer mp = null;
            try {
                mp = new MediaPlayer();
                mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
                final MediaPlayer fmp = mp;
                if (videoSurface != null && videoSurface.isValid()) fmp.setSurface(videoSurface);
                fmp.setOnPreparedListener(m -> ui.post(() -> {
                    if (token != playToken) return;
                    try { safeText(tvStatus, ""); m.start(); }
                    catch (Throwable th) { safeText(tvStatus, "Канал недоступен"); }
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
                    ui.post(() -> {
                        if (token == playToken) safeText(tvStatus, "Канал недоступен — стрелками выберите другой");
                    });
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
                ui.post(() -> {
                    if (token == playToken) safeText(tvStatus, "Канал недоступен — стрелками выберите другой");
                });
            }
        }, "iptv-prepare");
        prepareThread.setDaemon(true);
        prepareThread.start();
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
        if (show) { list.requestFocus(); if (currentIndex >= 0) list.setSelection(currentIndex); }
    }

    // ---------- клавиши ----------
    @Override public boolean onKeyDown(int code, KeyEvent e) {
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
                loadPlaylist();
                toast("Плейлист обновлён: " + channels.size() + " каналов");
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

    @Override protected void onPause() { super.onPause(); }
    @Override protected void onDestroy() { releasePlayer(); super.onDestroy(); }

    // ---------- адаптер ----------
    private class ChannelAdapter extends BaseAdapter {
        @Override public int getCount() { return channels.size(); }
        @Override public Object getItem(int i) { return channels.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(18), dp(12), dp(18), dp(12));

            Channel ch = channels.get(position);

            TextView name = new TextView(MainActivity.this);
            name.setText(ch.name);
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
            row.addView(name);

            if (ch.group != null && !ch.group.isEmpty()) {
                TextView group = new TextView(MainActivity.this);
                group.setText(ch.group);
                group.setTextColor(Color.parseColor("#8FB3D9"));
                group.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                row.addView(group);
            }
            return row;
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}