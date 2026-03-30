package com.fakegps;

import android.content.*;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences sp = ctx.getSharedPreferences("fakegps_prefs", Context.MODE_PRIVATE);
            boolean autoStart = sp.getBoolean("auto_start", false);
            if (autoStart) {
                double lat = Double.longBitsToDouble(sp.getLong("last_lat", 0));
                double lon = Double.longBitsToDouble(sp.getLong("last_lon", 0));
                if (lat != 0 && lon != 0) {
                    Intent svc = new Intent(ctx, FakeGpsService.class);
                    svc.setAction(FakeGpsService.ACTION_START);
                    svc.putExtra(FakeGpsService.EXTRA_LAT, lat);
                    svc.putExtra(FakeGpsService.EXTRA_LON, lon);
                    ctx.startService(svc);
                }
            }
        }
    }
}
