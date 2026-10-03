// SPDX-License-Identifier: GPL-3.0-or-later
package io.mesalabs.unica.lockscreen;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.samsung.android.lockscreen.editor.item.domain.entity.common.MutableObservableData;
import com.samsung.android.lockscreen.editor.item.domain.entity.item.widget.WidgetData;
import com.samsung.android.sdk.aodservice.entities.item.LockScreenItem;
public final class WidgetControls extends LinearLayout {
    private final SeekBar width, height;
    private MutableObservableData repository;
    private View preview;
    public WidgetControls(Context c,AttributeSet a) {
        super(c,a); setOrientation(VERTICAL);
        int padding=Math.round(24*getResources().getDisplayMetrics().density);
        setPadding(padding,0,padding,0);
        width=addSlider("Widget container width",true);
        height=addSlider("Widget container height",false);
    }
    private SeekBar addSlider(String label,final boolean horizontal) {
        TextView title=new TextView(getContext()); title.setText(label); addView(title,new LayoutParams(-1,-2));
        SeekBar slider=new SeekBar(getContext()); slider.setMax(75); slider.setContentDescription(label);
        addView(slider,new LayoutParams(-1,Math.round(48*getResources().getDisplayMetrics().density)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int value,boolean user) {
                if(!user || repository==null) return;
                WidgetData data=(WidgetData)repository.getData();
                if(horizontal) data.unicaWidth=.5f+value/100f; else data.unicaHeight=.5f+value/100f;
                repository.setData(data,true);
                if(preview instanceof ResizeFrame) ((ResizeFrame)preview).bind(data);
                if(preview!=null) preview.requestLayout();
            }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { }
        });
        return slider;
    }
    public static void bind(View root,Object repository,View preview) {
        WidgetControls controls=root.findViewWithTag("unica_widget_size");
        if(controls==null) return;
        controls.repository=(MutableObservableData)repository;
        controls.preview=preview != null && preview.getParent() instanceof ResizeFrame ? (View)preview.getParent() : preview;
        WidgetData data=(WidgetData)controls.repository.getData();
        controls.width.setProgress(Math.round((ResizeGeometry.factor(data.unicaWidth)-.5f)*100));
        controls.height.setProgress(Math.round((ResizeGeometry.factor(data.unicaHeight)-.5f)*100));
        if(controls.preview instanceof ResizeFrame) ((ResizeFrame)controls.preview).bind(data);
    }
    public static void save(WidgetData data,LockScreenItem.Builder builder) {
        builder.setScaleX(ResizeGeometry.factor(data.unicaWidth));
        builder.setScaleY(ResizeGeometry.factor(data.unicaHeight));
    }
    public static void load(WidgetData data,LockScreenItem item) {
        if(item!=null) {data.unicaWidth=ResizeGeometry.factor(item.getScaleX());data.unicaHeight=ResizeGeometry.factor(item.getScaleY());}
    }
    public static android.graphics.Rect bounds(WidgetData data, android.graphics.Rect original) {
        if (original == null) return null;
        return new android.graphics.Rect(original.left, original.top,
                original.left + ResizeGeometry.extent(original.width(), data.unicaWidth),
                original.top + ResizeGeometry.extent(original.height(), data.unicaHeight));
    }
    public static void copy(WidgetData source,WidgetData target) {target.unicaWidth=source.unicaWidth;target.unicaHeight=source.unicaHeight;}
}
