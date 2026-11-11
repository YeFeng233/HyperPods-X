package com.hyper.podsrx.hook;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** The fixed three-item ANC layout in device interconnectivity 17.2.4.1.2606161948. */
final class RomAncMethods {
    record FixedItems(Class<?> detail, Class<?> item, Class<?> section, Method update,
            Method click, Method capabilities, Method height, Constructor<?> createItem,
            Method icon, Method title) {}

    static FixedItems fixedItems(RomHeadsetMethods.ClassLookup classes) throws ReflectiveOperationException {
        Class<?> detail = classes.load("com.miui.circulateplus.world.headset.HeadSetsDetail");
        Class<?> item = classes.load("com.miui.circulate.world.headset.ui.HeadsetControlAncItemView");
        Class<?> section = classes.load("com.miui.circulateplus.world.headset.j");
        if (!View.class.isAssignableFrom(detail) || !LinearLayout.class.isAssignableFrom(item))
            throw new NoSuchMethodException("Unsupported fixed ANC view hierarchy");
        section.getDeclaredConstructor(detail);
        return new FixedItems(detail, item, section,
            instanceMethod(section, "z", void.class, int.class),
            instanceMethod(section, "w", void.class, View.class, int.class, String.class),
            instanceMethod(section, "F", void.class),
            instanceMethod(detail, "B", void.class),
            item.getConstructor(Context.class),
            instanceMethod(item, "getIcon", ImageView.class),
            instanceMethod(item, "getTitle", TextView.class));
    }

    private static Method instanceMethod(Class<?> owner, String name, Class<?> result, Class<?>... arguments)
            throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, arguments);
        if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers()))
            throw new NoSuchMethodException(owner.getName() + "." + name + " signature mismatch");
        method.setAccessible(true);
        return method;
    }

    private RomAncMethods() {}
}
