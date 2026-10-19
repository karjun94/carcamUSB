package com.karjun.dmaxlauncher;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Scroller;

/**
 * Minimal horizontal pager (no AndroidX on this project): children are laid
 * out side by side, each one screen wide. Horizontal drags move between them
 * and snap to the nearest page; taps still reach the children.
 */
public class SwipePager extends ViewGroup {
    interface OnPageChanged {
        void onPageChanged(int page);
    }

    private static final int SNAP_MS = 280;

    private final Scroller mScroller;
    private final int mTouchSlop;
    private final int mMinFlingVelocity;
    private VelocityTracker mVelocity;
    private float mDownX, mDownY, mLastX;
    private boolean mDragging;
    private int mPage;
    private OnPageChanged mListener;

    public SwipePager(Context context) {
        super(context);
        mScroller = new Scroller(context);
        ViewConfiguration vc = ViewConfiguration.get(context);
        mTouchSlop = vc.getScaledTouchSlop();
        mMinFlingVelocity = vc.getScaledMinimumFlingVelocity() * 4;
    }

    void setOnPageChanged(OnPageChanged listener) {
        mListener = listener;
    }

    int getPage() {
        return mPage;
    }

    void setPage(int page, boolean animate) {
        page = Math.max(0, Math.min(page, getChildCount() - 1));
        int dx = page * getWidth() - getScrollX();
        if (!mScroller.isFinished()) mScroller.abortAnimation();
        if (animate) {
            mScroller.startScroll(getScrollX(), 0, dx, 0, SNAP_MS);
            postInvalidateOnAnimation();
        } else {
            scrollTo(page * getWidth(), 0);
        }
        if (page != mPage) {
            mPage = page;
            if (mListener != null) mListener.onPageChanged(page);
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int childW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int childH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(childW, childH);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t);
        // Keep the current page in view after a resize or when pages are added/removed.
        if (mPage >= getChildCount()) mPage = Math.max(0, getChildCount() - 1);
        if (mScroller.isFinished()) scrollTo(mPage * w, 0);
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
            scrollTo(mScroller.getCurrX(), 0);
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = mLastX = ev.getX();
                mDownY = ev.getY();
                mDragging = !mScroller.isFinished();
                trackVelocity(ev);
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(ev.getX() - mDownX);
                float dy = Math.abs(ev.getY() - mDownY);
                if (dx > mTouchSlop && dx > dy) mDragging = true;
                trackVelocity(ev);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                break;
        }
        return mDragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        trackVelocity(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!mScroller.isFinished()) mScroller.abortAnimation();
                mDownX = mLastX = ev.getX();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float delta = mLastX - ev.getX();
                mLastX = ev.getX();
                int max = (getChildCount() - 1) * getWidth();
                int x = (int) Math.max(0, Math.min(max, getScrollX() + delta));
                scrollTo(x, 0);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                mVelocity.computeCurrentVelocity(1000);
                float vx = mVelocity.getXVelocity();
                int target = Math.round((float) getScrollX() / getWidth());
                if (vx < -mMinFlingVelocity) target = (int) Math.ceil((float) getScrollX() / getWidth());
                else if (vx > mMinFlingVelocity) target = (int) Math.floor((float) getScrollX() / getWidth());
                setPage(target, true);
                mVelocity.recycle();
                mVelocity = null;
                mDragging = false;
                return true;
            }
        }
        return true;
    }

    private void trackVelocity(MotionEvent ev) {
        if (mVelocity == null) mVelocity = VelocityTracker.obtain();
        mVelocity.addMovement(ev);
    }

}
