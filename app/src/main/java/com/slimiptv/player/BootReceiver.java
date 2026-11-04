package com.slimiptv.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/** Автозапуск плеера при включении приставки. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) return;

        final Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent i = new Intent(app, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                app.startActivity(i);
            } catch (Throwable ignored) { }
        }, 4000);
    }
}