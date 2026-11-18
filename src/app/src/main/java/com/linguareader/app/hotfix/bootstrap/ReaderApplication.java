package com.linguareader.app.hotfix.bootstrap;

import android.os.Build;
import com.tencent.tinker.loader.app.TinkerApplication;
import com.tencent.tinker.loader.shareutil.ShareConstants;

/** Only the immutable loader lives here; business Kotlin stays patchable. */
public final class ReaderApplication extends TinkerApplication {
    public ReaderApplication() {
        super("robolectric".equals(Build.FINGERPRINT) ? ShareConstants.TINKER_DISABLE : ShareConstants.TINKER_DEX_MASK,
            "com.linguareader.app.hotfix.bootstrap.ReaderApplicationLike",
            "com.tencent.tinker.loader.TinkerLoader", true);
        setUseSafeMode(true);
    }
}
