// SPDX-License-Identifier: GPL-3.0-or-later
package io.mesalabs.unica.lockscreen;
import android.view.View;
import com.samsung.android.sdk.aodservice.entities.item.LockScreenItem;
import android.view.ViewGroup;
/** Independent frame axes, uniform content transform and centered spare space. */
public final class ResizeGeometry {
    public static float factor(float value) {
        return Float.isNaN(value) || Float.isInfinite(value) || value <= 0 ? 1f : Math.max(.5f, Math.min(1.25f, value));
    }
    public static int extent(int size, float factor) { return Math.max(1, Math.round(size * factor(factor))); }
    public static int extentX(int size, LockScreenItem item) { return size <= 0 ? size : extent(size,item==null?1f:item.getScaleX()); }
    public static int extentY(int size, LockScreenItem item) { return size <= 0 ? size : extent(size,item==null?1f:item.getScaleY()); }
    public static void layout(ViewGroup root, int width, int height, float x, float y) {
        float scale = Math.min(factor(x), factor(y));
        float dx = (root.getWidth() - width * scale) / 2f;
        float dy = (root.getHeight() - height * scale) / 2f;
        for (int i=0; i<root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            child.setPivotX(0); child.setPivotY(0);
            child.setScaleX(scale); child.setScaleY(scale);
            child.setTranslationX(dx + child.getLeft() * (scale - 1f));
            child.setTranslationY(dy + child.getTop() * (scale - 1f));
        }
    }
}
