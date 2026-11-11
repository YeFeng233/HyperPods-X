package com.hyper.podsrx.hook;

import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.widget.ImageView;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedModule;

/** Replaces only the camera-grip half of HyperOS' handle battery status icon. */
final class SystemUiHandleIconHook {
    private static final String TAG = "HyperPods-X";
    private static final String SLOT = "handle_battery";
    private static final String LEGACY_SLOT = "bluetooth_handsfree_battery";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String RESOURCE_PREFIX = "stat_sys_handle_battery_";
    private static final String LEGACY_RESOURCE_PREFIX = "stat_sys_bluetooth_handsfree_battery_";
    private static final int AIRPODS_CASE_GLYPH = 0x102B47;
    private static final float RIGHT_START = 21.78f / 30f;
    private static final float BATTERY_SHIFT_PX = 3.5f;
    private static final float BATTERY_CLIP_GUARD_PX = 0.25f;

    private static AssetManager symbolAssets;
    private static Typeface symbolTypeface;
    private static Field slotField;
    private static final Map<Object, String> iconSlots = new WeakHashMap<>();

    private SystemUiHandleIconHook() {}

    static void install(XposedModule module, ClassLoader loader) throws Exception {
        Class<?> iconView = Class.forName("com.android.systemui.statusbar.StatusBarIconView", false, loader);
        Typeface typeface = loadSymbolTypeface(module);
        if (typeface == null) throw new IllegalStateException("SF Symbols font is unavailable");
        slotField = findField(iconView, "mSlot");
        List<Method> getIcons = getIconMethods(iconView);
        if (getIcons.isEmpty()) throw new NoSuchMethodException("StatusBarIconView.getIcon");
        for (Method getIcon : getIcons) {
            Field argumentIcon = getIcon.getParameterCount() == 1
                ? findField(getIcon.getParameterTypes()[0], "icon") : null;
            module.hook(getIcon).intercept(chain -> {
                Object result = chain.proceed();
                if (!(result instanceof Drawable drawable) || result instanceof HandleBatteryDrawable) return result;
                Object owner = chain.getThisObject();
                String slot = readString(slotField, owner);
                String rememberedSlot;
                synchronized (SystemUiHandleIconHook.class) {
                    rememberedSlot = iconSlots.get(chain.getArg(0));
                }
                String resourceName = null;
                boolean match = targetSlot(slot) || targetSlot(rememberedSlot);
                if (!match) {
                    Object icon = argumentIcon == null
                        ? readField(findField(owner.getClass(), "mIcon"), owner)
                        : readField(argumentIcon, chain.getArg(0));
                    resourceName = resourceName(owner, icon);
                    match = targetResource(resourceName);
                }
                if (!match) return result;
                boolean night = readBoolean(owner, "mNightMode");
                return new HandleBatteryDrawable(drawable, typeface, resourceName, night);
            });
        }
        for (Method updateDrawable : getMethods(iconView, "updateDrawable", 1)) {
            module.hook(updateDrawable).intercept(chain -> {
                Object result = chain.proceed();
                Object owner = chain.getThisObject();
                if (!(owner instanceof ImageView imageView)) return result;
                Drawable current = imageView.getDrawable();
                Drawable wrapped = wrapDrawable(owner, current, typeface);
                if (wrapped != current) imageView.setImageDrawable(wrapped);
                return result;
            });
        }
        for (Method setIcon : getMethods(iconView, "set", 1)) {
            module.hook(setIcon).intercept(chain -> {
                Object result = chain.proceed();
                Object owner = chain.getThisObject();
                if (!(owner instanceof ImageView imageView)) return result;
                Drawable current = imageView.getDrawable();
                Drawable wrapped = wrapDrawable(owner, current, typeface);
                if (wrapped != current) imageView.setImageDrawable(wrapped);
                return result;
            });
        }
        Class<?> controller = findClass(loader,
            "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl",
            "com.android.systemui.statusbar.phone.StatusBarIconControllerImpl");
        if (controller != null) {
            List<Method> controllerSetIcons = getMethods(controller, "setIcon", 2);
            for (Method setIcon : controllerSetIcons) {
                module.hook(setIcon).intercept(chain -> {
                    Object slot = chain.getArg(0);
                    Object result = chain.proceed();
                    if (slot instanceof String) rememberSlot((String) slot, chain.getArg(1));
                    return result;
                });
            }
        }
        DiagnosticLog.i(TAG, "SystemUI handle battery icon hook installed methods=" + getIcons.size());
    }

    private static Class<?> findClass(ClassLoader loader, String... names) {
        for (String name : names) {
            try { return Class.forName(name, false, loader); }
            catch (ClassNotFoundException ignored) { }
        }
        return null;
    }

    private static synchronized void rememberSlot(String slot, Object holder) {
        if (holder == null) return;
        Object statusIcon = holder;
        if (holder.getClass().getName().contains("StatusBarIconHolder")) {
            Object nested = readField(findField(holder.getClass(), "mIcon"), holder);
            if (nested == null) nested = readField(findField(holder.getClass(), "icon"), holder);
            if (nested != null) statusIcon = nested;
        }
        iconSlots.put(statusIcon, slot);
    }

    private static List<Method> getMethods(Class<?> type, String name, int parameterCount) {
        List<Method> methods = new ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    method.setAccessible(true);
                    methods.add(method);
                }
            }
        }
        return methods;
    }

    private static List<Method> getIconMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals("getIcon") && method.getParameterCount() <= 1
                    && Drawable.class.isAssignableFrom(method.getReturnType())) {
                    method.setAccessible(true);
                    methods.add(method);
                }
            }
        }
        return methods;
    }

    private static Drawable wrapDrawable(Object owner, Drawable drawable, Typeface typeface) {
        if (drawable == null || drawable instanceof HandleBatteryDrawable) return drawable;
        String slot = readString(slotField, owner);
        if (targetSlot(slot)) {
            return new HandleBatteryDrawable(drawable, typeface, null, readBoolean(owner, "mNightMode"));
        }
        Object icon = readField(findField(owner.getClass(), "mIcon"), owner);
        String rememberedSlot;
        synchronized (SystemUiHandleIconHook.class) { rememberedSlot = iconSlots.get(icon); }
        if (targetSlot(rememberedSlot)) {
            return new HandleBatteryDrawable(drawable, typeface, null, readBoolean(owner, "mNightMode"));
        }
        String resourceName = resourceName(owner, icon);
        if (!targetResource(resourceName)) return drawable;
        return new HandleBatteryDrawable(drawable, typeface, resourceName, readBoolean(owner, "mNightMode"));
    }

    private static boolean targetSlot(String slot) {
        return SLOT.equals(slot) || LEGACY_SLOT.equals(slot);
    }

    private static boolean targetResource(String resourceName) {
        return resourceName != null && (resourceName.startsWith(RESOURCE_PREFIX)
            || resourceName.startsWith(LEGACY_RESOURCE_PREFIX));
    }

    private static Field findField(Class<?> type, String name) {
        if (type == null) return null;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            } catch (RuntimeException error) {
                DiagnosticLog.w(TAG, "SystemUI icon field unavailable: " + name, error);
                return null;
            }
        }
        return null;
    }

    private static Object readField(Field field, Object owner) {
        if (field == null || owner == null) return null;
        try { return field.get(owner); }
        catch (IllegalAccessException | RuntimeException error) { return null; }
    }

    private static String readString(Field field, Object owner) {
        Object value = readField(field, owner);
        return value instanceof String ? (String) value : null;
    }

    private static boolean readBoolean(Object owner, String name) {
        if (owner == null) return false;
        Object value = readField(findField(owner.getClass(), name), owner);
        return value instanceof Boolean && (Boolean) value;
    }

    private static String resourceName(Object owner, Object iconObject) {
        if (!(iconObject instanceof Icon icon) || icon.getType() != Icon.TYPE_RESOURCE) return null;
        try {
            String packageName = icon.getResPackage();
            if (packageName != null && !SYSTEM_UI.equals(packageName)) return null;
            if (!(owner instanceof View view)) return null;
            Context host = view.getContext();
            return host.getResources().getResourceEntryName(icon.getResId());
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static synchronized Typeface loadSymbolTypeface(XposedModule module) {
        if (symbolTypeface != null) return symbolTypeface;
        try {
            String sourceDir = module.getModuleApplicationInfo().sourceDir;
            AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
            Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
            addAssetPath.setAccessible(true);
            if (sourceDir == null || ((Integer) addAssetPath.invoke(assets, sourceDir)) == 0) {
                throw new IllegalStateException("Module APK asset path is unavailable");
            }
            Typeface candidate = new Typeface.Builder(assets, "sf_pro.ttf").build();
            Paint probe = new Paint(Paint.ANTI_ALIAS_FLAG);
            probe.setTypeface(candidate);
            if (!probe.hasGlyph(new String(Character.toChars(AIRPODS_CASE_GLYPH)))) return null;
            symbolAssets = assets;
            symbolTypeface = candidate;
            return candidate;
        } catch (ReflectiveOperationException | RuntimeException error) {
            DiagnosticLog.w(TAG, "SF Symbols font load failed", error);
            return null;
        }
    }

    static Typeface symbolTypeface(XposedModule module) {
        return loadSymbolTypeface(module);
    }

    static Drawable caseGlyphDrawable(Typeface typeface) {
        return new CaseGlyphDrawable(typeface, (Context) null);
    }

    static Drawable caseGlyphDrawable(Typeface typeface, Context context) {
        return new CaseGlyphDrawable(typeface, context);
    }

    static Drawable caseGlyphDrawable(Typeface typeface, android.content.res.Resources resources) {
        return new CaseGlyphDrawable(typeface, resources);
    }

    private static final class CaseGlyphDrawable extends Drawable {
        private static final int GLYPH = 0x102B46;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private int alpha = 255;
        private ColorStateList tintList;

        CaseGlyphDrawable(Typeface typeface, Context context) {
            paint.setTypeface(typeface);
            boolean dark = context != null && (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            paint.setColor(dark ? Color.WHITE : Color.BLACK);
            paint.setFontFeatureSettings("'ss16' 1");
        }

        CaseGlyphDrawable(Typeface typeface, android.content.res.Resources resources) {
            paint.setTypeface(typeface);
            boolean dark = resources != null && (resources.getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            paint.setColor(dark ? Color.WHITE : Color.BLACK);
            paint.setFontFeatureSettings("'ss16' 1");
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            String glyph = new String(Character.toChars(GLYPH));
            float targetHeight = bounds.height() * 0.72f;
            paint.setTextSize(targetHeight);
            paint.setAlpha(alpha);
            if (tintList != null) paint.setColor(tintList.getColorForState(getState(), tintList.getDefaultColor()));
            Rect textBounds = new Rect();
            paint.getTextBounds(glyph, 0, glyph.length(), textBounds);
            if (textBounds.width() <= 0 || textBounds.height() <= 0) return;
            float scale = Math.min(1f, Math.min(bounds.width() * 0.74f / textBounds.width(), targetHeight / textBounds.height()));
            paint.setTextSize(targetHeight * scale);
            paint.getTextBounds(glyph, 0, glyph.length(), textBounds);
            float baseline = bounds.exactCenterY() - (textBounds.top + textBounds.bottom) * 0.5f;
            float x = bounds.exactCenterX() - paint.measureText(glyph) * 0.5f;
            canvas.drawText(glyph, x, baseline, paint);
        }

        @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
        @Override public int getAlpha() { return alpha; }
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }
        @Override public void setTintList(ColorStateList tint) { tintList = tint; invalidateSelf(); }
        @Override public boolean isStateful() { return tintList != null && tintList.isStateful(); }
        @Override protected boolean onStateChange(int[] state) { invalidateSelf(); return true; }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return 48; }
        @Override public int getIntrinsicHeight() { return 48; }
    }

    private static final class HandleBatteryDrawable extends Drawable {
        private final Drawable original;
        private final Typeface symbolTypeface;
        private final String resourceName;
        private final boolean nightMode;
        private final Paint symbolPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private ColorStateList tintList;
        private int alpha = 255;

        HandleBatteryDrawable(Drawable original, Typeface symbolTypeface, String resourceName, boolean nightMode) {
            this.original = original;
            this.symbolTypeface = symbolTypeface;
            this.resourceName = resourceName;
            this.nightMode = nightMode;
            symbolPaint.setTypeface(symbolTypeface);
            symbolPaint.setFontFeatureSettings("'ss16' 1");
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            original.setBounds(bounds);
            int save = canvas.save();
            // The battery is translated left to sit closer to the case glyph. Expand the
            // clip edge by the same amount so the battery's leading pixels remain visible.
            canvas.clipRect(
                bounds.left + bounds.width() * RIGHT_START - BATTERY_SHIFT_PX - BATTERY_CLIP_GUARD_PX,
                bounds.top,
                bounds.right,
                bounds.bottom
            );
            canvas.translate(-BATTERY_SHIFT_PX, 0f);
            original.draw(canvas);
            canvas.restoreToCount(save);
            drawAirPodsCase(canvas, bounds);
        }

        private void drawAirPodsCase(Canvas canvas, Rect bounds) {
            float left = bounds.left;
            float right = bounds.left + bounds.width() * RIGHT_START;
            float centerX = (left + right) * 0.5f;
            float centerY = bounds.exactCenterY();
            float targetWidth = (right - left) * 0.64f;
            float targetHeight = bounds.height() * 0.68f;
            String glyph = new String(Character.toChars(AIRPODS_CASE_GLYPH));
            symbolPaint.setTextSize(targetHeight);
            symbolPaint.setColor(symbolColor());
            symbolPaint.setAlpha(alpha);
            Rect textBounds = new Rect();
            symbolPaint.getTextBounds(glyph, 0, glyph.length(), textBounds);
            if (textBounds.width() <= 0 || textBounds.height() <= 0) return;
            float scale = Math.min(1f, Math.min(targetWidth / textBounds.width(), targetHeight / textBounds.height()));
            symbolPaint.setTextSize(targetHeight * scale);
            symbolPaint.getTextBounds(glyph, 0, glyph.length(), textBounds);
            float baseline = centerY - (textBounds.top + textBounds.bottom) * 0.5f;
            float x = centerX - symbolPaint.measureText(glyph) * 0.5f;
            symbolPaint.setColorFilter(getColorFilter());
            canvas.drawText(glyph, x, baseline, symbolPaint);
        }

        private int symbolColor() {
            return Color.WHITE;
        }

        @Override protected void onBoundsChange(Rect bounds) { original.setBounds(bounds); }

        @Override protected boolean onStateChange(int[] state) {
            boolean changed = original.setState(state);
            if (tintList != null && tintList.isStateful()) changed = true;
            if (changed) invalidateSelf();
            return changed;
        }

        @Override public boolean isStateful() { return original.isStateful() || tintList != null && tintList.isStateful(); }
        @Override public int getIntrinsicWidth() { return original.getIntrinsicWidth(); }
        @Override public int getIntrinsicHeight() { return original.getIntrinsicHeight(); }
        @Override public int getMinimumWidth() { return original.getMinimumWidth(); }
        @Override public int getMinimumHeight() { return original.getMinimumHeight(); }
        @Override public boolean getPadding(Rect padding) { return original.getPadding(padding); }
        @Override public int getOpacity() { return original.getOpacity(); }
        @Override public void setAlpha(int alpha) { this.alpha = alpha; original.setAlpha(alpha); invalidateSelf(); }
        @Override public int getAlpha() { return alpha; }
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            original.setColorFilter(colorFilter);
            symbolPaint.setColorFilter(colorFilter);
            invalidateSelf();
        }
        @Override public android.graphics.ColorFilter getColorFilter() { return original.getColorFilter(); }
        @Override public void setTintList(ColorStateList tint) { tintList = tint; original.setTintList(tint); invalidateSelf(); }
        public ColorStateList getTintList() { return tintList; }
        @Override public void setTintMode(PorterDuff.Mode mode) { original.setTintMode(mode); invalidateSelf(); }
        @Override public void setDither(boolean dither) { original.setDither(dither); }
        @Override public void setFilterBitmap(boolean filter) { original.setFilterBitmap(filter); }
        @Override public boolean setVisible(boolean visible, boolean restart) {
            boolean changed = super.setVisible(visible, restart);
            return original.setVisible(visible, restart) || changed;
        }
        @Override public void jumpToCurrentState() { original.jumpToCurrentState(); }
        @Override public void getOutline(android.graphics.Outline outline) { original.getOutline(outline); }
        @Override public Drawable mutate() { original.mutate(); return this; }
    }
}
