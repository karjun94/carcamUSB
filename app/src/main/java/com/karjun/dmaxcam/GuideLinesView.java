package com.karjun.dmaxcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * Static parking guide lines: two lines converging towards the horizon, with
 * red / yellow / green distance bands (near to far). Not calibrated; they only
 * show the vehicle's path for a centred, level camera.
 */
public class GuideLinesView extends View {
    // Fractions of the view: where the lines start (bottom) and end (top of the far band).
    private static final float BOTTOM_HALF_WIDTH = 0.38f;
    private static final float TOP_HALF_WIDTH = 0.16f;
    private static final float TOP_Y = 0.45f;
    private static final int[] BAND_COLORS = {Color.RED, Color.YELLOW, Color.GREEN};

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public GuideLinesView(Context context) {
        super(context);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeWidth(6 * context.getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        int bands = BAND_COLORS.length;
        for (int i = 0; i < bands; i++) {
            // Each band covers an equal slice of the height between the bottom and TOP_Y.
            float t0 = (float) i / bands;
            float t1 = (float) (i + 1) / bands;
            float y0 = lerp(h, h * TOP_Y, t0);
            float y1 = lerp(h, h * TOP_Y, t1);
            float half0 = w * lerp(BOTTOM_HALF_WIDTH, TOP_HALF_WIDTH, t0);
            float half1 = w * lerp(BOTTOM_HALF_WIDTH, TOP_HALF_WIDTH, t1);
            mPaint.setColor(BAND_COLORS[i]);
            canvas.drawLine(cx - half0, y0, cx - half1, y1, mPaint);
            canvas.drawLine(cx + half0, y0, cx + half1, y1, mPaint);
            // Short tick at the far edge of the band, pointing inwards.
            float tick = half1 * 0.25f;
            canvas.drawLine(cx - half1, y1, cx - half1 + tick, y1, mPaint);
            canvas.drawLine(cx + half1, y1, cx + half1 - tick, y1, mPaint);
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
