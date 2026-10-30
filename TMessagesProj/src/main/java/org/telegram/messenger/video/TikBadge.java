package org.telegram.messenger.video;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * TikBadge — builds the "Shared Media" badge bitmap that Tikgram burns
 * into the frames of gallery-picked round videos.
 *
 * Drop-in path:
 *   TMessagesProj/src/main/java/org/telegram/messenger/video/TikBadge.java
 *
 * The badge is created once per conversion and uploaded as a GL texture
 * by TextureRenderer. There is deliberately NO flag, NO toggle and NO
 * code path that skips it: whenever a gallery video is converted to a
 * round video note, the badge is in every encoded frame. Removing it
 * requires editing this file and the TextureRenderer draw call — it
 * cannot be switched off from TikConfig or from the UI.
 *
 * Text is drawn with Canvas/Paint so Arabic shaping applies ("وسائط").
 */
public class TikBadge {

    public static final String BADGE_TEXT = "وسائط / Shared Media";

    public static Bitmap buildBadgeBitmap() {
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(52f);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        float textWidth = textPaint.measureText(BADGE_TEXT);
        int padX = 40;
        int width = (int) (textWidth + padX * 2);
        int height = 112;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(0xB3000000);
        canvas.drawRoundRect(new RectF(0, 0, width, height), 56, 56, bgPaint);
        canvas.drawText(BADGE_TEXT, width / 2f,
                height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint);
        return bitmap;
    }
}
