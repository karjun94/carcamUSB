package com.karjun.dmaxcam.core;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * Parking guide lines: a left and a right line, each split into red / yellow /
 * green distance bands (near to far), joined at the bottom by a dashed line
 * that marks the car's bumper edge.
 *
 * Every line endpoint is stored separately (as fractions of the view), so in
 * edit mode each line can be moved, lengthened or tilted on its own to match a
 * camera that is mounted off-centre or at an angle.
 */
public class GuideLinesView extends View {
    /** Index of each endpoint's x in the points array; y follows at +1. */
    public static final int LEFT_BOTTOM = 0, LEFT_TOP = 2, RIGHT_BOTTOM = 4, RIGHT_TOP = 6;
    public static final float[] DEFAULT_POINTS = {
            0.12f, 0.95f, 0.34f, 0.45f, // left line: bottom, top
            0.88f, 0.95f, 0.66f, 0.45f, // right line: bottom, top
    };

    private static final int[] BAND_COLORS = {Color.RED, Color.YELLOW, Color.GREEN};
    private static final float TICK_FRACTION = 0.06f;

    private final float mDensity;
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHandle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHandleRing = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float[] mPoints = DEFAULT_POINTS.clone();
    private boolean mEditing;
    private int mDragging = -1;
    private Runnable mOnChanged;

    public GuideLinesView(Context context) {
        super(context);
        mDensity = context.getResources().getDisplayMetrics().density;
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeCap(Paint.Cap.ROUND);
        mLine.setStrokeWidth(6 * mDensity);
        mEdge.setStyle(Paint.Style.STROKE);
        mEdge.setColor(0xCCFFFFFF);
        mEdge.setStrokeWidth(3 * mDensity);
        mEdge.setPathEffect(new DashPathEffect(new float[]{14 * mDensity, 10 * mDensity}, 0));
        mHandle.setColor(0x992EC4B6);
        mHandleRing.setStyle(Paint.Style.STROKE);
        mHandleRing.setColor(Color.WHITE);
        mHandleRing.setStrokeWidth(3 * mDensity);
    }

    public void setPoints(float[] points) {
        mPoints = points.clone();
        invalidate();
    }

    public float[] getPoints() {
        return mPoints.clone();
    }

    /** In edit mode the endpoints show as handles and can be dragged. */
    public void setEditing(boolean editing) {
        mEditing = editing;
        invalidate();
    }

    /** Called when a drag finishes, so the new positions can be saved. */
    public void setOnChanged(Runnable onChanged) {
        mOnChanged = onChanged;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        drawLine(canvas, LEFT_BOTTOM, LEFT_TOP, 1, w, h);
        drawLine(canvas, RIGHT_BOTTOM, RIGHT_TOP, -1, w, h);
        canvas.drawLine(x(LEFT_BOTTOM, w), y(LEFT_BOTTOM, h), x(RIGHT_BOTTOM, w), y(RIGHT_BOTTOM, h), mEdge);

        if (mEditing) {
            float r = 18 * mDensity;
            for (int i = 0; i < mPoints.length; i += 2) {
                canvas.drawCircle(x(i, w), y(i, h), r, mHandle);
                canvas.drawCircle(x(i, w), y(i, h), r, mHandleRing);
            }
        }
    }

    /** @param inward +1 if ticks point right (left line), -1 if they point left. */
    private void drawLine(Canvas canvas, int bottom, int top, int inward, float w, float h) {
        float bx = x(bottom, w), by = y(bottom, h);
        float tx = x(top, w), ty = y(top, h);
        float tick = w * TICK_FRACTION * inward;
        int bands = BAND_COLORS.length;
        for (int i = 0; i < bands; i++) {
            float t0 = (float) i / bands;
            float t1 = (float) (i + 1) / bands;
            float x0 = lerp(bx, tx, t0), y0 = lerp(by, ty, t0);
            float x1 = lerp(bx, tx, t1), y1 = lerp(by, ty, t1);
            mLine.setColor(BAND_COLORS[i]);
            canvas.drawLine(x0, y0, x1, y1, mLine);
            // Ticks shrink with distance, like the lines themselves.
            canvas.drawLine(x1, y1, x1 + tick * (1f - 0.4f * t1), y1, mLine);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mEditing) return false;
        float w = getWidth(), h = getHeight();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDragging = nearestHandle(event.getX(), event.getY(), w, h);
                return mDragging >= 0;
            case MotionEvent.ACTION_MOVE:
                if (mDragging < 0) return false;
                mPoints[mDragging] = clamp(event.getX() / w);
                mPoints[mDragging + 1] = clamp(event.getY() / h);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDragging >= 0 && mOnChanged != null) mOnChanged.run();
                mDragging = -1;
                return true;
            default:
                return mDragging >= 0;
        }
    }

    private int nearestHandle(float px, float py, float w, float h) {
        float reach = 48 * mDensity;
        int best = -1;
        float bestDist = reach * reach;
        for (int i = 0; i < mPoints.length; i += 2) {
            float dx = x(i, w) - px, dy = y(i, h) - py;
            float d = dx * dx + dy * dy;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private float x(int i, float w) {
        return mPoints[i] * w;
    }

    private float y(int i, float h) {
        return mPoints[i + 1] * h;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
