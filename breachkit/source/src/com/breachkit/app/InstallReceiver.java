package com.breachkit.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Receives PackageInstaller results. If Android wants the user to confirm, show its dialog. */
public class InstallReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent i) {
    int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
    if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
      Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
      if (confirm != null) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(confirm); } catch (Exception ignored) {}
      }
    }
  }
}
