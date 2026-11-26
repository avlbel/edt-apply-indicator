package com.avlbel.edt.applyindicator;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * Активатор бандла {@code com.avlbel.edt.applyindicator}.
 */
public class Activator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "com.avlbel.edt.applyindicator";

    private static Activator plugin;

    public static Activator getDefault() {
        return plugin;
    }

    public static void log(int severity, String message, Throwable throwable) {
        Activator current = plugin;
        if (current == null) {
            return;
        }
        String text = message != null ? message : "";
        current.getLog().log(new Status(severity, PLUGIN_ID, text, throwable));
    }

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        plugin = null;
        super.stop(context);
    }
}
