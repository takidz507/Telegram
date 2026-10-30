package org.telegram.ui.Components;

import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;

/**
 * TikGlass — iOS-style Liquid Glass surfaces for Tikgram.
 *
 * Drop-in path:
 *   TMessagesProj/src/main/java/org/telegram/ui/Components/TikGlass.java
 *
 * apply() gives a view a translucent tint plus a specular hairline
 * border, and — on Android 12+ (API 31) — a real backdrop blur via
 * RenderEffect. Below API 31 the same call degrades gracefully to the
 * translucent tint + border only (no crash, no blur).
 *
 * NOTE: setRenderEffect blurs what is rendered behind the view in the
 * same window. It is applied to the chat action bar and the input bar,
 * which sit on top of the message list — that is what produces the
 * glass effect. If a later theme update resets a view's background,
 * the glass must be re-applied (ChatActivity does so in createView).
 */
public class TikGlass {

    /**
     * @param view        target view (action bar, input bar, ...)
     * @param radiusDp    blur radius, roughly in dp (0 = no blur)
     * @param borderAlpha hairline border alpha, 0-255
     */
    public static void apply(View view, int radiusDp, int borderAlpha) {
        if (view == null) {
            return;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x3D000000);
        bg.setStroke(1, (borderAlpha << 24) | 0x00FFFFFF);
        view.setBackground(bg);
        if (radiusDp > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                float density = view.getResources().getDisplayMetrics().density;
                float radiusPx = Math.max(1f, radiusDp * density);
                view.setRenderEffect(RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP));
            } catch (Exception ignore) {
                // RenderEffect unavailable on this device — tint + border remain.
            }
        }
    }
}
