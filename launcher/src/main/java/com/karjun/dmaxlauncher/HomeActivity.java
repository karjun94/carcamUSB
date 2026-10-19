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
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Car home screen: big clock, GPS speed, now playing, and app shortcuts. */
public class HomeActivity extends Activity implements LocationListener {
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
    private static final String[] SLOT_NAMES = {"Phone", "Navigation", "Radio", "Camera", "Settings"};
    /** First installed package wins. Long-press a tile to pick something else. */
    private static final String[][] SLOT_DEFAULTS = {
            {"com.ucarhu.carlink", "com.imotor.phoneconnect", "com.carletter.car", "com.google.android.projection.gearhead"},
            {"com.waze", "com.google.android.apps.maps"},
            {"com.imotor.fmam"},
            {"com.karjun.dmaxcam"},
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
        setContentView(R.layout.activity_home);
        mPrefs = getSharedPreferences("home", MODE_PRIVATE);

        mRoot = findViewById(R.id.root);
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

        mSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        mListener = new ComponentName(this, MediaListener.class);
        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        applyTheme();
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildTiles();
        connectMedia();
        startLocation();
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
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
        // We are the home screen; there is nothing to go back to.
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
            } else if (pkg != null && pm.getLaunchIntentForPackage(pkg) != null) {
                try {
                    icon = pm.getApplicationIcon(pkg);
                    label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                } catch (PackageManager.NameNotFoundException e) {
                    label = SLOT_NAMES[slot];
                }
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
                tile.setOnClickListener(v -> pickApp("All apps", this::launch));
            } else {
                tile.setOnClickListener(v -> {
                    if (pkg != null && launch(pkg)) return;
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
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(main, 0);
        List<String[]> apps = new ArrayList<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            apps.add(new String[]{ri.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));

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
                try {
                    iv.setImageDrawable(pm.getApplicationIcon(apps.get(i)[1]));
                } catch (PackageManager.NameNotFoundException ignored) {
                }
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

    private boolean launch(String pkg) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
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
        mTitle.setText(md.getString(MediaMetadata.METADATA_KEY_TITLE));
        String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        mArtist.setText(artist == null ? "" : artist);

        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art != null) {
            mArt.setImageTintList(null);
            mArt.setBackground(null);
            mArt.setPadding(0, 0, 0, 0);
            mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mArt.setImageBitmap(art);
        } else {
            setPlaceholderArt();
        }

        PlaybackState state = mController.getPlaybackState();
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        mPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        mControls.setVisibility(View.VISIBLE);
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

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
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
    }

    @Override
    public void onLocationChanged(Location location) {
        mLastFixAt = SystemClock.elapsedRealtime();
        rememberPosition(location);
        float kmh = location.hasSpeed() ? location.getSpeed() * 3.6f : 0f;
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
