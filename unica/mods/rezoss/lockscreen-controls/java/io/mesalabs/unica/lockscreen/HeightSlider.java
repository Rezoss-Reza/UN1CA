// SPDX-License-Identifier: GPL-3.0-or-later
package io.mesalabs.unica.lockscreen;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import androidx.appcompat.widget.SeslSeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import com.samsung.android.lockscreen.editor.item.domain.entity.common.MutableObservableData;
import com.samsung.android.lockscreen.editor.item.domain.entity.item.clock.ClockData;
import com.samsung.android.lockscreen.editor.item.domain.entity.item.clock.ClockStretchData;

/** Uses the native clock profile so Done/Cancel retain their normal semantics. */
public final class HeightSlider extends LinearLayout implements SeslSeekBar.OnSeekBarChangeListener {
    private SeslSeekBar slider;
    private MutableObservableData repository;
    private boolean refreshing;
    private Object model;
    public HeightSlider(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
    }
    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        slider = (SeslSeekBar) getChildAt(0);
        slider.setMax(300);
        slider.setOnSeekBarChangeListener(this);
    }
    /** Reserve the added native control row in the initial collapsed clock panel. */
    public static Integer panelHeight(View panel, Integer nativeHeight) {
        float density = panel.getResources().getDisplayMetrics().density;
        int base = nativeHeight == null ? Math.round(283 * density) : nativeHeight;
        return base + Math.round(54 * density);
    }
    public static void bind(View root, Object repository, Object model) {
        HeightSlider view = root.findViewWithTag("unica_clock_height");
        if (view != null) {
            view.repository = (MutableObservableData) repository;
            view.model = model;
            view.refresh();
        }
    }
    private ClockData clock() { return repository == null ? null : (ClockData) repository.getData(); }
    private void refresh() {
        ClockData data = clock();
        boolean enabled = data != null && data.getStretchEnabled();
        slider.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.4f);
        List<Float> heights = enabled ? data.getClockFontData().getClockStretchData().getFontHeight() : null;
        if (!slider.isPressed()) {
            refreshing = true;
            float height = heights == null || heights.isEmpty() ? 1f : heights.get(0);
            slider.setProgress(Math.round((Math.max(1f, Math.min(4f, height)) - 1f) * 100));
            refreshing = false;
        }
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // The native picker changes templates without recreating its panel.
        post(refreshState);
    }
    private final Runnable refreshState = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow()) return;
            refresh();
            postDelayed(this, 250);
        }
    };
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(refreshState);
        super.onDetachedFromWindow();
    }
    @Override public void onProgressChanged(SeslSeekBar bar, int progress, boolean fromUser) {
        if (!fromUser || refreshing) return;
        ClockData data = clock();
        if (data == null || !data.getStretchEnabled()) return;
        ClockStretchData stretch = data.getClockFontData().getClockStretchData();
        List<Float> previous = stretch.getFontHeight();
        int count = previous == null ? 0 : previous.size();
        if (count == 0 && stretch.getOriginalStretchMeasureData() != null
                && stretch.getOriginalStretchMeasureData().getMinRects() != null)
            count = stretch.getOriginalStretchMeasureData().getMinRects().size();
        if (count == 0) return; // Wait for the renderer to report actual glyph count.
        float ratio = 1f + progress / 100f;
        ArrayList<Float> heights = new ArrayList<>();
        for (int i = 0; i < count; i++) heights.add(ratio);
        stretch.unicaHeight = ratio;
        stretch.unicaFont = data.getClockFontData().getClockFontType();
        stretch.unicaType = data.getClockTypeData().getClockType();
        stretch.setFontHeight(heights);
        repository.setData(data, true);
    }
    /** Automatic wallpaper suggestions must not immediately undo a manual slider edit. */
    public static List<Float> retain(ClockData data, List<Float> suggested) {
        ClockStretchData stretch = data.getClockFontData().getClockStretchData();
        if (stretch.unicaHeight <= 0 || stretch.unicaFont != data.getClockFontData().getClockFontType()
                || stretch.unicaType != data.getClockTypeData().getClockType()
                || stretch.getFontHeight() == null) {
            stretch.unicaHeight = 0;
            return suggested;
        }
        return stretch.getFontHeight();
    }
    @Override public void onStartTrackingTouch(SeslSeekBar bar) { EditorHooks.tracking(model, true); }
    @Override public void onStopTrackingTouch(SeslSeekBar bar) { EditorHooks.tracking(model, false); refresh(); }
}
