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
import android.widget.LinearLayout;
import android.widget.TextView;
import com.hyper.podsrx.protocol.NativeHeadsetPolicy;
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
    private final RomAncMethods.FixedItems fixedItems;
    private final Map<ViewGroup, Panel> panels = Collections.synchronizedMap(new WeakHashMap<>());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable synchronize = this::synchronizePanels;
    private SharedPreferences.OnSharedPreferenceChangeListener listener;
    private String lastTargetDiagnostic;
    private String lastLayoutFailure;
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
        final boolean[] originalEnabled;
        final int[] originalVisibility;
        String address;
        long lastRequest;
        int lastMode;
        int lastHostMode = -1;
        Panel(View detail, View adaptive, String address, ViewGroup card) {
            this.detail = new WeakReference<>(detail);
            this.adaptive = new WeakReference<>(adaptive);
            this.address = address;
            for (int i = 0; i < card.getChildCount(); i++) nativeOrder.add(new WeakReference<>(card.getChildAt(i)));
            originalEnabled = new boolean[card.getChildCount()];
            originalVisibility = new int[card.getChildCount()];
            for (int i = 0; i < card.getChildCount(); i++) {
                originalEnabled[i] = card.getChildAt(i).isEnabled();
                originalVisibility[i] = card.getChildAt(i).getVisibility();
            }
        }

        void restoreItems() {
            for (int i = 0; i < nativeOrder.size(); i++) {
                View item = nativeOrder.get(i).get();
                if (item == null) continue;
                item.setEnabled(originalEnabled[i]);
                item.setVisibility(originalVisibility[i]);
            }
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
        Class<?> selector = null;
        try { selector = Class.forName("com.miui.circulate.world.headset.ui.HeadsetSelectCardView", false, loader); }
        catch (ClassNotFoundException absent) { /* Resolve the independent fixed-item layout below. */ }
        fixedItems = selector == null ? RomAncMethods.fixedItems(name -> Class.forName(name, false, loader)) : null;
        cardType = fixedItems == null ? selector : LinearLayout.class;
        nativeItems = fixedItems == null ? onlyField(cardType, List.class, false) : null;
        itemType = fixedItems == null
            ? Class.forName("com.miui.circulate.world.headset.ui.HeadsetSelectItemView", false, loader) : fixedItems.item();
        deviceInfo = detailType.getMethod("getHeadsetDeviceInfo");
        address = deviceInfo.getReturnType().getField("mac");
        modeVisible = detailType.getMethod("setModeVisible", boolean.class);
        updateHeight = fixedItems == null ? detailType.getMethod("W") : fixedItems.height();
        addItem = fixedItems == null ? cardType.getMethod("a", int.class, int.class) : null;
        selectItem = fixedItems == null ? cardType.getMethod("b", int.class) : null;
        Class<?> sectionType = fixedItems == null
            ? Class.forName("com.miui.circulateplus.world.headset.r", false, loader) : fixedItems.section();
        ancSection = onlyField(detailType, sectionType, false);
        sectionOwner = onlyField(sectionType, detailType, false);
        currentNativeMode = onlyField(sectionType, int.class, true);
        updateMode = fixedItems == null ? sectionType.getDeclaredMethod("M", int.class) : fixedItems.update();
        updateMode.setAccessible(true);
        Method picture = null;
        if (fixedItems == null) {
            try {
                picture = detailType.getDeclaredMethod("C", deviceInfo.getReturnType());
                picture.setAccessible(true);
            } catch (ReflectiveOperationException error) {
                Compatibility.failed("Native headset card artwork", error);
                DiagnosticLog.w("HyperPods-X", "Native headset card artwork unavailable", error);
            }
        }
        artwork = picture;
        // a0() sets the card image from a single generic AirPods drawable on every state update.
        // Without it the card keeps that drawable; the controls do not depend on it.
        Method state = null;
        Field icon = null;
        if (fixedItems == null) try {
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
            if (field.getType() == type && !Modifier.isStatic(field.getModifiers()) &&
                (!mutable || !Modifier.isFinal(field.getModifiers()))) fields.add(field);
        if (fields.size() != 1) throw new NoSuchFieldException(owner.getName() + ": ambiguous " + type.getName());
        Field result = fields.get(0);
        result.setAccessible(true);
        return result;
    }

    static void install(XposedModule module, ClassLoader loader, SharedPreferences preferences) throws Exception {
        NativeControlPanelAdapter adapter = new NativeControlPanelAdapter(loader, preferences);
        ReloadLifecycle.add(adapter::close);
        ReloadLifecycle.rejectWhile(() -> adapter.pendingCommands.get() != 0);
        if (adapter.artwork != null) module.hook(adapter.artwork).intercept(chain -> {
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
        if (adapter.selectItem != null) module.hook(adapter.selectItem).intercept(chain -> {
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
                Panel panel = adapter.panel(detail);
                if (panel != null) panel.lastHostMode = (int) chain.getArg(0);
                if (adapter.confirmedMode(detail) != 0 && adapter.refresh(detail)) {
                    // Both provider updates and optimistic click callbacks enter here.
                    // Native unknown-mode handling hides and rebuilds the section for
                    // adaptive; update its state in place instead.
                    panel = adapter.panel(detail);
                    if (panel != null) panel.lastHostMode = (int) chain.getArg(0);
                    return null;
                }
            }
            catch (ReflectiveOperationException | RuntimeException error) {
                DiagnosticLog.w("HyperPods-X", "Native adaptive panel adapter unavailable", error);
            }
            return chain.proceed();
        });
        if (adapter.fixedItems != null) adapter.installFixedItems(module);
        adapter.listen();
        installedAdapter = adapter;
        // Package lifecycle callbacks are not replayed by libxposed after reload.
        // Reattach to an already visible headset detail after the old item is removed.
        adapter.main.post(adapter::refreshOpenWindows);
        DiagnosticLog.i("HyperPods-X", "Native adaptive headset panel adapter ready: "
            + (adapter.fixedItems == null ? "select-card" : "fixed-items")
            + " update=" + adapter.updateMode.toGenericString());
    }

    private void installFixedItems(XposedModule module) {
        module.hook(fixedItems.capabilities()).intercept(chain -> {
            if (!active()) return chain.proceed();
            try {
                View detail = (View) sectionOwner.get(chain.getThisObject());
                if (confirmedMode(detail) != 0 && refresh(detail)) {
                    return null;
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                DiagnosticLog.w("HyperPods-X", "Native ANC capabilities unavailable", error);
            }
            return chain.proceed();
        });
        module.hook(fixedItems.click()).intercept(chain -> {
            if (!active()) return chain.proceed();
            try {
                View detail = (View) sectionOwner.get(chain.getThisObject());
                Object info = deviceInfo.invoke(detail);
                String target = info == null ? null : (String) address.get(info);
                if (freshTarget(target) && refresh(detail)) {
                    Panel panel = panel(detail);
                    int mode = NativeHeadsetPolicy.toAacpMode((int) chain.getArg(1));
                    if (panel != null && mode != 0) requestMode(panel, (View) chain.getArg(0), mode);
                    return null;
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                DiagnosticLog.w("HyperPods-X", "Native ANC click unavailable", error);
            }
            return chain.proceed();
        });
    }

    private Panel panel(View detail) {
        View card = resourceView(detail, fixedItems == null ? "anc_select_card" : "anc_card");
        return card instanceof ViewGroup group ? panels.get(group) : null;
    }

    private static View resourceView(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
        return id == 0 ? null : root.findViewById(id);
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
        if (details.isEmpty()) refreshOpenWindows();
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

    private boolean layoutUnavailable(String detail) {
        if (!detail.equals(lastLayoutFailure)) {
            lastLayoutFailure = detail;
            Compatibility.failed("Native ANC view layout", new IllegalStateException(detail));
            DiagnosticLog.w("HyperPods-X", "Native ANC view layout unavailable: " + detail);
        }
        return false;
    }

    private boolean refresh(View detail) throws ReflectiveOperationException {
        if (!active()) return false;
        Context context = detail.getContext();
        String pkg = context.getPackageName();
        int cardId = detail.getResources().getIdentifier(fixedItems == null ? "anc_select_card" : "anc_card", "id", pkg);
        int titleId = detail.getResources().getIdentifier(fixedItems == null ? "anc_card_text" : "anc_card_title", "id", pkg);
        if (cardId == 0 || titleId == 0) return layoutUnavailable("ANC card/title resource missing");
        View view = detail.findViewById(cardId);
        View title = detail.findViewById(titleId);
        if (!(view instanceof ViewGroup card) || !cardType.isInstance(card) || title == null)
            return layoutUnavailable("ANC card/title view hierarchy mismatch");
        int mode = confirmedMode(detail);
        Panel panel = panels.get(card);
        if (mode == 0) {
            if (panel != null) {
                boolean wasActive = panel.lastMode != 0;
                panel.arrange(card, false);
                if (wasActive) panel.restoreItems();
                panel.lastMode = 0;
                if (panel.adaptive.get() != null) panel.adaptive.get().setVisibility(View.GONE);
                if (wasActive && fixedItems != null) {
                    Object section = ancSection.get(detail);
                    if (section != null) {
                        updateMode.invoke(section, panel.lastHostMode);
                        fixedItems.capabilities().invoke(section);
                    }
                }
            }
            return false;
        }
        boolean layoutChanged = panel == null || card.getVisibility() != View.VISIBLE || title.getVisibility() != View.VISIBLE;
        String target = preferences.getString("address", "");
        if (panel == null) {
            if (card.getChildCount() != 3) return layoutUnavailable("Expected three native ANC items");
            for (int i = 0; i < 3; i++) if (!itemType.isInstance(card.getChildAt(i)))
                return layoutUnavailable("Native ANC item type mismatch");
            if (fixedItems != null && (card.getChildAt(0) != resourceView(detail, "anc_clear") ||
                card.getChildAt(1) != resourceView(detail, "anc_noise_cancel") ||
                card.getChildAt(2) != resourceView(detail, "anc_off")))
                return layoutUnavailable("Native ANC item IDs/order mismatch");
            int iconId = detail.getResources().getIdentifier(
                fixedItems == null ? "headset_noise_cancel_selector" : "anc_selector", "drawable", pkg);
            int textId = detail.getResources().getIdentifier("circulate_headset_control_anc_noise_cancel", "string", pkg);
            int toolsTextId = detail.getResources().getIdentifier("tools_text", "id", pkg);
            if (iconId == 0 || (fixedItems == null && (textId == 0 || toolsTextId == 0)))
                return layoutUnavailable("ANC icon/label resource missing");
            // The select-card owns an indexed list; the fixed layout owns three views.
            View adaptive;
            View labelView;
            if (fixedItems == null) {
                adaptive = (View) addItem.invoke(card, iconId, textId);
                labelView = adaptive.findViewById(toolsTextId);
            } else {
                adaptive = (View) fixedItems.createItem().newInstance(context);
                adaptive.setId(View.generateViewId());
                ((ImageView) fixedItems.icon().invoke(adaptive)).setImageResource(iconId);
                labelView = (View) fixedItems.title().invoke(adaptive);
                card.addView(adaptive, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            if (!(labelView instanceof TextView label)) {
                if (nativeItems != null) ((List<?>) nativeItems.get(card)).remove(adaptive);
                card.removeView(adaptive);
                return layoutUnavailable("ANC label is not a TextView");
            }
            label.setText(HookStrings.get(context, R.string.hook_adaptive));
            label.setContentDescription(HookStrings.get(context, R.string.hook_adaptive));
            adaptive.setContentDescription(HookStrings.get(context, R.string.hook_adaptive));
            panel = new Panel(detail, adaptive, target, card);
            Object section = ancSection.get(detail);
            if (section != null) panel.lastHostMode = currentNativeMode.getInt(section);
            panels.put(card, panel);
            Panel endpoint = panel;
            adaptive.setOnClickListener(clicked -> requestMode(endpoint, clicked, 4));
        }
        View adaptive = panel.adaptive.get();
        if (adaptive == null || card.getChildCount() != 4 || adaptive.getParent() != card)
            return layoutUnavailable("Adaptive item detached or ANC hierarchy changed");
        panel.address = target;
        adaptive.setVisibility(View.VISIBLE);
        panel.arrange(card, true);
        Object section = ancSection.get(detail);
        if (section != null) currentNativeMode.setInt(section, NativeHeadsetPolicy.toNativeMode(mode));
        if (fixedItems != null) {
            for (int i = 0; i < panel.nativeOrder.size(); i++) {
                View item = panel.nativeOrder.get(i).get();
                if (item != null) {
                    item.setVisibility(View.VISIBLE);
                    boolean selected = i == index(mode);
                    if (item.isSelected() != selected) item.setSelected(selected);
                }
            }
            panel.lastMode = mode;
        } else if (panel.lastMode != mode) {
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
        lastLayoutFailure = null;
        Compatibility.installed("Native ANC view layout");
        return true;
    }

    private void requestMode(Panel panel, View clicked, int mode) {
        if (!active()) return;
        try {
            View detail = panel.detail.get();
            long now = SystemClock.elapsedRealtime();
            if (detail == null || confirmedMode(detail) == 0 || !fresh(panel.address) ||
                mode < 1 || mode > 4 || preferences.getInt("panel_anc", 0) == mode ||
                !preferences.getBoolean("native_connected", false) || preferences.getLong("panel_readback_until", 0) != 0 ||
                preferences.getBoolean("panel_anc_pending", false) ||
                now - panel.lastRequest < 300) return;
            panel.lastRequest = now;
            clicked.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            String target = panel.address;
            Context context = clicked.getContext().getApplicationContext();
            pendingCommands.incrementAndGet();
            try { commands.execute(() -> {
                try {
                    if (!fresh(target) || !preferences.getBoolean("native_connected", false) ||
                        preferences.getLong("panel_readback_until", 0) != 0 || preferences.getBoolean("panel_anc_pending", false)) return;
                    BluetoothManager manager = context.getSystemService(BluetoothManager.class);
                    if (manager == null || manager.getAdapter() == null) return;
                    Bundle request = new Bundle();
                    request.putString("extra_key", "air_anc");
                    request.putString("extra_value", "0" + mode);
                    request.putParcelable(BluetoothDevice.EXTRA_DEVICE, manager.getAdapter().getRemoteDevice(target));
                    Bundle result = context.getContentResolver().call(REPOSITORY, "airpodsRepository", "send_command", request);
                    if (result == null || result.getInt("extra_command_state", 0) != 1)
                        DiagnosticLog.w("HyperPods-X", "Native ANC request was not accepted: " + mode);
                } catch (RuntimeException error) {
                    DiagnosticLog.w("HyperPods-X", "Native ANC request unavailable", error);
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
            if (bound && !applied && artwork != null) {
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
                panel.restoreItems();
                try {
                    // Removing only the child leaves index 3 in the host's private list,
                    // causing duplicate items and stale selection on the next generation.
                    if (nativeItems != null) ((List<?>) nativeItems.get(card)).remove(adaptive);
                    if (adaptive.getParent() == card) card.removeView(adaptive);
                    if (fixedItems != null) {
                        View detail = panel.detail.get();
                        Object section = detail == null ? null : ancSection.get(detail);
                        if (section != null) {
                            updateMode.invoke(section, panel.lastHostMode);
                            fixedItems.capabilities().invoke(section);
                        }
                    }
                } catch (ReflectiveOperationException error) {
                    throw new IllegalStateException("Could not remove old adaptive item", error);
                }
            }
            panels.clear();
        }
    }
}
