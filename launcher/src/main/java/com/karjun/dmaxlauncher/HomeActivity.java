package com.karjun.dmaxlauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import com.karjun.dmaxcam.core.CameraActions;
import com.karjun.dmaxcam.core.GuideEditBar;
import com.karjun.dmaxcam.core.GuideLinesView;
import com.karjun.dmaxcam.core.KeyWatchService;
import com.karjun.dmaxcam.core.Prefs;
import com.karjun.dmaxcam.core.SettingsActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Car home screen: big clock, GPS speed, now playing, and app shortcuts. */
public class HomeActivity extends Activity implements LocationListener, android.hardware.SensorEventListener {
    private static final int REQ_LOCATION = 1;
    private static final long TICK_MS = 30_000;
    /** No GPS fix for this long and the speed shows "--". */
    private static final long SPEED_STALE_MS = 5_000;
    /** Below this, treat GPS jitter as standing still. */
    private static final float STOPPED_KMH = 3f;
    // Fallback for the day/night calculation before the first GPS fix: Kuching.
    private static final double DEFAULT_LAT = 1.55, DEFAULT_LNG = 110.34;

    private static final int THEME_AUTO = 0, THEME_DAY = 1, THEME_NIGHT = 2;
    private static final String[] THEME_LABELS = {"AUTO", "DAY", "NIGHT"};

    /** Shortcut slots; the last one always opens the app list. */
    private static final int SLOT_CAMERA = 3;
    private static final String[] SLOT_NAMES = {"Phone", "Navigation", "Radio", "Camera", "Settings"};
    /** First installed package wins. Long-press a tile to pick something else. */
    private static final String[][] SLOT_DEFAULTS = {
            {"com.ucarhu.carlink", "com.imotor.phoneconnect", "com.carletter.car", "com.google.android.projection.gearhead"},
            {"com.waze", "com.google.android.apps.maps"},
            {"com.imotor.fmam"},
            {}, // empty = CarHome's own front camera
            {"com.android.settings"},
    };

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences mPrefs;
    private Palette mPalette;

    private View mRoot;
    private View mClockCard, mSpeedCard, mMediaCard;
    private TextClock mTime, mDate;
    private ImageButton mThemeButton;
    private TextView mThemeLabel;
    private TextView mSpeed, mSpeedUnit, mGpsStatus;
    private ImageView mArt;
    private TextView mSource, mTitle, mArtist;
    private View mControls;
    private ImageButton mPrev, mPlay, mNext;
    private GridLayout mTiles;
    private SwipePager mPager;
    private LinearLayout mDots;

    private static final int REQ_CAMERA = 2;
    private FrameLayout mCameraCard;
    private CardCamera mCardCamera;
    private ImageButton mSwapButton;
    private FrameLayout mCardHolder;
    private CompassView mCompass;

    /** Lower-left card: speed, camera or compass (cycled by the swap button). */
    private static final int LEFT_SPEED = 0, LEFT_CAMERA = 1, LEFT_COMPASS = 2;
    /** Above this speed the GPS direction of travel is trusted over the compass sensor. */
    private static final float GPS_HEADING_MIN_KMH = 5f;
    private static final long GPS_HEADING_FRESH_MS = 3_000;
    private android.hardware.SensorManager mSensors;
    private float mGpsBearing = Float.NaN;
    private long mGpsBearingAt;
    private float mSensorBearing = Float.NaN;
    private final float[] mRotation = new float[9];
    private final float[] mGravity = new float[3];
    private final float[] mGeomagnetic = new float[3];
    private boolean mHaveGravity, mHaveGeomagnetic;
    private long mLastRotationVectorAt;
    private final float[] mRemapped = new float[9];
    private final float[] mOrientation = new float[3];
    /** Full-screen layer the camera card moves into when expanded. */
    private FrameLayout mExpanded;
    private com.serenegiant.widget.AspectRatioTextureView mPreview;
    private GuideLinesView mGuideLines;
    private GuideEditBar mEditBar;
    private boolean mEditing;
    private boolean mResumed;

    // Navigation card (covers the clock card while Waze / Maps navigates).
    private LinearLayout mNavCard;
    private ImageView mNavArrow;
    private TextView mNavDistance, mNavInstruction, mNavFooter;
    private ImageButton mHomeSettings;

    // Parking mode: armed once above the "close" speed, fires when slowing below "open".
    private boolean mParkingArmed;
    private boolean mParkingOpened;

    private LocationManager mLocationManager;
    private long mLastFixAt;

    private MediaSessionManager mSessionManager;
    private ComponentName mListener;
    private boolean mMediaAllowed;
    private MediaController mController;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            applyTheme();
            updateSpeedStale();
            mHandler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = getSharedPreferences("home", MODE_PRIVATE);

        // Page 0 is the dashboard; swiping left shows pages of app icons (like CarPlay).
        FrameLayout frame = new FrameLayout(this);
        mPager = new SwipePager(this);
        mPager.addView(getLayoutInflater().inflate(R.layout.activity_home, mPager, false));
        frame.addView(mPager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mDots = new LinearLayout(this);
        mDots.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams dotsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(16), Gravity.BOTTOM);
        dotsLp.bottomMargin = dp(2);
        frame.addView(mDots, dotsLp);
        mExpanded = new FrameLayout(this);
        mExpanded.setBackgroundColor(Color.BLACK);
        mExpanded.setVisibility(View.GONE);
        frame.addView(mExpanded, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        mPager.setOnPageChanged(page -> updateDots());
        // App pages depend on the screen size, so (re)build them once it is known.
        frame.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (r - l != oldR - oldL || b - t != oldB - oldT) {
                fitDashboard(r - l, b - t);
                buildAppPages();
            }
        });

        mRoot = frame;
        mClockCard = findViewById(R.id.clock_card);
        mSpeedCard = findViewById(R.id.speed_card);
        mMediaCard = findViewById(R.id.media_card);
        mTime = findViewById(R.id.time);
        mDate = findViewById(R.id.date);
        mThemeButton = findViewById(R.id.theme_button);
        mThemeLabel = findViewById(R.id.theme_label);
        mSpeed = findViewById(R.id.speed);
        mSpeedUnit = findViewById(R.id.speed_unit);
        mGpsStatus = findViewById(R.id.gps_status);
        mArt = findViewById(R.id.art);
        mSource = findViewById(R.id.source);
        mTitle = findViewById(R.id.title);
        mArtist = findViewById(R.id.artist);
        mControls = findViewById(R.id.controls);
        mPrev = findViewById(R.id.prev);
        mPlay = findViewById(R.id.play);
        mNext = findViewById(R.id.next);
        mTiles = findViewById(R.id.tiles);

        mThemeButton.setOnClickListener(v -> {
            int mode = (mPrefs.getInt("theme_mode", THEME_AUTO) + 1) % THEME_LABELS.length;
            mPrefs.edit().putInt("theme_mode", mode).apply();
            applyTheme();
        });

        mPrev.setOnClickListener(v -> {
            if (mController != null) mController.getTransportControls().skipToPrevious();
        });
        mNext.setOnClickListener(v -> {
            if (mController != null) mController.getTransportControls().skipToNext();
        });
        mPlay.setOnClickListener(v -> {
            if (mController == null) return;
            PlaybackState state = mController.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                mController.getTransportControls().pause();
            } else {
                mController.getTransportControls().play();
            }
        });
        mMediaCard.setOnClickListener(v -> {
            if (!mMediaAllowed) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } else if (mController != null) {
                launch(mController.getPackageName());
            }
        });

        fitMediaCard();
        setUpLeftCard();
        setUpClockCardExtras();
        startService(new Intent(this, KeyWatchService.class));

        mSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        mListener = new ComponentName(this, MediaListener.class);
        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        applyTheme();
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildTiles();
        buildAppPages();
        connectMedia();
        startLocation();
        mResumed = true;
        MediaListener.setNavListener(this::showNav);
        showNav(MediaListener.currentNav());
        mPreview.setScaleX(Prefs.isMirror(this) ? -1f : 1f);
        mGuideLines.setPoints(Prefs.getGuidePoints(this));
        if (!handleCameraIntent(getIntent())) showLeftCard();
        setIntent(new Intent(Intent.ACTION_MAIN));
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        mResumed = false;
        MediaListener.setNavListener(null);
        // Keep the full-screen camera "open" while in the background (the steering
        // button's own app, e.g. the dialer for Call, briefly covers us); the stream
        // stops below and restarts on resume. A plain Home press closes it (onNewIntent).
        mCardCamera.stop();
        mSensors.unregisterListener(this);
        mHandler.removeCallbacks(mTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mMediaAllowed) mSessionManager.removeOnActiveSessionsChangedListener(mSessionsListener);
        if (mController != null) mController.unregisterCallback(mMediaCallback);
        mLocationManager.removeUpdates(this);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // We are the home screen: Back closes the big camera, otherwise returns to the dashboard.
        if (mCameraCard.getParent() == mExpanded) {
            setCameraExpanded(false);
            return;
        }
        mPager.setPage(0, true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String action = intent.getAction();
        if (action != null && action.startsWith("com.karjun.dmaxcam.action.")) {
            // Handled in onResume, once the screen is back in front.
            setIntent(intent);
            return;
        }
        // Home pressed while already showing: go back to the dashboard, like CarPlay.
        setCameraExpanded(false);
        mPager.setPage(0, true);
    }

    /**
     * Steering button (TOGGLE), "Open camera" (OPEN) and "Adjust guide lines" (ADJUST)
     * from the shared button watcher and settings screen. Returns true if handled.
     */
    private boolean handleCameraIntent(Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (CameraActions.ACTION_TOGGLE.equals(action)) {
            if (isCameraExpanded() && !mEditing) setCameraExpanded(false);
            else setCameraExpanded(true);
        } else if (CameraActions.ACTION_OPEN.equals(action)) {
            setCameraExpanded(true);
        } else if (CameraActions.ACTION_ADJUST.equals(action)) {
            setCameraExpanded(true);
            setEditing(true);
        } else {
            return false;
        }
        mPager.setPage(0, false);
        return true;
    }

    // ---- Theme ----------------------------------------------------------------------------

    private boolean isNight() {
        int mode = mPrefs.getInt("theme_mode", THEME_AUTO);
        if (mode != THEME_AUTO) return mode == THEME_NIGHT;
        double lat = mPrefs.getFloat("lat", (float) DEFAULT_LAT);
        double lng = mPrefs.getFloat("lng", (float) DEFAULT_LNG);
        return SunTimes.isNight(System.currentTimeMillis(), lat, lng);
    }

    private void applyTheme() {
        Palette p = isNight() ? Palette.NIGHT : Palette.DAY;
        mThemeLabel.setText(THEME_LABELS[mPrefs.getInt("theme_mode", THEME_AUTO)]);
        if (p == mPalette) return;
        mPalette = p;

        mRoot.setBackgroundColor(p.bg);
        getWindow().setStatusBarColor(p.bg);
        getWindow().setNavigationBarColor(p.bg);
        // Dark status-bar icons on the light day background.
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if (p == Palette.DAY) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        else flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().getDecorView().setSystemUiVisibility(flags);
        for (View card : new View[]{mClockCard, mSpeedCard, mMediaCard}) card.setBackground(p.card(this));
        if (mCameraCard != null) {
            if (mCameraCard.getParent() != mExpanded) mCameraCard.setBackground(cameraCardBackground());
            ((TextView) mCameraCard.getChildAt(1)).setTextColor(0xFFE8EDF2);
            mSwapButton.setBackground(p.chip(this));
            mCompass.setBackground(p.card(this));
            mNavDistance.setTextColor(p.text);
            mNavInstruction.setTextColor(p.text);
            mNavFooter.setTextColor(p.textSecondary);
            mHomeSettings.setImageTintList(ColorStateList.valueOf(p.textSecondary));
            mCompass.setColors(p.text, p.textSecondary, p.accent);
            mSwapButton.setImageTintList(ColorStateList.valueOf(p.accent));
        }
        for (TextView t : new TextView[]{mTime, mSpeed, mTitle}) t.setTextColor(p.text);
        for (TextView t : new TextView[]{mDate, mSpeedUnit, mGpsStatus, mArtist}) t.setTextColor(p.textSecondary);
        mSource.setTextColor(p.accent);
        mThemeLabel.setTextColor(p.textSecondary);
        mThemeButton.setImageTintList(ColorStateList.valueOf(p.textSecondary));
        for (ImageButton b : new ImageButton[]{mPrev, mNext}) b.setImageTintList(ColorStateList.valueOf(p.text));
        GradientDrawable playBg = new GradientDrawable();
        playBg.setShape(GradientDrawable.OVAL);
        playBg.setColor(p.accent);
        mPlay.setBackground(playBg);
        mPlay.setImageTintList(ColorStateList.valueOf(p.onAccent));
        buildTiles();
        buildAppPages();
        updateMedia();
    }

    // ---- Shortcuts ------------------------------------------------------------------------

    private String slotPackage(int slot) {
        String saved = mPrefs.getString("slot_" + slot, null);
        if (saved != null) return saved;
        for (String pkg : SLOT_DEFAULTS[slot]) {
            if (getPackageManager().getLaunchIntentForPackage(pkg) != null) return pkg;
        }
        return null;
    }

    private void buildTiles() {
        if (mPalette == null) return;
        mTiles.removeAllViews();
        PackageManager pm = getPackageManager();
        int gap = dp(12);
        for (int slot = 0; slot <= SLOT_NAMES.length; slot++) {
            boolean appsTile = slot == SLOT_NAMES.length;
            String pkg = appsTile ? null : slotPackage(slot);
            Drawable icon = null;
            String label;
            if (appsTile) {
                label = "All apps";
            } else if (pkg != null && AppIcons.launchIntent(this, pkg) != null) {
                icon = AppIcons.icon(this, pkg);
                label = AppIcons.label(this, pkg);
            } else if (slot == SLOT_CAMERA) {
                label = "Front camera";
            } else {
                // Unassigned: tap or hold to pick an app.
                label = "+ " + SLOT_NAMES[slot];
            }

            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setBackground(mPalette.card(this));
            tile.setPadding(dp(8), dp(8), dp(8), dp(8));

            ImageView iv = new ImageView(this);
            if (icon != null) {
                iv.setImageDrawable(icon);
            } else if (!appsTile && slot == SLOT_CAMERA) {
                iv.setImageDrawable(AppIcons.glyphTile(this, com.karjun.dmaxcam.core.R.drawable.ic_videocam));
            } else if (appsTile) {
                iv.setImageDrawable(AppIcons.glyphTile(this, R.drawable.ic_apps));
            } else {
                iv.setImageResource(R.drawable.ic_apps);
                iv.setImageTintList(ColorStateList.valueOf(mPalette.accent));
            }
            // Icon takes whatever height is left after the label, up to 56dp, so short tiles still show the name.
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setAdjustViewBounds(true);
            iv.setMaxHeight(dp(56));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            tile.addView(iv, ilp);

            TextView tv = new TextView(this);
            tv.setText(label);
            tv.setTextColor(mPalette.text);
            tv.setTextSize(15);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(1);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(4);
            tile.addView(tv, tlp);

            final int s = slot;
            if (appsTile) {
                tile.setOnClickListener(v -> mPager.setPage(1, true));
            } else {
                tile.setOnClickListener(v -> {
                    if (pkg != null && launch(pkg)) return;
                    if (s == SLOT_CAMERA) {
                        setCameraExpanded(true);
                        return;
                    }
                    chooseSlotApp(s);
                });
                tile.setOnLongClickListener(v -> {
                    chooseSlotApp(s);
                    return true;
                });
            }

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(slot / 3, 1f), GridLayout.spec(slot % 3, 1f));
            lp.width = 0;
            lp.height = 0;
            lp.setMargins(slot % 3 == 0 ? 0 : gap / 2, slot / 3 == 0 ? 0 : gap / 2,
                    slot % 3 == 2 ? 0 : gap / 2, slot / 3 == 1 ? 0 : gap / 2);
            mTiles.addView(tile, lp);
        }
    }

    /**
     * The music card is the tightest spot on small or squarer screens: drop the
     * artist line if it would be cut in half, and shrink the buttons row if it is
     * wider than the space next to the cover.
     */
    private void fitMediaCard() {
        // Cover art is always square: as tall as the card allows, at most 40% of its width.
        mMediaCard.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l == or - ol && b - t == ob - ot) return;
            int innerW = r - l - v.getPaddingLeft() - v.getPaddingRight();
            int innerH = b - t - v.getPaddingTop() - v.getPaddingBottom();
            int size = Math.max(0, Math.min(innerH, Math.round(innerW * 0.4f)));
            v.post(() -> {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mArt.getLayoutParams();
                if (lp.width == size && lp.height == size && lp.weight == 0) return;
                lp.width = size;
                lp.height = size;
                lp.weight = 0;
                mArt.setLayoutParams(lp);
                updateMedia();
            });
        });
        View text = findViewById(R.id.media_text);
        View column = (View) mControls.getParent();
        text.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int needed = mSource.getHeight() + mTitle.getHeight() + mArtist.getHeight() + dp(8);
            boolean fits = b - t >= needed;
            int want = fits || mArtist.getText().length() == 0 ? View.VISIBLE : View.GONE;
            // Only ever hide on a too-small box, so this cannot flip back and forth.
            if (want == View.GONE && mArtist.getVisibility() != View.GONE) {
                v.post(() -> mArtist.setVisibility(View.GONE));
            }
        });
        column.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l == or - ol) return;
            int available = r - l - v.getPaddingLeft() - v.getPaddingRight();
            // Natural row: 48 + 8 + 60 + 8 + 48 dp.
            float scale = Math.min(1f, available / (float) dp(172));
            v.post(() -> {
                sizeButton(mPrev, 48, scale);
                sizeButton(mPlay, 60, scale);
                sizeButton(mNext, 48, scale);
            });
        });
    }

    private void sizeButton(View button, int sizeDp, float scale) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) button.getLayoutParams();
        int size = Math.round(dp(sizeDp) * scale);
        if (lp.width == size) return;
        lp.width = size;
        lp.height = size;
        button.setLayoutParams(lp);
    }

    // ---- Clock card: settings gear + navigation card ------------------------------------

    private void setUpClockCardExtras() {
        FrameLayout clock = (FrameLayout) mClockCard;

        // Fills the clock card (same padding) while Waze / Maps is navigating.
        mNavCard = new LinearLayout(this);
        mNavCard.setOrientation(LinearLayout.HORIZONTAL);
        mNavCard.setGravity(Gravity.CENTER_VERTICAL);
        mNavCard.setVisibility(View.GONE);
        mNavArrow = new ImageView(this);
        mNavArrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // Maps / Waze draw white arrows: give them a coloured disc to sit on.
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(0xFF1E8F85);
        mNavArrow.setBackground(disc);
        mNavCard.addView(mNavArrow, new LinearLayout.LayoutParams(dp(88), dp(88)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        mNavDistance = new TextView(this);
        mNavDistance.setTypeface(null, android.graphics.Typeface.BOLD);
        mNavDistance.setMaxLines(1);
        mNavDistance.setIncludeFontPadding(false);
        mNavInstruction = new TextView(this);
        mNavInstruction.setTextSize(18);
        mNavInstruction.setMaxLines(2);
        mNavInstruction.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mNavFooter = new TextView(this);
        mNavFooter.setTextSize(14);
        mNavFooter.setMaxLines(1);
        mNavFooter.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mNavFooter.setPadding(0, dp(4), 0, 0);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            mNavDistance.setAutoSizeTextTypeUniformWithConfiguration(24, 56, 2,
                    android.util.TypedValue.COMPLEX_UNIT_SP);
        } else {
            mNavDistance.setTextSize(36);
        }
        texts.addView(mNavDistance, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
        texts.addView(mNavInstruction);
        texts.addView(mNavFooter);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        tlp.setMarginStart(dp(18));
        tlp.setMarginEnd(dp(36)); // room for the settings gear in the corner
        mNavCard.addView(texts, tlp);
        mNavCard.setOnClickListener(v -> {
            MediaListener.Nav nav = MediaListener.currentNav();
            if (nav != null) launch(nav.pkg);
        });
        clock.addView(mNavCard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Arrow disc scales with the card height (phones vs. big head-unit screens).
        clock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t == ob - ot) return;
            int size = Math.max(dp(56), Math.min(dp(120), (int) ((b - t - v.getPaddingTop() - v.getPaddingBottom()) * 0.7f)));
            v.post(() -> {
                ViewGroup.LayoutParams lp = mNavArrow.getLayoutParams();
                if (lp.width == size) return;
                lp.width = size;
                lp.height = size;
                mNavArrow.setLayoutParams(lp);
                mNavArrow.setPadding(size / 6, size / 6, size / 6, size / 6);
            });
        });

        mHomeSettings = new ImageButton(this);
        mHomeSettings.setImageResource(R.drawable.ic_settings);
        mHomeSettings.setContentDescription("CarHome settings");
        mHomeSettings.setBackgroundResource(android.R.drawable.list_selector_background);
        mHomeSettings.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mHomeSettings.setPadding(dp(8), dp(8), dp(8), dp(8));
        mHomeSettings.setOnClickListener(v -> startActivity(new Intent(this, HomeSettingsActivity.class)));
        clock.addView(mHomeSettings, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM | Gravity.END));
    }

    private void showNav(MediaListener.Nav nav) {
        boolean show = nav != null && HomePrefs.navCard(this);
        mNavCard.setVisibility(show ? View.VISIBLE : View.GONE);
        // The clock and its corner buttons make way for the turn while navigating.
        int clockVis = show ? View.INVISIBLE : View.VISIBLE;
        ((ViewGroup) mClockCard).getChildAt(0).setVisibility(clockVis);
        mThemeButton.setVisibility(clockVis);
        mThemeLabel.setVisibility(clockVis);
        // The settings gear stays reachable during a trip (the nav text leaves room for it).
        if (!show) return;
        // Plain text: the apps' own styling (spans) renders oddly at our sizes.
        mNavDistance.setText(nav.distance == null ? "" : nav.distance.toString());
        mNavInstruction.setText(nav.instruction == null ? "" : nav.instruction.toString());
        String now = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date());
        mNavFooter.setText(nav.trip != null ? nav.trip.toString() : now + "  ·  " + appLabel(nav.pkg));
        if (nav.arrow != null) {
            mNavArrow.setImageDrawable(nav.arrow);
        } else {
            try {
                mNavArrow.setImageDrawable(getPackageManager().getApplicationIcon(nav.pkg));
            } catch (PackageManager.NameNotFoundException e) {
                mNavArrow.setImageDrawable(null);
            }
        }
    }

    // ---- Parking mode -----------------------------------------------------------------------

    /**
     * Once you have driven above the "close" speed, slowing below the "open" speed
     * brings up the front camera; speeding up again closes it. A camera the driver
     * opened by hand is never closed automatically.
     */
    private void checkParkingMode(float kmh) {
        if (!HomePrefs.parkingMode(this)) return;
        if (kmh > HomePrefs.parkingHideAbove(this)) {
            mParkingArmed = true;
            if (mParkingOpened && isCameraExpanded()) setCameraExpanded(false);
            mParkingOpened = false;
        } else if (mParkingArmed && kmh < HomePrefs.parkingShowBelow(this)) {
            mParkingArmed = false;
            if (isCameraExpanded()) return;
            mParkingOpened = true;
            if (mResumed) setCameraExpanded(true);
            else CameraActions.send(this, CameraActions.ACTION_OPEN);
        }
    }

    // ---- Speed / camera card --------------------------------------------------------------

    /**
     * The lower-left card shows either GPS speed or a live USB camera preview.
     * The swap button in its corner switches; the choice is remembered.
     */
    private void setUpLeftCard() {
        ViewGroup column = (ViewGroup) mSpeedCard.getParent();
        int index = column.indexOfChild(mSpeedCard);
        ViewGroup.LayoutParams cardLp = mSpeedCard.getLayoutParams();
        column.removeView(mSpeedCard);

        FrameLayout holder = new FrameLayout(this);
        holder.addView(mSpeedCard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mCameraCard = new FrameLayout(this);
        mCameraCard.setClipToOutline(true);
        FrameLayout picture = new FrameLayout(this);
        mPreview = new com.serenegiant.widget.AspectRatioTextureView(this);
        picture.addView(mPreview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mGuideLines = new GuideLinesView(this);
        mGuideLines.setOnChanged(() -> Prefs.setGuidePoints(this, mGuideLines.getPoints()));
        mGuideLines.setVisibility(View.GONE);
        picture.addView(mGuideLines, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mCameraCard.addView(picture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        TextView status = new TextView(this);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        mCameraCard.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mCameraCard.setOnClickListener(v -> {
            if (!mEditing) setCameraExpanded(!isCameraExpanded());
        });
        holder.addView(mCameraCard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mCardCamera = new CardCamera(mPreview, status);

        mCompass = new CompassView(this);
        holder.addView(mCompass, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mSensors = (android.hardware.SensorManager) getSystemService(Context.SENSOR_SERVICE);

        mSwapButton = new ImageButton(this);
        mSwapButton.setImageResource(R.drawable.ic_swap);
        mSwapButton.setContentDescription("Switch speed / camera / compass");
        mSwapButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mSwapButton.setPadding(dp(10), dp(10), dp(10), dp(10));
        mSwapButton.setOnClickListener(v -> setLeftCard((leftMode() + 1) % 3));
        FrameLayout.LayoutParams swapLp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END);
        swapLp.setMargins(0, dp(8), dp(8), 0);
        holder.addView(mSwapButton, swapLp);

        column.addView(holder, index, cardLp);
        mCardHolder = holder;

        // Full-screen extras: guide-line edit bar (top) and camera settings (top right).
        mEditBar = new GuideEditBar(this, mGuideLines, () -> setEditing(false));
        mEditBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        barLp.topMargin = dp(16);
        mExpanded.addView(mEditBar, barLp);

        ImageButton gear = new ImageButton(this);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setContentDescription("Camera settings");
        gear.setScaleType(ImageView.ScaleType.FIT_CENTER);
        gear.setPadding(dp(12), dp(12), dp(12), dp(12));
        gear.setImageTintList(ColorStateList.valueOf(0xFF2EC4B6));
        GradientDrawable gearBg = new GradientDrawable();
        gearBg.setShape(GradientDrawable.OVAL);
        gearBg.setColor(0xB3171B22);
        gear.setBackground(gearBg);
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        FrameLayout.LayoutParams gearLp = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP | Gravity.END);
        gearLp.setMargins(0, dp(16), dp(16), 0);
        mExpanded.addView(gear, gearLp);
        mExpanded.setTag(gear);
    }

    /**
     * Moves the live camera view between its card and the full-screen layer.
     * The camera keeps running; only the view is re-parented, so there is no reopen.
     */
    private boolean isCameraExpanded() {
        return mCameraCard.getParent() == mExpanded;
    }

    private void setCameraExpanded(boolean expand) {
        boolean expanded = isCameraExpanded();
        if (expand == expanded) return;
        if (!expand) {
            setEditing(false);
            mParkingOpened = false;
        }
        ((ViewGroup) mCameraCard.getParent()).removeView(mCameraCard);
        if (expand) {
            mExpanded.addView(mCameraCard, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // Edge to edge: no card corners or border while full screen.
            mCameraCard.setBackgroundColor(Color.BLACK);
            mCameraCard.setClipToOutline(false);
            mExpanded.setVisibility(View.VISIBLE);
        } else {
            mExpanded.setVisibility(View.GONE);
            // Back in the card, under the swap button.
            mCardHolder.addView(mCameraCard, 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mCameraCard.setBackground(cameraCardBackground());
            mCameraCard.setClipToOutline(true);
        }
        CameraActions.setShowing(expand);
        mPreview.setScaleX(Prefs.isMirror(this) ? -1f : 1f);
        updateGuideLines();
        // Starts the camera if the card was not showing it, or stops it again on collapse.
        showLeftCard();
    }

    /** Guide lines only in the full-screen view: when switched on, or while adjusting. */
    private void updateGuideLines() {
        boolean show = isCameraExpanded() && (mEditing || Prefs.showGuideLines(this));
        mGuideLines.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void setEditing(boolean editing) {
        mEditing = editing;
        mGuideLines.setEditing(editing);
        mEditBar.setVisibility(editing ? View.VISIBLE : View.GONE);
        ((View) mExpanded.getTag()).setVisibility(editing ? View.GONE : View.VISIBLE);
        updateGuideLines();
    }

    // ---- Compass ----------------------------------------------------------------------------

    /**
     * Compass-sensor heading, used whenever there is no fresh GPS heading. Listens to
     * both the fused rotation-vector sensor and the plain accelerometer + magnetometer,
     * and takes whichever delivers: either one can be missing or stall on some devices.
     * Many head units have neither; GPS heading still works while driving.
     */
    private void startCompassSensor() {
        int rate = android.hardware.SensorManager.SENSOR_DELAY_UI;
        android.hardware.Sensor rotation = mSensors.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR);
        if (rotation != null) mSensors.registerListener(this, rotation, rate);
        android.hardware.Sensor accel = mSensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
        android.hardware.Sensor magnet = mSensors.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD);
        if (accel != null && magnet != null) {
            mSensors.registerListener(this, accel, rate);
            mSensors.registerListener(this, magnet, rate);
        }
        updateCompass();
    }

    @Override
    public void onSensorChanged(android.hardware.SensorEvent event) {
        switch (event.sensor.getType()) {
            case android.hardware.Sensor.TYPE_ROTATION_VECTOR:
                mLastRotationVectorAt = SystemClock.elapsedRealtime();
                android.hardware.SensorManager.getRotationMatrixFromVector(mRotation, event.values);
                break;
            case android.hardware.Sensor.TYPE_ACCELEROMETER:
                System.arraycopy(event.values, 0, mGravity, 0, 3);
                mHaveGravity = true;
                if (!fromMagnetometer()) return;
                break;
            default:
                System.arraycopy(event.values, 0, mGeomagnetic, 0, 3);
                mHaveGeomagnetic = true;
                if (!fromMagnetometer()) return;
                break;
        }
        // Azimuth relative to the top of the screen, whichever way the display is rotated.
        int x = android.hardware.SensorManager.AXIS_X, y = android.hardware.SensorManager.AXIS_Y;
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case android.view.Surface.ROTATION_90:
                x = android.hardware.SensorManager.AXIS_Y;
                y = android.hardware.SensorManager.AXIS_MINUS_X;
                break;
            case android.view.Surface.ROTATION_180:
                x = android.hardware.SensorManager.AXIS_MINUS_X;
                y = android.hardware.SensorManager.AXIS_MINUS_Y;
                break;
            case android.view.Surface.ROTATION_270:
                x = android.hardware.SensorManager.AXIS_MINUS_Y;
                y = android.hardware.SensorManager.AXIS_X;
                break;
        }
        android.hardware.SensorManager.remapCoordinateSystem(mRotation, x, y, mRemapped);
        android.hardware.SensorManager.getOrientation(mRemapped, mOrientation);
        float azimuth = (float) ((Math.toDegrees(mOrientation[0]) + 360) % 360);
        // Light smoothing, taking the 359 -> 0 wrap into account.
        if (Float.isNaN(mSensorBearing)) {
            mSensorBearing = azimuth;
        } else {
            float diff = ((azimuth - mSensorBearing + 540) % 360) - 180;
            mSensorBearing = (mSensorBearing + diff * 0.2f + 360) % 360;
        }
        updateCompass();
    }

    /** Accelerometer + magnetometer path; skipped while the fused sensor is delivering. */
    private boolean fromMagnetometer() {
        if (SystemClock.elapsedRealtime() - mLastRotationVectorAt < 1000) return false;
        if (!mHaveGravity || !mHaveGeomagnetic) return false;
        return android.hardware.SensorManager.getRotationMatrix(mRotation, null, mGravity, mGeomagnetic);
    }

    @Override
    public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {
    }

    /** GPS direction of travel while moving; otherwise the compass sensor, if any. */
    private void updateCompass() {
        if (mCompass == null || mCompass.getVisibility() != View.VISIBLE) return;
        boolean gpsFresh = !Float.isNaN(mGpsBearing)
                && SystemClock.elapsedRealtime() - mGpsBearingAt < GPS_HEADING_FRESH_MS;
        if (gpsFresh) mCompass.setBearing(mGpsBearing, "GPS heading");
        else if (!Float.isNaN(mSensorBearing)) mCompass.setBearing(mSensorBearing, "Compass sensor");
        else mCompass.setBearing(Float.NaN, "");
    }

    /** Black card with the theme's rounded corners and border. */
    private GradientDrawable cameraCardBackground() {
        GradientDrawable bg = mPalette.card(this);
        bg.setColor(Color.BLACK);
        return bg;
    }

    private int leftMode() {
        // 0.2 stored a camera on/off flag; carry it over.
        int legacy = mPrefs.getBoolean("left_camera", false) ? LEFT_CAMERA : LEFT_SPEED;
        return mPrefs.getInt("left_mode", legacy);
    }

    private void setLeftCard(int mode) {
        mPrefs.edit().putInt("left_mode", mode).apply();
        showLeftCard();
    }

    private void showLeftCard() {
        int mode = leftMode();
        boolean camera = mode == LEFT_CAMERA || isCameraExpanded();
        mSpeedCard.setVisibility(mode == LEFT_SPEED ? View.VISIBLE : View.INVISIBLE);
        mCameraCard.setVisibility(camera ? View.VISIBLE : View.GONE);
        mCompass.setVisibility(mode == LEFT_COMPASS ? View.VISIBLE : View.GONE);
        if (mode == LEFT_COMPASS) startCompassSensor();
        else mSensors.unregisterListener(this);
        if (!camera || !mResumed) {
            mCardCamera.stop();
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        mCardCamera.start();
    }

    // ---- Screen shape -----------------------------------------------------------------------

    /** Below this width/height ratio the dashboard columns are stacked (squarer car screens). */
    private static final float STACK_BELOW_ASPECT = 1.3f;

    /**
     * Wide screens (phones, 16:9 head units) show clock+speed beside music+shortcuts;
     * squarer ones put the two groups on top of each other so nothing gets squeezed.
     */
    private void fitDashboard(int width, int height) {
        LinearLayout dash = findViewById(R.id.root);
        boolean stack = height > 0 && (float) width / height < STACK_BELOW_ASPECT;
        int orientation = stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL;
        if (dash.getOrientation() == orientation) return;
        dash.setOrientation(orientation);
        for (int i = 0; i < dash.getChildCount(); i++) {
            View column = dash.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) column.getLayoutParams();
            lp.width = stack ? ViewGroup.LayoutParams.MATCH_PARENT : 0;
            lp.height = stack ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
            int gap = i == 0 ? 0 : dp(12);
            lp.setMargins(stack ? 0 : gap, stack ? gap : 0, 0, 0);
            column.setLayoutParams(lp);
        }
        // Stacked: the clock/speed group sits side by side to use the width.
        LinearLayout top = (LinearLayout) dash.getChildAt(0);
        top.setOrientation(stack ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        for (int i = 0; i < top.getChildCount(); i++) {
            View card = top.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
            lp.width = stack ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = stack ? ViewGroup.LayoutParams.MATCH_PARENT : 0;
            int gap = i == 0 ? 0 : dp(12);
            lp.setMargins(stack ? gap : 0, stack ? 0 : gap, 0, 0);
            card.setLayoutParams(lp);
        }
    }

    // ---- App pages ------------------------------------------------------------------------

    /** Rebuilds the swipe pages of app icons after the dashboard (page 0). */
    private void buildAppPages() {
        if (mPalette == null || mPager.getWidth() == 0) return;
        // CarPlay-style 4 columns. Rows follow the real screen size: about one row per
        // 200dp of height (3 on a 720p head unit, 2 on a phone in landscape), and at
        // least 3 on squarer (4:3) screens.
        float density = getResources().getDisplayMetrics().density;
        float aspect = (float) mPager.getWidth() / mPager.getHeight();
        int cols = 4;
        int rows = Math.max(2, Math.min(4, (int) ((mPager.getHeight() / density - 40) / 200)));
        if (aspect < 1.55f) rows = Math.max(rows, 3);
        int perPage = cols * rows;
        // Icon size: CarPlay-like 72dp, smaller if the cell cannot fit it plus its label.
        int cellH = (mPager.getHeight() - dp(40)) / rows - dp(8);
        int iconSize = Math.max(dp(36), Math.min(dp(72), cellH - dp(34)));

        while (mPager.getChildCount() > 1) mPager.removeViewAt(1);
        List<String[]> apps = launchableApps(false);
        int hiddenCount = hiddenApps().size();
        // Placeholder entry for the "Hidden apps" cell (null package).
        if (hiddenCount > 0) apps.add(new String[]{"Hidden apps (" + hiddenCount + ")", null});
        PackageManager pm = getPackageManager();
        GridLayout page = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % perPage == 0) {
                page = new GridLayout(this);
                page.setColumnCount(cols);
                page.setRowCount(rows);
                page.setPadding(dp(16), dp(16), dp(16), dp(24));
                mPager.addView(page);
            }
            String label = apps.get(i)[0];
            String pkg = apps.get(i)[1];
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setBackgroundResource(android.R.drawable.list_selector_background);
            ImageView iv = new ImageView(this);
            if (pkg == null) {
                iv.setImageResource(R.drawable.ic_hidden);
                iv.setImageTintList(ColorStateList.valueOf(mPalette.accent));
                iv.setBackground(mPalette.chip(this));
                iv.setPadding(iconSize / 5, iconSize / 5, iconSize / 5, iconSize / 5);
            } else {
                iv.setImageDrawable(AppIcons.icon(this, pkg));
            }
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(iv, new LinearLayout.LayoutParams(iconSize, iconSize));
            TextView tv = new TextView(this);
            tv.setText(label);
            tv.setTextColor(mPalette.text);
            tv.setTextSize(15);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(1);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(6);
            cell.addView(tv, tlp);
            if (pkg == null) {
                cell.setOnClickListener(v -> showHiddenApps());
            } else {
                cell.setOnClickListener(v -> launch(pkg));
                cell.setOnLongClickListener(v -> {
                    appOptions(label, pkg);
                    return true;
                });
            }
            page.addView(cell, gridCell(i % perPage, cols));
        }
        // Pad the last page so its cells keep the same size as on full pages.
        int used = apps.size() % perPage;
        if (page != null && used != 0) {
            for (int pos = used; pos < perPage; pos++) page.addView(new View(this), gridCell(pos, cols));
        }
        if (mPager.getPage() >= mPager.getChildCount()) mPager.setPage(0, false);
        updateDots();
    }

    private GridLayout.LayoutParams gridCell(int pos, int cols) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(pos / cols, 1f), GridLayout.spec(pos % cols, 1f));
        lp.width = 0;
        lp.height = 0;
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    /** Long-press menu for an app in the drawer. */
    private void appOptions(String label, String pkg) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(label)
                .setItems(new String[]{"Put on home screen", "Hide from app drawer"}, (d, which) -> {
                    if (which == 0) {
                        addToHomeTile(label, pkg);
                    } else {
                        java.util.Set<String> hidden = hiddenApps();
                        hidden.add(pkg);
                        setHiddenApps(hidden);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Tick an app to show it in the drawer again. */
    private void showHiddenApps() {
        java.util.Set<String> hidden = hiddenApps();
        List<String[]> apps = new ArrayList<>();
        for (String[] app : launchableApps(true)) {
            if (hidden.contains(app[1]) || hidden.contains(AppIcons.pkg(app[1]))) apps.add(app);
        }
        String[] labels = new String[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i)[0];
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Show again in the app drawer")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Show", (d, w) -> {
                    for (int i = 0; i < apps.size(); i++) {
                        if (!checked[i]) continue;
                        hidden.remove(apps.get(i)[1]);
                        hidden.remove(AppIcons.pkg(apps.get(i)[1]));
                    }
                    setHiddenApps(hidden);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private java.util.Set<String> hiddenApps() {
        // Copy: the set returned by SharedPreferences must not be modified in place.
        return new java.util.HashSet<>(mPrefs.getStringSet("hidden_apps", Collections.emptySet()));
    }

    private void setHiddenApps(java.util.Set<String> hidden) {
        mPrefs.edit().putStringSet("hidden_apps", hidden).apply();
        buildAppPages();
    }

    private void addToHomeTile(String label, String pkg) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Put " + label + " on the home screen as…")
                .setItems(SLOT_NAMES, (d, slot) -> {
                    mPrefs.edit().putString("slot_" + slot, pkg).apply();
                    buildTiles();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateDots() {
        if (mPalette == null) return;
        mDots.removeAllViews();
        int count = mPager.getChildCount();
        if (count < 2 || mPager.getPage() == 0) return;
        for (int i = 0; i < count; i++) {
            View dot = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(i == mPager.getPage() ? mPalette.accent : mPalette.stroke);
            dot.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(8), dp(8));
            lp.setMargins(dp(5), 0, dp(5), 0);
            mDots.addView(dot, lp);
        }
    }

    private List<String[]> launchableApps(boolean includeHidden) {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.Set<String> hidden = includeHidden ? Collections.emptySet() : hiddenApps();
        List<String[]> apps = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            String key = new ComponentName(pkg, ri.activityInfo.name).flattenToShortString();
            // Older versions hid whole packages; still honour those.
            if (pkg.equals(getPackageName()) || hidden.contains(key) || hidden.contains(pkg)) continue;
            apps.add(new String[]{AppIcons.label(this, key), key});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        return apps;
    }

    private void chooseSlotApp(int slot) {
        pickApp("Choose app for " + SLOT_NAMES[slot], pkg -> {
            mPrefs.edit().putString("slot_" + slot, pkg).apply();
            buildTiles();
        });
    }

    private interface OnPicked {
        void picked(String pkg);
    }

    /** Simple app list (icon + name), sorted by name. */
    private void pickApp(String title, OnPicked onPicked) {
        PackageManager pm = getPackageManager();
        List<String[]> apps = launchableApps(true);

        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return apps.size();
            }

            @Override
            public Object getItem(int i) {
                return apps.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                LinearLayout row = new LinearLayout(HomeActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(20), dp(10), dp(20), dp(10));
                ImageView iv = new ImageView(HomeActivity.this);
                iv.setImageDrawable(AppIcons.icon(HomeActivity.this, apps.get(i)[1]));
                row.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
                TextView tv = new TextView(HomeActivity.this);
                tv.setText(apps.get(i)[0]);
                tv.setTextSize(18);
                tv.setPadding(dp(16), 0, 0, 0);
                row.addView(tv);
                return row;
            }
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setAdapter(adapter, (d, which) -> onPicked.picked(apps.get(which)[1]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** @param key package name or "pkg/.Activity" (see AppIcons). */
    private boolean launch(String key) {
        Intent intent = AppIcons.launchIntent(this, key);
        if (intent == null) return false;
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }

    // ---- Now playing ----------------------------------------------------------------------

    private final MediaSessionManager.OnActiveSessionsChangedListener mSessionsListener =
            controllers -> selectController(controllers);

    private final MediaController.Callback mMediaCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            updateMedia();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            updateMedia();
        }

        @Override
        public void onSessionDestroyed() {
            connectMedia();
        }
    };

    private void connectMedia() {
        try {
            List<MediaController> controllers = mSessionManager.getActiveSessions(mListener);
            if (!mMediaAllowed) {
                mSessionManager.addOnActiveSessionsChangedListener(mSessionsListener, mListener, mHandler);
                mMediaAllowed = true;
            }
            selectController(controllers);
        } catch (SecurityException e) {
            // Notification access not granted yet.
            mMediaAllowed = false;
            updateMedia();
        }
    }

    /** Prefer whatever is playing; otherwise the most recently active session. */
    private void selectController(List<MediaController> controllers) {
        MediaController pick = null;
        if (controllers != null) {
            for (MediaController c : controllers) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    pick = c;
                    break;
                }
            }
            if (pick == null && !controllers.isEmpty()) pick = controllers.get(0);
        }
        if (mController != null) mController.unregisterCallback(mMediaCallback);
        mController = pick;
        if (mController != null) mController.registerCallback(mMediaCallback, mHandler);
        updateMedia();
    }

    private void updateMedia() {
        if (mPalette == null) return;
        if (!mMediaAllowed) {
            showMediaPlaceholder("Music", "Tap to allow music info", "Needed to show what's playing");
            return;
        }
        MediaMetadata md = mController == null ? null : mController.getMetadata();
        if (md == null || md.getString(MediaMetadata.METADATA_KEY_TITLE) == null) {
            showMediaPlaceholder("Music", "Nothing playing", "Start music in any app");
            return;
        }
        mSource.setText(appLabel(mController.getPackageName()));
        String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (!title.contentEquals(mTitle.getText())) mTitle.setText(title);
        mTitle.setSelected(true);
        String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        mArtist.setText(artist == null ? "" : artist);

        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art == null) art = loadArtUri(md);
        if (art != null) {
            mArt.setImageTintList(null);
            mArt.setBackground(null);
            mArt.setPadding(0, 0, 0, 0);
            mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mArt.setImageBitmap(art);
        } else {
            // No cover for this track: show the playing app's own icon instead.
            try {
                Drawable icon = getPackageManager().getApplicationIcon(mController.getPackageName());
                mArt.setImageTintList(null);
                mArt.setImageDrawable(icon);
                mArt.setScaleType(ImageView.ScaleType.FIT_CENTER);
                mArt.setBackground(mPalette.chip(this));
                int pad = Math.max(mArt.getLayoutParams().width, 0) / 5;
                mArt.setPadding(pad, pad, pad, pad);
            } catch (PackageManager.NameNotFoundException e) {
                setPlaceholderArt();
            }
        }

        PlaybackState state = mController.getPlaybackState();
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        // Remembered for "resume music when the car starts".
        if (playing) mPrefs.edit().putString("last_music_pkg", mController.getPackageName()).apply();
        mPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        // Only offer what the player accepts right now: YouTube Music, for one, turns
        // "next" off at times, and sending it anyway leaves the player stopped.
        long actions = state == null ? 0 : state.getActions();
        setControlEnabled(mNext, (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0);
        setControlEnabled(mPrev, (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0);
        mControls.setVisibility(View.VISIBLE);
    }

    /**
     * Art given as a URI. Local ones are read directly; web ones (YouTube Music) are
     * downloaded in the background and the card refreshes when they arrive.
     */
    private Bitmap loadArtUri(MediaMetadata md) {
        for (String key : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
            String uri = md.getString(key);
            if (uri == null) continue;
            if (uri.startsWith("http")) {
                if (uri.equals(mWebArtUri)) return mWebArt;
                fetchWebArt(uri);
                return null;
            }
            if (!(uri.startsWith("content:") || uri.startsWith("file:")
                    || uri.startsWith("android.resource:"))) continue;
            try (java.io.InputStream in = getContentResolver().openInputStream(android.net.Uri.parse(uri))) {
                Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
                if (b != null) return b;
            } catch (Exception ignored) {
                // Not readable by us; try the next key.
            }
        }
        return null;
    }

    private String mWebArtUri;
    private Bitmap mWebArt;
    private String mFetchingUri;

    private void fetchWebArt(String uri) {
        if (uri.equals(mFetchingUri)) return;
        mFetchingUri = uri;
        new Thread(() -> {
            Bitmap b = null;
            try {
                java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) new java.net.URL(uri).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                try (java.io.InputStream in = c.getInputStream()) {
                    b = android.graphics.BitmapFactory.decodeStream(in);
                } finally {
                    c.disconnect();
                }
            } catch (Exception ignored) {
                // Offline or blocked: keep the placeholder.
            }
            Bitmap result = b;
            mHandler.post(() -> {
                if (!uri.equals(mFetchingUri)) return;
                mFetchingUri = null;
                if (result == null) return;
                mWebArtUri = uri;
                mWebArt = result;
                updateMedia();
            });
        }, "art").start();
    }

    private static void setControlEnabled(View button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.3f);
    }

    private void showMediaPlaceholder(String source, String title, String subtitle) {
        mSource.setText(source);
        mTitle.setText(title);
        mArtist.setText(subtitle);
        setPlaceholderArt();
        mControls.setVisibility(View.GONE);
    }

    private void setPlaceholderArt() {
        mArt.setImageResource(R.drawable.ic_music);
        mArt.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mArt.setImageTintList(ColorStateList.valueOf(mPalette.accent));
        mArt.setBackground(mPalette.chip(this));
        mArt.setPadding(dp(24), dp(24), dp(24), dp(24));
    }

    private String appLabel(String key) {
        return AppIcons.label(this, key);
    }

    // ---- Speed (GPS) ----------------------------------------------------------------------

    private void startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            mGpsStatus.setText("Location permission needed for speed");
            return;
        }
        try {
            mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
            Location last = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) rememberPosition(last);
            if (mLastFixAt == 0) mGpsStatus.setText("Waiting for GPS…");
        } catch (IllegalArgumentException | SecurityException e) {
            mGpsStatus.setText("No GPS on this device");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_LOCATION) startLocation();
        if (requestCode == REQ_CAMERA) showLeftCard();
    }

    @Override
    public void onLocationChanged(Location location) {
        mLastFixAt = SystemClock.elapsedRealtime();
        rememberPosition(location);
        float kmh = location.hasSpeed() ? location.getSpeed() * 3.6f : 0f;
        checkParkingMode(kmh);
        if (location.hasBearing() && kmh >= GPS_HEADING_MIN_KMH) {
            mGpsBearing = location.getBearing();
            mGpsBearingAt = SystemClock.elapsedRealtime();
        }
        updateCompass();
        mSpeed.setText(String.valueOf(kmh < STOPPED_KMH ? 0 : Math.round(kmh)));
        mGpsStatus.setText(location.hasAccuracy()
                ? "GPS  ·  ±" + Math.round(location.getAccuracy()) + " m"
                : "GPS");
    }

    private void updateSpeedStale() {
        if (mLastFixAt != 0 && SystemClock.elapsedRealtime() - mLastFixAt > SPEED_STALE_MS) {
            mSpeed.setText("--");
            mGpsStatus.setText("GPS signal lost");
        }
    }

    /** Kept for the sunrise/sunset calculation. */
    private void rememberPosition(Location location) {
        mPrefs.edit().putFloat("lat", (float) location.getLatitude())
                .putFloat("lng", (float) location.getLongitude()).apply();
    }

    // Required on Android < 11, where LocationListener has no default methods.
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
        mGpsStatus.setText("GPS is off");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Day and night colour sets; same accent as D-Max Cam. */
    private static final class Palette {
        static final Palette NIGHT = new Palette(0xFF0E1116, 0xFF171B22, 0xFF262C36, 0xFF1F242D,
                0xFFE8EDF2, 0xFF8A94A3, 0xFF2EC4B6, 0xFF0B1A19);
        static final Palette DAY = new Palette(0xFFEEF2F5, 0xFFFFFFFF, 0xFFD9E0E6, 0xFFE6EBEF,
                0xFF0E1116, 0xFF5B6673, 0xFF14A396, Color.WHITE);

        final int bg, cardColor, stroke, chipColor, text, textSecondary, accent, onAccent;

        Palette(int bg, int card, int stroke, int chip, int text, int textSecondary, int accent, int onAccent) {
            this.bg = bg;
            this.cardColor = card;
            this.stroke = stroke;
            this.chipColor = chip;
            this.text = text;
            this.textSecondary = textSecondary;
            this.accent = accent;
            this.onAccent = onAccent;
        }

        GradientDrawable card(Context c) {
            GradientDrawable d = new GradientDrawable();
            float density = c.getResources().getDisplayMetrics().density;
            d.setColor(cardColor);
            d.setStroke(Math.round(density), stroke);
            d.setCornerRadius(20 * density);
            return d;
        }

        GradientDrawable chip(Context c) {
            GradientDrawable d = new GradientDrawable();
            d.setColor(chipColor);
            d.setCornerRadius(16 * c.getResources().getDisplayMetrics().density);
            return d;
        }
    }
}
