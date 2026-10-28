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
import java.lang.reflect.Modifier;
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
    private final Method artwork;
    private final Field ancSection;
    private final Field sectionOwner;
    private final Field currentNativeMode;
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
        final List<WeakReference<View>> nativeOrder = new ArrayList<>();
        String address;
        long lastRequest;
        int lastMode;
        Panel(View detail, View adaptive, String address, ViewGroup card) {
            this.detail = new WeakReference<>(detail);
            this.adaptive = new WeakReference<>(adaptive);
            this.address = address;
            for (int i = 0; i < card.getChildCount(); i++) nativeOrder.add(new WeakReference<>(card.getChildAt(i)));
        }

        void arrange(ViewGroup card, boolean boundDevice) {
            if (nativeOrder.size() != 4 || card.getChildCount() != 4) return;
            List<View> items = new ArrayList<>();
            for (WeakReference<View> reference : nativeOrder) {
                View item = reference.get();
                if (item == null || item.getParent() != card) return;
                items.add(item);
            }
            // The native selection API indexes its private item list, not the
            // child hierarchy. Keep that list and all original listeners intact.
            int[] order = boundDevice ? new int[] {2, 0, 3, 1} : new int[] {0, 1, 2, 3};
            for (int position = 0; position < order.length; position++) {
                View item = items.get(order[position]);
                if (card.getChildAt(position) == item) continue;
                card.removeView(item);
                card.addView(item, position);
            }
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
        Class<?> sectionType = Class.forName("com.miui.circulateplus.world.headset.r", false, loader);
        ancSection = onlyField(detailType, sectionType, false);
        sectionOwner = onlyField(sectionType, detailType, false);
        currentNativeMode = onlyField(sectionType, int.class, true);
        updateMode = sectionType.getDeclaredMethod("M", int.class);
        updateMode.setAccessible(true);
        artwork = detailType.getDeclaredMethod("C", deviceInfo.getReturnType());
        artwork.setAccessible(true);
    }

    private static Field onlyField(Class<?> owner, Class<?> type, boolean mutable) throws ReflectiveOperationException {
        List<Field> fields = new ArrayList<>();
        for (Field field : owner.getDeclaredFields())
            if (field.getType() == type && (!mutable || !Modifier.isFinal(field.getModifiers()))) fields.add(field);
        if (fields.size() != 1) throw new NoSuchFieldException(owner.getName() + ": ambiguous " + type.getName());
        Field result = fields.get(0);
        result.setAccessible(true);
        return result;
    }

    static void install(XposedModule module, ClassLoader loader, SharedPreferences preferences) throws Exception {
        NativeControlPanelAdapter adapter = new NativeControlPanelAdapter(loader, preferences);
        module.hook(adapter.artwork).intercept(chain -> {
            try {
                Object info = chain.getArg(0);
                String target = info == null ? null : (String) adapter.address.get(info);
                if (adapter.freshTarget(target)) {
                    View detail = (View) chain.getThisObject();
                    int icon = detail.getResources().getIdentifier("circulate_airpods_headset_icon", "drawable",
                        detail.getContext().getPackageName());
                    if (icon != 0) return icon;
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w("HyperPods-X", "Native AirPods artwork unavailable", error);
            }
            return chain.proceed();
        });
        module.hook(adapter.selectItem).intercept(chain -> {
            Panel panel = adapter.panels.get(chain.getThisObject());
            if (panel != null) {
                try {
                    View detail = panel.detail.get();
                    int mode = detail == null ? 0 : adapter.confirmedMode(detail);
                    if (mode >= 1 && mode <= 4) {
                        // Native callbacks optimistically select a requested mode.
                        // Keep selection on the acknowledged AACP state until its update.
                        if (panel.lastMode == mode) return null;
                        return chain.proceed(new Object[] {index(mode)});
                    }
                } catch (ReflectiveOperationException | RuntimeException error) {
                    Log.w("HyperPods-X", "Native headset selection unavailable", error);
                }
            }
            return chain.proceed();
        });
        module.hook(adapter.updateMode).intercept(chain -> {
            try {
                View detail = (View) adapter.sectionOwner.get(chain.getThisObject());
                if (adapter.confirmedMode(detail) != 0) {
                    // Both provider updates and optimistic click callbacks enter here.
                    // Native unknown-mode handling hides and rebuilds the section for
                    // adaptive; update its state in place instead.
                    adapter.refresh(detail);
                    return null;
                }
            }
            catch (ReflectiveOperationException | RuntimeException error) {
                Log.w("HyperPods-X", "Native adaptive panel adapter unavailable", error);
            }
            return chain.proceed();
        });
        adapter.listen();
        Log.i("HyperPods-X", "Native adaptive headset panel adapter ready");
    }

    private void listen() {
        listener = (prefs, key) -> {
            if (key == null || "panel_anc".equals(key) || "panel_anc_pending".equals(key) ||
                "panel_anc_available".equals(key) || "native_connected".equals(key) ||
                "panel_readback_until".equals(key) || "panel_audio_route".equals(key) ||
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
                refresh(detail);
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w("HyperPods-X", "Native headset acknowledgment refresh unavailable", error);
            }
        }
    }

    private boolean fresh(String target) {
        return freshTarget(target) && preferences.getBoolean("panel_anc_available", false);
    }

    private boolean freshTarget(String target) {
        return NativeControlWire.isPresentationTarget(target, preferences.getString("address", ""),
            preferences.getString("panel_model", ""), preferences.getBoolean("enabled", false),
            preferences.getBoolean("native_connected", false), preferences.getBoolean("panel_audio_route", false),
            preferences.getLong("panel_readback_until", 0), preferences.getLong("panel_heartbeat", -1),
            SystemClock.elapsedRealtime());
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
            if (panel != null) panel.arrange(card, false);
            if (panel != null) panel.lastMode = 0;
            if (panel != null && panel.adaptive.get() != null) panel.adaptive.get().setVisibility(View.GONE);
            return;
        }
        boolean layoutChanged = panel == null || card.getVisibility() != View.VISIBLE || title.getVisibility() != View.VISIBLE;
        String target = preferences.getString("address", "");
        if (panel == null) {
            if (card.getChildCount() != 3) return;
            for (int i = 0; i < 3; i++) if (!itemType.isInstance(card.getChildAt(i))) return;
            int iconId = detail.getResources().getIdentifier("headset_noise_cancel_selector", "drawable", pkg);
            int textId = detail.getResources().getIdentifier("circulate_headset_control_anc_noise_cancel", "string", pkg);
            int toolsTextId = detail.getResources().getIdentifier("tools_text", "id", pkg);
            if (iconId == 0 || textId == 0 || toolsTextId == 0) return;
            // Add through the native API so its internal item list and index3 agree.
            // Keep the native backing-list indexes; only the visual child order changes.
            View adaptive = (View) addItem.invoke(card, iconId, textId);
            View labelView = adaptive.findViewById(toolsTextId);
            if (!(labelView instanceof TextView label)) {
                adaptive.setVisibility(View.GONE);
                return;
            }
            label.setText("自适应");
            label.setContentDescription("自适应");
            panel = new Panel(detail, adaptive, target, card);
            panels.put(card, panel);
            Panel endpoint = panel;
            adaptive.setOnClickListener(clicked -> requestAdaptive(endpoint, clicked));
        }
        View adaptive = panel.adaptive.get();
        if (adaptive == null || card.getChildCount() != 4 || adaptive.getParent() != card) return;
        panel.address = target;
        adaptive.setVisibility(View.VISIBLE);
        panel.arrange(card, true);
        Object section = ancSection.get(detail);
        if (section != null) currentNativeMode.setInt(section, switch (mode) { case 1 -> 2; case 2 -> 0; case 3 -> 1; default -> -1; });
        if (panel.lastMode != mode) {
            selectItem.invoke(card, index(mode));
            panel.lastMode = mode;
        }
        boolean enabled = preferences.getBoolean("native_connected", false) &&
            preferences.getLong("panel_readback_until", 0) == 0 && !preferences.getBoolean("panel_anc_pending", false);
        for (int i = 0; i < card.getChildCount(); i++) {
            View item = card.getChildAt(i);
            if (item.isEnabled() != enabled) item.setEnabled(enabled);
        }
        if (layoutChanged) {
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
