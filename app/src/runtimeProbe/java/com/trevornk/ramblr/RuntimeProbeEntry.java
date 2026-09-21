package com.trevornk.ramblr;

import android.content.Context;

/**
 * The sole AndroidTest-to-target ABI for the optimized isolated probe. This Java static method is
 * explicitly preserved in probe-runtime-rules.pro; every production API exercise stays in the
 * target's own R8 graph behind it.
 */
public final class RuntimeProbeEntry {
    private RuntimeProbeEntry() {}

    public static void run(Context context, String stage) {
        RuntimeProbeNativeDriver.run(context, stage);
    }
}
