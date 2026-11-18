package com.linguareader.app.hotfix;

import com.tencent.tinker.lib.service.DefaultTinkerResultService;
import com.tencent.tinker.lib.service.PatchResult;
import com.tencent.tinker.lib.util.TinkerServiceInternals;

/** Completion must not kill the reader or interrupt TTS; the user reopens when convenient. */
public final class ReaderPatchResultService extends DefaultTinkerResultService {
    @Override public void onPatchResult(PatchResult result) {
        if (result == null) return;
        NativeHotfixRuntime.finish(getApplicationContext(), result);
        if (result.isSuccess) deleteRawPatchFile(new java.io.File(result.rawPatchFilePath));
        TinkerServiceInternals.killTinkerPatchServiceProcess(getApplicationContext());
    }
}
