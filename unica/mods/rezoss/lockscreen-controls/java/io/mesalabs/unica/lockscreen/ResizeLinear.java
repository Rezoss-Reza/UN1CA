// SPDX-License-Identifier: GPL-3.0-or-later
package io.mesalabs.unica.lockscreen;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;
import com.samsung.android.sdk.aodservice.entities.item.LockScreenItem;
public class ResizeLinear extends LinearLayout {
    private float x=1f,y=1f;
    private int baseWidth,baseHeight;
    public ResizeLinear(Context c) { super(c); }
    public ResizeLinear(Context c,AttributeSet a) { super(c,a); }
    public ResizeLinear(Context c,AttributeSet a,int style) { super(c,a,style); }
    public ResizeLinear(Context c,AttributeSet a,int style,int res) { super(c,a,style,res); }
    public void setUnicaItem(LockScreenItem item) {
        x=item==null?1f:ResizeGeometry.factor(item.getScaleX());
        y=item==null?1f:ResizeGeometry.factor(item.getScaleY());
    }
    @Override protected void onMeasure(int width,int height) {
        super.onMeasure(width,height);
        baseWidth=getMeasuredWidth(); baseHeight=getMeasuredHeight();
        setMeasuredDimension(ResizeGeometry.extent(baseWidth,x),ResizeGeometry.extent(baseHeight,y));
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        super.onLayout(changed,l,t,l+baseWidth,t+baseHeight);
        ResizeGeometry.layout(this,baseWidth,baseHeight,x,y);
    }
}
