package com.karjun.dmaxlauncher;

import android.app.Activity;
import android.os.Bundle;

/**
 * Invisible. Declaring a USB_DEVICE_ATTACHED filter is what lets Android offer
 * "Use by default" for the camera, so the USB permission is asked only once.
 */
public class UsbAttachActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
