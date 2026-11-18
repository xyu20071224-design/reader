package com.linguareader.app.hotfix.bootstrap;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.tencent.tinker.entry.DefaultApplicationLike;
import com.tencent.tinker.lib.patch.UpgradePatch;
import com.tencent.tinker.lib.reporter.DefaultLoadReporter;
import com.tencent.tinker.lib.reporter.DefaultPatchReporter;
import com.linguareader.app.hotfix.ReaderPatchListener;
import com.tencent.tinker.lib.tinker.TinkerInstaller;
import com.linguareader.app.hotfix.ReaderPatchResultService;

public final class ReaderApplicationLike extends DefaultApplicationLike {
    public ReaderApplicationLike(Application application, int flags, boolean verify, long elapsed, long millis, Intent result) {
        super(application, flags, verify, elapsed, millis, result);
    }
    @Override public void onBaseContextAttached(Context base) {
        super.onBaseContextAttached(base);
        if (!"robolectric".equals(Build.FINGERPRINT)) {
            TinkerInstaller.install(this, new DefaultLoadReporter(base), new DefaultPatchReporter(base),
                new ReaderPatchListener(base), ReaderPatchResultService.class, new UpgradePatch());
        }
    }
}
