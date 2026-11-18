package com.linguareader.app.hotfix.bootstrap;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

/** Private helper process survives stopping the main process so restart needs no alarm permission. */
public final class ReaderRestartActivity extends Activity {
    public static void restart(Context context) {
        context.startActivity(new Intent(context, ReaderRestartActivity.class)
            .putExtra("mainPid", Process.myPid()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        int target = getIntent().getIntExtra("mainPid", -1);
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        boolean ownMain = false;
        if (manager != null && manager.getRunningAppProcesses() != null) {
            for (ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses()) {
                if (process.pid == target && process.uid == Process.myUid() && getPackageName().equals(process.processName)) ownMain = true;
            }
        }
        if (!ownMain || target == Process.myPid()) { finish(); return; }
        Process.killProcess(target);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            new Handler(Looper.getMainLooper()).postDelayed(() -> Process.killProcess(Process.myPid()), 400);
        }, 400);
    }
}
