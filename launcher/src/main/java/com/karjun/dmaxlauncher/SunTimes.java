package com.karjun.dmaxlauncher;

/**
 * Sunrise and sunset from latitude/longitude, offline (the standard "sunrise
 * equation", accurate to a minute or two). Used for automatic night mode on
 * head units that cannot read the headlight signal.
 */
final class SunTimes {
    private static final double J2000 = 2451545.0;
    private static final double UNIX_EPOCH_JD = 2440587.5;
    private static final double MS_PER_DAY = 86400000.0;

    private SunTimes() {
    }

    /** @return {sunrise, sunset} in epoch milliseconds for the day containing {@code nowMs}. */
    static long[] riseSet(long nowMs, double lat, double lng) {
        double jd = nowMs / MS_PER_DAY + UNIX_EPOCH_JD;
        // Solar-noon day nearest to now, in local solar time.
        double n = Math.round(jd - J2000 + lng / 360.0);
        double jStar = n + 0.0009 - lng / 360.0;
        double m = Math.toRadians((357.5291 + 0.98560028 * jStar) % 360.0);
        double c = 1.9148 * Math.sin(m) + 0.02 * Math.sin(2 * m) + 0.0003 * Math.sin(3 * m);
        double lambda = Math.toRadians((Math.toDegrees(m) + c + 180.0 + 102.9372) % 360.0);
        double transit = J2000 + jStar + 0.0053 * Math.sin(m) - 0.0069 * Math.sin(2 * lambda);
        double decl = Math.asin(Math.sin(lambda) * Math.sin(Math.toRadians(23.4397)));
        double phi = Math.toRadians(lat);
        double cosW = (Math.sin(Math.toRadians(-0.833)) - Math.sin(phi) * Math.sin(decl))
                / (Math.cos(phi) * Math.cos(decl));
        // Polar day/night: clamp so we still return something sensible.
        double w = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosW))));
        return new long[]{toMs(transit - w / 360.0), toMs(transit + w / 360.0)};
    }

    static boolean isNight(long nowMs, double lat, double lng) {
        long[] rs = riseSet(nowMs, lat, lng);
        return nowMs < rs[0] || nowMs > rs[1];
    }

    private static long toMs(double jd) {
        return (long) ((jd - UNIX_EPOCH_JD) * MS_PER_DAY);
    }
}
