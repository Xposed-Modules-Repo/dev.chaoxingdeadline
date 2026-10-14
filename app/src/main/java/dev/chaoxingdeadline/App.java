package dev.chaoxingdeadline;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class App extends Application implements XposedServiceHelper.OnServiceListener {
    private static volatile XposedService service;
    private static final CopyOnWriteArrayList<ServiceListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        BridgeAuth.ensureToken(this);
        BridgeAuth.syncRemotePreferences(this);
        DeadlineNotifier.ensureChannel(this);
        XposedServiceHelper.registerListener(this);
        DeadlineNotifier.rescheduleUpcomingOnly(this);
        DeadlineNotifier.scheduleNextDigest(this);
        DeadlineWidgetProvider.updateAll(this);
        DeadlineScheduleWidget.updateAll(this);
        listenConfigurationChanges(this);
    }

    /** Widgets bake dark/light palettes at update time, so re-render on theme changes. */
    private static void listenConfigurationChanges(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                DeadlineWidgetProvider.updateAll(ctx);
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onServiceBind(XposedService service) {
        App.service = service;
        BridgeAuth.syncRemotePreferences(this);
        AppSettings.syncRemotePreferences(this);
        OverlayBridge.publish(this);
        for (ServiceListener listener : listeners) {
            listener.onServiceChanged(service);
        }
    }

    @Override
    public void onServiceDied(XposedService service) {
        App.service = null;
        for (ServiceListener listener : listeners) {
            listener.onServiceChanged(null);
        }
    }

    public static XposedService getService() {
        return service;
    }

    public static void addServiceListener(ServiceListener listener) {
        listeners.add(listener);
        listener.onServiceChanged(service);
    }

    public static void removeServiceListener(ServiceListener listener) {
        listeners.remove(listener);
    }

    public interface ServiceListener {
        void onServiceChanged(XposedService service);
    }
}
