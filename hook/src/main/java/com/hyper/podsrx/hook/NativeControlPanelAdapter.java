package com.hyper.podsrx.hook;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Extends the existing native selector with acknowledged adaptive control. */
final class NativeControlPanelAdapter {
    private static final Uri REPOSITORY = Uri.parse("content://com.android.bluetooth.ble.app.headsetdata.provider/airpodsRepository");
    private final SharedPreferences preferences;
    private final Method deviceInfo;
    private final Field address;
    private final Method modeVisible;
    private final Method updateHeight;
    private final Method addItem;
    private final Method selectItem;
    private final Method updateMode;
    private final Class<?> cardType;
    private final Class<?> itemType;
    private final Map<ViewGroup, Panel> panels = Collections.synchronizedMap(new WeakHashMap<>());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable synchronize = this::synchronizePanels;
    private SharedPreferences.OnSharedPreferenceChangeListener listener;
    private final Executor commands = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "HyperPods-native-controls");
        thread.setDaemon(true);
        return thread;
    });

    private static final class Panel {
        final WeakReference<View> detail;
        final WeakReference<View> adaptive;
        String address;
        long lastRequest;
        Panel(View detail, View adaptive, String address) {
            this.detail = new WeakReference<>(detail);
            this.adaptive = new WeakReference<>(adaptive);
            this.address = address;
        }
    }

    private NativeControlPanelAdapter(ClassLoader loader, SharedPreferences preferences) throws Exception {
        this.preferences = preferences;
        Class<?> detailType = Class.forName("com.miui.circulateplus.world.headset.HeadSetsDetail", false, loader);
        cardType = Class.forName("com.miui.circulate.world.headset.ui.HeadsetSelectCardView", false, loader);
        itemType = Class.forName("com.miui.circulate.world.headset.ui.HeadsetSelectItemView", false, loader);
        deviceInfo = detailType.getMethod("getHeadsetDeviceInfo");
        address = deviceInfo.getReturnType().getField("mac");
        modeVisible = detailType.getMethod("setModeVisible", boolean.class);
        updateHeight = detailType.getMethod("W");
        addItem = cardType.getMethod("a", int.class, int.class);
        selectItem = cardType.getMethod("b", int.class);
        updateMode = detailType.getDeclaredMethod("Z", int.class);
        updateMode.setAccessible(true);
    }

    static void install(XposedModule module, ClassLoader loader, SharedPreferences preferences) throws Exception {
        NativeControlPanelAdapter adapter = new NativeControlPanelAdapter(loader, preferences);
        module.hook(adapter.selectItem).intercept(chain -> {
            Panel panel = adapter.panels.get(chain.getThisObject());
            if (panel != null) {
                try {
                    View detail = panel.detail.get();
                    int mode = detail == null ? 0 : adapter.confirmedMode(detail);
                    if (mode >= 1 && mode <= 4) {
                        // Native callbacks optimistically select a requested mode.
                        // Keep selection on the acknowledged AACP state until its update.
                        return chain.proceed(new Object[] {index(mode)});
                    }
                } catch (ReflectiveOperationException | RuntimeException error) {
                    Log.w("HyperPods-X", "Native headset selection unavailable", error);
                }
            }
            return chain.proceed();
        });
        module.hook(adapter.updateMode).intercept(chain -> {
            Object result = chain.proceed();
            try { adapter.refresh((View) chain.getThisObject()); }
            catch (ReflectiveOperationException | RuntimeException error) {
                Log.w("HyperPods-X", "Native adaptive panel adapter unavailable", error);
            }
            return result;
        });
        adapter.listen();
        Log.i("HyperPods-X", "Native adaptive headset panel adapter ready");
    }

    private void listen() {
        listener = (prefs, key) -> {
            if (key == null || "panel_anc".equals(key) || "panel_anc_pending".equals(key) ||
                "panel_anc_available".equals(key) || "native_connected".equals(key) ||
                "panel_model".equals(key) || "address".equals(key)) {
                main.removeCallbacks(synchronize);
                main.postDelayed(synchronize, 50);
            }
        };
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    private void synchronizePanels() {
        List<View> details = new ArrayList<>();
        synchronized (panels) {
            for (Panel panel : panels.values()) {
                View detail = panel.detail.get();
                if (detail != null && detail.isAttachedToWindow() && !details.contains(detail)) details.add(detail);
            }
        }
        for (View detail : details) {
            try {
                if (confirmedMode(detail) == 4) {
                    // MiLink can drop its unsupported 04 notification. Re-enter
                    // the native unknown-mode path, then our hook selects index3.
                    updateMode.invoke(detail, -1);
                } else {
                    refresh(detail);
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w("HyperPods-X", "Native headset acknowledgment refresh unavailable", error);
            }
        }
    }

    private boolean fresh(String target) {
        long age = SystemClock.elapsedRealtime() - preferences.getLong("panel_heartbeat", -1);
        return age >= 0 && age <= 30000 && preferences.getBoolean("enabled", false) &&
            preferences.getBoolean("native_connected", false) && preferences.getBoolean("panel_anc_available", false) &&
            "A3441".equals(preferences.getString("panel_model", "")) && target != null && !target.isEmpty() &&
            target.equalsIgnoreCase(preferences.getString("address", ""));
    }

    private int confirmedMode(View detail) throws ReflectiveOperationException {
        Object info = deviceInfo.invoke(detail);
        String target = info == null ? null : (String) address.get(info);
        if (!fresh(target)) return 0;
        int mode = preferences.getInt("panel_anc", 0);
        return mode >= 1 && mode <= 4 ? mode : 0;
    }

    private void refresh(View detail) throws ReflectiveOperationException {
        Context context = detail.getContext();
        String pkg = context.getPackageName();
        int cardId = detail.getResources().getIdentifier("anc_select_card", "id", pkg);
        int titleId = detail.getResources().getIdentifier("anc_card_text", "id", pkg);
        if (cardId == 0 || titleId == 0) return;
        View view = detail.findViewById(cardId);
        View title = detail.findViewById(titleId);
        if (!(view instanceof ViewGroup card) || !cardType.isInstance(card) || title == null) return;
        int mode = confirmedMode(detail);
        Panel panel = panels.get(card);
        if (mode == 0) {
            if (panel != null && panel.adaptive.get() != null) panel.adaptive.get().setVisibility(View.GONE);
            return;
        }
        String target = preferences.getString("address", "");
        if (panel == null) {
            if (card.getChildCount() != 3) return;
            for (int i = 0; i < 3; i++) if (!itemType.isInstance(card.getChildAt(i))) return;
            int iconId = detail.getResources().getIdentifier("headset_noise_cancel_selector", "drawable", pkg);
            int textId = detail.getResources().getIdentifier("circulate_headset_control_anc_noise_cancel", "string", pkg);
            int toolsTextId = detail.getResources().getIdentifier("tools_text", "id", pkg);
            if (iconId == 0 || textId == 0 || toolsTextId == 0) return;
            // Add through the native API so its internal item list and index3 agree.
            // Keeping the first three indexes also preserves native support filtering.
            View adaptive = (View) addItem.invoke(card, iconId, textId);
            View labelView = adaptive.findViewById(toolsTextId);
            if (!(labelView instanceof TextView label)) {
                adaptive.setVisibility(View.GONE);
                return;
            }
            label.setText("自适应");
            label.setContentDescription("自适应");
            panel = new Panel(detail, adaptive, target);
            panels.put(card, panel);
            Panel endpoint = panel;
            adaptive.setOnClickListener(clicked -> requestAdaptive(endpoint, clicked));
        }
        View adaptive = panel.adaptive.get();
        if (adaptive == null || card.getChildCount() != 4 || card.getChildAt(3) != adaptive) return;
        panel.address = target;
        adaptive.setVisibility(View.VISIBLE);
        for (int i = 0; i < card.getChildCount(); i++) card.getChildAt(i).setSelected(false);
        selectItem.invoke(card, index(mode));
        card.setVisibility(View.VISIBLE);
        title.setVisibility(View.VISIBLE);
        modeVisible.invoke(detail, true);
        detail.post(() -> {
            try { updateHeight.invoke(detail); }
            catch (ReflectiveOperationException | RuntimeException error) {
                Log.w("HyperPods-X", "Native adaptive panel height unavailable", error);
            }
        });
    }

    private void requestAdaptive(Panel panel, View clicked) {
        try {
            View detail = panel.detail.get();
            long now = SystemClock.elapsedRealtime();
            if (detail == null || confirmedMode(detail) == 0 || !fresh(panel.address) ||
                preferences.getInt("panel_anc", 0) == 4 || preferences.getBoolean("panel_anc_pending", false) ||
                now - panel.lastRequest < 300) return;
            panel.lastRequest = now;
            clicked.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            String target = panel.address;
            Context context = clicked.getContext().getApplicationContext();
            commands.execute(() -> {
                try {
                    if (!fresh(target) || preferences.getBoolean("panel_anc_pending", false)) return;
                    BluetoothManager manager = context.getSystemService(BluetoothManager.class);
                    if (manager == null || manager.getAdapter() == null) return;
                    Bundle request = new Bundle();
                    request.putString("extra_key", "air_anc");
                    request.putString("extra_value", "04");
                    request.putParcelable(BluetoothDevice.EXTRA_DEVICE, manager.getAdapter().getRemoteDevice(target));
                    Bundle result = context.getContentResolver().call(REPOSITORY, "airpodsRepository", "send_command", request);
                    if (result == null || result.getInt("extra_command_state", 0) != 1)
                        Log.w("HyperPods-X", "Native adaptive request was not accepted");
                } catch (RuntimeException error) {
                    Log.w("HyperPods-X", "Native adaptive request unavailable", error);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("HyperPods-X", "Native adaptive control unavailable", error);
        }
    }

    private static int index(int mode) {
        return switch (mode) { case 1 -> 2; case 2 -> 1; case 3 -> 0; default -> 3; };
    }
}
