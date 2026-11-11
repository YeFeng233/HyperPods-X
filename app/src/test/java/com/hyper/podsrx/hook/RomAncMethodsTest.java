package com.hyper.podsrx.hook;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class RomAncMethodsTest {
    private RomHeadsetMethods.ClassLookup classes(Class<?> item, Class<?> section) {
        Map<String, Class<?>> types = Map.of(
            "com.miui.circulateplus.world.headset.HeadSetsDetail", Detail.class,
            "com.miui.circulate.world.headset.ui.HeadsetControlAncItemView", item,
            "com.miui.circulateplus.world.headset.j", section);
        return name -> {
            Class<?> type = types.get(name);
            if (type == null) throw new ClassNotFoundException(name);
            return type;
        };
    }

    @Test public void fixedItemLayoutHasItsOwnCreationCapabilityClickAndReadbackMethods() throws Exception {
        var layout = RomAncMethods.fixedItems(classes(Item.class, Section.class));
        assertEquals("z", layout.update().getName());
        assertEquals("w", layout.click().getName());
        assertEquals("F", layout.capabilities().getName());
        assertEquals("B", layout.height().getName());
        assertArrayEquals(new Class<?>[] {View.class, int.class, String.class}, layout.click().getParameterTypes());
        assertArrayEquals(new Class<?>[] {Context.class}, layout.createItem().getParameterTypes());
        assertEquals(ImageView.class, layout.icon().getReturnType());
        assertEquals(TextView.class, layout.title().getReturnType());
    }

    @Test public void unrelatedViewsAndReusedSectionNamesAreRejected() {
        assertThrows(NoSuchMethodException.class, () -> RomAncMethods.fixedItems(classes(View.class, Section.class)));
        assertThrows(NoSuchMethodException.class, () -> RomAncMethods.fixedItems(classes(Item.class, WrongSection.class)));
        assertThrows(NoSuchMethodException.class, () -> RomAncMethods.fixedItems(classes(WrongItem.class, Section.class)));
        assertThrows(ClassNotFoundException.class, () -> RomAncMethods.fixedItems(name -> { throw new ClassNotFoundException(name); }));
    }

    public static class Detail extends View {
        public Detail(Context context) { super(context); }
        public void B() {}
    }
    public static class Item extends LinearLayout {
        public Item(Context context) { super(context); }
        public ImageView getIcon() { return null; }
        public TextView getTitle() { return null; }
    }
    public static class Section {
        public Section(Detail detail) {}
        public void z(int mode) {}
        private void w(View view, int mode, String label) {}
        public void F() {}
    }
    public static class WrongSection {
        public WrongSection(Detail detail) {}
        public static void z(int mode) {}
        private void w(View view, int mode, String label) {}
        public void F() {}
    }
    public static class WrongItem extends LinearLayout {
        public WrongItem(Context context) { super(context); }
        public ImageView getIcon() { return null; }
        public String getTitle() { return ""; }
    }
}
