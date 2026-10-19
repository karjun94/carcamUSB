package com.karjun.dmaxlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

/**
 * Car-style compass: a dial that turns so the direction of travel is always at
 * the top (marked by a fixed pointer), with the heading in degrees and as a
 * compass point in the middle.
 */
public class CompassView extends View {
    private static final String[] POINTS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private final Paint mTick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNorth = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPointer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHeading = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPointerPath = new Path();
    private final float mDensity;

    /** Degrees clockwise from north, or NaN when unknown. */
    private float mBearing = Float.NaN;
    private String mSource = "";

    public CompassView(Context context) {
        super(context);
        mDensity = context.getResources().getDisplayMetrics().density;
        mTick.setStrokeCap(Paint.Cap.ROUND);
        mLabel.setTextAlign(Paint.Align.CENTER);
        mLabel.setTypeface(Typeface.DEFAULT_BOLD);
        mNorth.setTextAlign(Paint.Align.CENTER);
        mNorth.setTypeface(Typeface.DEFAULT_BOLD);
        mHeading.setTextAlign(Paint.Align.CENTER);
        mHeading.setTypeface(Typeface.DEFAULT_BOLD);
        mHint.setTextAlign(Paint.Align.CENTER);
    }

    void setColors(int text, int textSecondary, int accent) {
        mTick.setColor(textSecondary);
        mLabel.setColor(text);
        mHeading.setColor(text);
        mHint.setColor(textSecondary);
        mNorth.setColor(0xFFE5484D);
        mPointer.setColor(accent);
        invalidate();
    }

    /** @param bearing degrees from north, NaN if unknown; @param source e.g. "GPS". */
    void setBearing(float bearing, String source) {
        mBearing = bearing;
        mSource = source;
        invalidate();
    }

    static String point(float bearing) {
        return POINTS[Math.round(((bearing % 360) + 360) % 360 / 45f) % 8];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f + 6 * mDensity;
        float r = Math.min(w, h) / 2f - 18 * mDensity;
        if (r <= 0) return;

        // Fixed pointer at the top: the direction the car is going.
        mPointerPath.reset();
        float pw = r * 0.09f;
        mPointerPath.moveTo(cx, cy - r - 2 * mDensity);
        mPointerPath.lineTo(cx - pw, cy - r - pw * 1.8f);
        mPointerPath.lineTo(cx + pw, cy - r - pw * 1.8f);
        mPointerPath.close();
        canvas.drawPath(mPointerPath, mPointer);

        boolean known = !Float.isNaN(mBearing);
        canvas.save();
        if (known) canvas.rotate(-mBearing, cx, cy);
        for (int deg = 0; deg < 360; deg += 10) {
            boolean major = deg % 90 == 0;
            boolean mid = deg % 30 == 0;
            float len = r * (major ? 0.14f : mid ? 0.09f : 0.05f);
            mTick.setStrokeWidth((major ? 3f : 1.6f) * mDensity);
            canvas.save();
            canvas.rotate(deg, cx, cy);
            canvas.drawLine(cx, cy - r, cx, cy - r + len, mTick);
            canvas.restore();
        }
        mLabel.setTextSize(r * 0.17f);
        mNorth.setTextSize(r * 0.19f);
        for (int i = 0; i < 4; i++) {
            canvas.save();
            canvas.rotate(i * 90, cx, cy);
            Paint p = i == 0 ? mNorth : mLabel;
            // Keep letters upright relative to the screen.
            float ly = cy - r * 0.74f;
            canvas.rotate(-(i * 90) + (known ? mBearing : 0), cx, ly);
            canvas.drawText(POINTS[i * 2], cx, ly + p.getTextSize() * 0.35f, p);
            canvas.restore();
        }
        canvas.restore();

        // Centre read-out.
        mHeading.setTextSize(r * 0.24f);
        mHint.setTextSize(r * 0.1f);
        if (known) {
            int deg = Math.round(((mBearing % 360) + 360) % 360) % 360;
            canvas.drawText(deg + "° " + point(mBearing), cx, cy + mHeading.getTextSize() * 0.35f, mHeading);
            canvas.drawText(mSource, cx, cy + r * 0.34f, mHint);
        } else {
            canvas.drawText("--", cx, cy + mHeading.getTextSize() * 0.35f, mHeading);
            canvas.drawText("Waiting for", cx, cy + r * 0.3f, mHint);
            canvas.drawText("heading", cx, cy + r * 0.3f + mHint.getTextSize() * 1.15f, mHint);
        }
    }
}
