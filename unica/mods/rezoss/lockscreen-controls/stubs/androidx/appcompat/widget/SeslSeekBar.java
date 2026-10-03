package androidx.appcompat.widget;
public class SeslSeekBar extends android.view.View {
    public SeslSeekBar(android.content.Context context) { super(context); }
    public void setMax(int max) {}
    public void setProgress(int progress) {}
    public void setOnSeekBarChangeListener(OnSeekBarChangeListener listener) {}
    public interface OnSeekBarChangeListener {
        void onProgressChanged(SeslSeekBar bar, int progress, boolean fromUser);
        void onStartTrackingTouch(SeslSeekBar bar);
        void onStopTrackingTouch(SeslSeekBar bar);
    }
}
