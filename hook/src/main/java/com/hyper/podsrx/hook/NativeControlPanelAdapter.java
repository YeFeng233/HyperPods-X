package com.hyper.podsrx.hook;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Extends the existing native selector with acknowledged adaptive control. */
final class NativeControlPanelAdapter {
    private static volatile NativeControlPanelAdapter installedAdapter;
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
    private final Field nativeItems;
    private final Class<?> detailType;
    private final Method updateState;
    private final Field headsetIcon;
    private String cardArtworkName;
    private Drawable.ConstantState cardArtwork;
    private final Field ancSection;
    private final Field sectionOwner;
    private final Field currentNativeMode;
    private final Class<?> cardType;
    private final Class<?> itemType;
    private final Map<ViewGroup, Panel> panels = Collections.synchronizedMap(new WeakHashMap<>());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable synchronize = this::synchronizePanels;
    private SharedPreferences.OnSharedPreferenceChangeListener listener;
    private String lastTargetDiagnostic;
    private volatile boolean closed;
    private final AtomicInteger pendingCommands = new AtomicInteger();
    private final ExecutorService commands = Executors.newSingleThreadExecutor(task -> {
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
        detailType = Class.forName("com.miui.circulateplus.world.headset.HeadSetsDetail", false, loader);
        cardType = Class.forName("com.miui.circulate.world.headset.ui.HeadsetSelectCardView", false, loader);
        nativeItems = onlyField(cardType, List.class, false);
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
        // a0() sets the card image from a single generic AirPods drawable on every state update.
        // Without it the card keeps that drawable; the controls do not depend on it.
        Method state = null;
        Field icon = null;
        try {
            state = detailType.getDeclaredMethod("a0");
            icon = onlyField(detailType, ImageView.class, false);
        } catch (ReflectiveOperationException error) {
            DiagnosticLog.w("HyperPods-X", "Native headset card artwork unavailable", error);
        }
        updateState = icon == null ? null : state;
        headsetIcon = icon;
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
        ReloadLifecycle.add(adapter::close);
        ReloadLifecycle.rejectWhile(() -> adapter.pendingCommands.get() != 0);
        module.hook(adapter.artwork).intercept(chain -> {
            if (!adapter.active()) return chain.proceed();
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
                DiagnosticLog.w("HyperPods-X", "Native AirPods artwork unavailable", error);
            }
            return chain.proceed();
        });
        if (adapter.updateState != null) module.hook(adapter.updateState).intercept(chain -> {
            Object result = chain.proceed();
            if (!adapter.active()) return result;
            try {
                View detail = (View) chain.getThisObject();
                Object info = adapter.deviceInfo.invoke(detail);
                String target = info == null ? null : (String) adapter.address.get(info);
                if (adapter.freshTarget(target)) adapter.applyCardArtwork(detail);
            } catch (ReflectiveOperationException | PackageManager.NameNotFoundException | RuntimeException error) {
                DiagnosticLog.w("HyperPods-X", "Native headset card artwork unavailable", error);
            }
            return result;
        });
        module.hook(adapter.selectItem).intercept(chain -> {
            if (!adapter.active()) return chain.proceed();
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
                    DiagnosticLog.w("HyperPods-X", "Native headset selection unavailable", error);
                }
            }
            return chain.proceed();
        });
        module.hook(adapter.updateMode).intercept(chain -> {
            if (!adapter.active()) return chain.proceed();
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
                DiagnosticLog.w("HyperPods-X", "Native adaptive panel adapter unavailable", error);
            }
            return chain.proceed();
        });
        adapter.listen();
        installedAdapter = adapter;
        // Package lifecycle callbacks are not replayed by libxposed after reload.
        // Reattach to an already visible headset detail after the old item is removed.
        adapter.main.post(adapter::refreshOpenWindows);
        DiagnosticLog.i("HyperPods-X", "Native adaptive headset panel adapter ready");
    }

    private void listen() {
        listener = (prefs, key) -> {
            if (!active()) return;
            if (key == null || "panel_anc".equals(key) || "panel_anc_pending".equals(key) ||
                "panel_anc_available".equals(key) || "native_connected".equals(key) ||
                "panel_readback_until".equals(key) || "panel_audio_route".equals(key) ||
                "panel_model".equals(key) || "address".equals(key)) {
                scheduleSynchronize();
            }
        };
        preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    private synchronized void scheduleSynchronize() {
        if (!active()) return;
        main.removeCallbacks(synchronize);
        main.postDelayed(synchronize, 50);
    }

    private void synchronizePanels() {
        if (!active()) return;
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
                DiagnosticLog.w("HyperPods-X", "Native headset acknowledgment refresh unavailable", error);
            }
        }
    }

    private boolean fresh(String target) {
        return freshTarget(target) && preferences.getBoolean("panel_anc_available", false);
    }

    private boolean freshTarget(String target) {
        if (!active()) return false;
        boolean matches = NativeControlWire.isPresentationTarget(target, preferences.getString("address", ""),
            preferences.getString("panel_model", ""), preferences.getBoolean("enabled", false),
            preferences.getBoolean("native_connected", false), preferences.getBoolean("panel_audio_route", false),
            preferences.getLong("panel_readback_until", 0), preferences.getLong("panel_heartbeat", -1),
            SystemClock.elapsedRealtime());
        String state = "matched=" + matches + " targetPresent=" + (target != null)
            + " boundMatches=" + (target != null && target.equalsIgnoreCase(preferences.getString("address", "")))
            + " model=" + preferences.getString("panel_model", "")
            + " enabled=" + preferences.getBoolean("enabled", false)
            + " connected=" + preferences.getBoolean("native_connected", false)
            + " heartbeatFresh=" + (SystemClock.elapsedRealtime() - preferences.getLong("panel_heartbeat", -1) <= 30000);
        if (!state.equals(lastTargetDiagnostic)) {
            lastTargetDiagnostic = state;
            DiagnosticLog.i("HyperPods-X", "Native panel target: " + state);
        }
        return matches;
    }

    /**
     * The bound headset's own pair artwork, decoded once per product and shared across card updates.
     * The card sizes its image by height. The ROM's AirPods icon is a 560 px square whose earbuds span
     * 298 px across, so the edge-to-edge pair is centered in a square with the same proportion.
     */
    private Drawable cardArtwork(Context host) throws PackageManager.NameNotFoundException {
        String name = HeadsetModels.of(preferences.getString("panel_model", ""),
            preferences.getInt("panel_product", -1)).artwork + "_pair";
        if (!name.equals(cardArtworkName)) {
            Context module = host.createPackageContext(NativeControlWire.APP, 0);
            int id = module.getResources().getIdentifier(name, "drawable", NativeControlWire.APP);
            Drawable drawable = id == 0 ? null : module.getDrawable(id);
            cardArtwork = drawable == null ? null : drawable.getConstantState();
            cardArtworkName = name;
        }
        if (cardArtwork == null) return null;
        Drawable pair = cardArtwork.newDrawable(host.getResources());
        int width = pair.getIntrinsicWidth(), height = pair.getIntrinsicHeight();
        if (width <= 0 || height <= 0) return pair;
        int side = Math.max(Math.round(width * 560f / 298f), height);
        int horizontal = (side - width) / 2, vertical = (side - height) / 2;
        return new InsetDrawable(pair, horizontal, vertical, side - width - horizontal, side - height - vertical);
    }

    /** Returns false when the module artwork is unavailable and the card keeps the ROM's image. */
    private boolean applyCardArtwork(View detail) throws ReflectiveOperationException, PackageManager.NameNotFoundException {
        if (headsetIcon == null) return false;
        Drawable drawable = cardArtwork(detail.getContext());
        if (drawable == null || !(headsetIcon.get(detail) instanceof ImageView icon)) return false;
        icon.setImageDrawable(drawable);
        return true;
    }

    private int confirmedMode(View detail) throws ReflectiveOperationException {
        Object info = deviceInfo.invoke(detail);
        String target = info == null ? null : (String) address.get(info);
        if (!fresh(target)) return 0;
        int mode = preferences.getInt("panel_anc", 0);
        return mode >= 1 && mode <= 4 ? mode : 0;
    }

    private void refresh(View detail) throws ReflectiveOperationException {
        if (!active()) return;
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
                ((List<?>) nativeItems.get(card)).remove(adaptive);
                card.removeView(adaptive);
                return;
            }
            label.setText(HookStrings.get(context, R.string.hook_adaptive));
            label.setContentDescription(HookStrings.get(context, R.string.hook_adaptive));
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
            main.post(() -> {
                if (!active() || !detail.isAttachedToWindow()) return;
                try { updateHeight.invoke(detail); }
                catch (ReflectiveOperationException | RuntimeException error) {
                    DiagnosticLog.w("HyperPods-X", "Native adaptive panel height unavailable", error);
                }
            });
        }
    }

    private void requestAdaptive(Panel panel, View clicked) {
        if (!active()) return;
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
            pendingCommands.incrementAndGet();
            try { commands.execute(() -> {
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
                        DiagnosticLog.w("HyperPods-X", "Native adaptive request was not accepted");
                } catch (RuntimeException error) {
                    DiagnosticLog.w("HyperPods-X", "Native adaptive request unavailable", error);
                } finally {
                    pendingCommands.decrementAndGet();
                }
            }); } catch (RuntimeException error) {
                pendingCommands.decrementAndGet();
                throw error;
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            DiagnosticLog.w("HyperPods-X", "Native adaptive control unavailable", error);
        }
    }

    private static int index(int mode) {
        return switch (mode) { case 1 -> 2; case 2 -> 1; case 3 -> 0; default -> 3; };
    }

    private boolean active() { return !closed && ReloadLifecycle.isActive(); }

    private void refreshOpenWindows() {
        if (!active()) return;
        try {
            Class<?> manager = Class.forName("android.view.WindowManagerGlobal");
            Object instance = manager.getMethod("getInstance").invoke(null);
            Field views = manager.getDeclaredField("mViews");
            views.setAccessible(true);
            for (Object root : new ArrayList<>((List<?>) views.get(instance))) {
                if (root instanceof View view) refreshTree(view);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            DiagnosticLog.w("HyperPods-X", "Existing headset panel refresh unavailable; reopen the card", error);
        }
    }

    private void refreshTree(View view) throws ReflectiveOperationException {
        if (!active()) return;
        if (detailType.isInstance(view)) {
            refresh(view);
            Object info = deviceInfo.invoke(view);
            boolean bound = info != null && freshTarget((String) address.get(info));
            boolean applied = false;
            if (bound) {
                try { applied = applyCardArtwork(view); }
                catch (PackageManager.NameNotFoundException error) {
                    DiagnosticLog.w("HyperPods-X", "Native headset card artwork unavailable", error);
                }
            }
            if (bound && !applied) {
                int id = view.getResources().getIdentifier("device_item", "id", view.getContext().getPackageName());
                View icon = id == 0 ? null : view.findViewById(id);
                int drawable = (int) artwork.invoke(view, info);
                if (drawable != 0 && icon instanceof ImageView image) image.setImageResource(drawable);
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) refreshTree(group.getChildAt(i));
        }
    }

    static void refreshExisting(View root) {
        NativeControlPanelAdapter adapter = installedAdapter;
        if (adapter == null || !adapter.active()) return;
        try { adapter.refreshTree(root); }
        catch (ReflectiveOperationException | RuntimeException error) {
            DiagnosticLog.w("HyperPods-X", "Existing headset panel refresh unavailable", error);
        }
    }

    static void refreshExistingWindows() {
        NativeControlPanelAdapter adapter = installedAdapter;
        if (adapter != null) adapter.refreshOpenWindows();
    }

    private synchronized void close() {
        if (pendingCommands.get() != 0) throw new IllegalStateException("Native control request is still running");
        closed = true;
        if (installedAdapter == this) installedAdapter = null;
        if (listener != null) preferences.unregisterOnSharedPreferenceChangeListener(listener);
        listener = null;
        main.removeCallbacksAndMessages(null);
        commands.shutdownNow();
        try {
            if (!commands.awaitTermination(1000, TimeUnit.MILLISECONDS))
                throw new IllegalStateException("Native control worker did not stop");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted stopping native control worker", error);
        }
        synchronized (panels) {
            for (Map.Entry<ViewGroup, Panel> entry : panels.entrySet()) {
                ViewGroup card = entry.getKey();
                Panel panel = entry.getValue();
                View adaptive = panel.adaptive.get();
                if (adaptive == null) continue;
                adaptive.setOnClickListener(null);
                panel.arrange(card, false);
                try {
                    // Removing only the child leaves index 3 in the host's private list,
                    // causing duplicate items and stale selection on the next generation.
                    ((List<?>) nativeItems.get(card)).remove(adaptive);
                    if (adaptive.getParent() == card) card.removeView(adaptive);
                } catch (IllegalAccessException error) {
                    throw new IllegalStateException("Could not remove old adaptive item", error);
                }
            }
            panels.clear();
        }
    }
}
