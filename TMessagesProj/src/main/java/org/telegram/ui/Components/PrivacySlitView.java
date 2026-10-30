package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.TikConfigManager;

/**
 * PrivacySlitView — Sheikh & Guard screen shield for Tikgram.
 *
 * Drop-in path:
 *   TMessagesProj/src/main/java/org/telegram/ui/Components/PrivacySlitView.java
 *
 * A full-screen blackout overlay with one clear horizontal band (the
 * "slit") the user reads through. The band is draggable vertically;
 * dragging near its top/bottom edge resizes it. Double-tap anywhere
 * dismisses the shield and turns the flag off.
 *
 * Rendering: the view draws black over everything, then punches the
 * slit out with a CLEAR xfermode pass. That requires a software layer
 * (set in the constructor) — otherwise CLEAR does nothing under
 * hardware acceleration.
 *
 * Opacity and initial band size come from TikConfig flags
 * (privacy_slit_opacity 0-100, privacy_slit_size 20-60 % of height).
 * Touches outside the slit are consumed (the shield blocks interaction
 * with the chat underneath by design); double-tap is the exit.
 */
public class PrivacySlitView extends View {

    private final Paint blackPaint = new Paint();
    private final Paint clearPaint = new Paint();

    private float slitTop;
    private float slitBottom;
    private boolean initialized;

    private int dragMode; // 0 none, 1 move, 2 resize-top, 3 resize-bottom
    private float lastY;
    private long lastTapUp;
    private static final float EDGE = 28f; // dp, edge grab zone

    public PrivacySlitView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        setWillNotDraw(false);
    }

    private void ensureInit() {
        if (initialized) {
            return;
        }
        initialized = true;
        TikConfigManager cfg = TikConfigManager.getInstance();
        float sizeFraction = Math.max(20, Math.min(60, cfg.getInt("privacy_slit_size"))) / 100f;
        float h = getHeight() > 0 ? getHeight() : AndroidUtilities.displaySize.y;
        float band = h * sizeFraction;
        slitTop = (h - band) / 2f;
        slitBottom = slitTop + band;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        ensureInit();
        TikConfigManager cfg = TikConfigManager.getInstance();
        int opacity = Math.max(0, Math.min(100, cfg.getInt("privacy_slit_opacity")));
        blackPaint.setColor((opacity * 255 / 100) << 24);
        int save = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        canvas.drawRect(0, 0, getWidth(), getHeight(), blackPaint);
        canvas.drawRect(0, slitTop, getWidth(), slitBottom, clearPaint);
        canvas.restoreToCount(save);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float y = event.getY();
        float edgePx = EDGE * getResources().getDisplayMetrics().density;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (Math.abs(y - slitTop) <= edgePx) {
                    dragMode = 2;
                } else if (Math.abs(y - slitBottom) <= edgePx) {
                    dragMode = 3;
                } else if (y > slitTop && y < slitBottom) {
                    dragMode = 1;
                } else {
                    dragMode = 0;
                }
                lastY = y;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragMode == 0) {
                    return true;
                }
                float dy = y - lastY;
                lastY = y;
                float minBand = getHeight() * 0.1f;
                if (dragMode == 1) {
                    float band = slitBottom - slitTop;
                    slitTop = Math.max(0, Math.min(getHeight() - band, slitTop + dy));
                    slitBottom = slitTop + band;
                } else if (dragMode == 2) {
                    slitTop = Math.max(0, Math.min(slitBottom - minBand, slitTop + dy));
                } else if (dragMode == 3) {
                    slitBottom = Math.min(getHeight(), Math.max(slitTop + minBand, slitBottom + dy));
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                long now = System.currentTimeMillis();
                if (now - lastTapUp < 300 && dragMode == 0) {
                    // Double-tap outside the slit: dismiss the shield.
                    TikConfigManager.getInstance().setBoolean("privacy_slit", false);
                    setVisibility(GONE);
                }
                lastTapUp = now;
                dragMode = 0;
                return true;
            default:
                return true;
        }
    }
}
