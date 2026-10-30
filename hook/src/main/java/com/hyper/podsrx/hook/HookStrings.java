package com.hyper.podsrx.hook;

import android.content.Context;
import android.content.pm.PackageManager;

/** Resolve module resources, never host resource IDs, using the host's current language. */
final class HookStrings {
    private HookStrings() {}

    static String get(Context host, int resource, Object... arguments) {
        try {
            Context module = host.createPackageContext(HubWire.APP, 0)
                .createConfigurationContext(host.getResources().getConfiguration());
            return module.getString(resource, arguments);
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("HyperPods-X resources unavailable", error);
        }
    }
}
