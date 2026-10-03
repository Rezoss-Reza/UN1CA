// SPDX-License-Identifier: GPL-3.0-or-later
package io.mesalabs.unica.lockscreen;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import com.samsung.android.lockscreen.editor.item.domain.entity.item.widget.WidgetData;
public final class ResizeFrame extends FrameLayout {
    private WidgetData data;
    private int baseWidth, baseHeight;
    public ResizeFrame(Context context, AttributeSet attrs) { super(context, attrs); }
    public void bind(WidgetData data) { this.data=data; requestLayout(); }
    private float x() { return data == null ? 1f : ResizeGeometry.factor(data.unicaWidth); }
    private float y() { return data == null ? 1f : ResizeGeometry.factor(data.unicaHeight); }
    @Override protected void onMeasure(int width, int height) {
        super.onMeasure(width, height);
        baseWidth=getMeasuredWidth(); baseHeight=getMeasuredHeight();
        setMeasuredDimension(ResizeGeometry.extent(baseWidth,x()),ResizeGeometry.extent(baseHeight,y()));
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        super.onLayout(changed,l,t,l+baseWidth,t+baseHeight);
        ResizeGeometry.layout(this,baseWidth,baseHeight,x(),y());
        // The editor selection border follows the frame, not the fitted content.
        for (int i=0;i<getChildCount();i++) {
            android.view.View child=getChildAt(i);
            if ("binding_1".equals(child.getTag())) {
                child.setScaleX(1f); child.setScaleY(1f);
                child.setTranslationX(0f); child.setTranslationY(0f);
                child.layout(0,0,getWidth(),getHeight());
            }
        }
    }
}
