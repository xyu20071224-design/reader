package com.linguareader.app.hotfix;

import android.content.Context;
import android.os.Build;
import com.tencent.tinker.lib.listener.DefaultPatchListener;

/** Official asynchronous dexopt mode avoids waiting for ODEX files modern ART may not emit. */
public final class ReaderPatchListener extends DefaultPatchListener {
    public ReaderPatchListener(Context context) { super(context); }
    @Override public int onPatchReceived(String path) {
        return checkPackageAndRunPatchService(path, Build.VERSION.SDK_INT >= 29);
    }
}
