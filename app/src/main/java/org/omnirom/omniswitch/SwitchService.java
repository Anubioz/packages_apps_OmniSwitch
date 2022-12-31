/*
 *  Copyright (C) 2013 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package org.omnirom.omniswitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.ContextThemeWrapper;

import com.android.wm.shell.splitscreen.ISplitScreen;

import org.omnirom.omniswitch.launcher.Launcher;
import org.omnirom.omniswitch.ui.BitmapCache;
import org.omnirom.omniswitch.ui.BitmapUtils;
import org.omnirom.omniswitch.ui.IconPackHelper;

import java.util.HashSet;
import java.util.Set;

public class SwitchService extends Service {
    private final static String TAG = "OmniSwitch:SwitchService";
    private static boolean DEBUG = false;

    private static final int START_SERVICE_ERROR_ID = 0;
    private static final int START_PERMISSION_SETTINGS_ID = 1;
    private static final String NOTIFICATION_CHANNEL_ID = "omniswitch";

    public static final String DPI_CHANGE = "dpi_change";

    private RecentsReceiver mReceiver;
    private static SwitchManager mManager;
    private SharedPreferences mPrefs;
    private SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener;
    private static SwitchConfiguration mConfiguration;
    private static int mUserId = -1;
    private Set<String> mPrefKeyFilter = new HashSet<String>();
    private static boolean mIsRunning;
    private static boolean mPreloadDone;
    private OverlayMonitor mOverlayMonitor;
    private PackageReceiver mPackageReceiver;
    private LocaleChangeReceiver mLocaleReceiver;
    private boolean mSplitScreenServiceBound;
    private Handler mHandler = new Handler();

    private final ServiceConnection mSplitsScreenService = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "onServiceConnected " + name + " " + service);
            ISplitScreen splitScreen = ISplitScreen.Stub.asInterface(service);
            mManager.bindSplitScreen(splitScreen);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected " + name);
            mManager.unbindSplitScreen();
        }
    };

    public static boolean isRunning() {
        return mIsRunning;
    }

    private BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mManager.hide(true);
        }
    };

    private final class SettingsObserver extends ContentObserver {
        private final Uri mSplitScreenExternal =
                Settings.System.getUriFor("split_screen_external");

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = SwitchService.this.getContentResolver();
            resolver.registerContentObserver(mSplitScreenExternal, false, this);
        }

        void unobserve() {
            ContentResolver resolver = SwitchService.this.getContentResolver();
            resolver.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            update(uri);
        }

        public void update(Uri uri) {
            if (mSplitScreenExternal.equals(uri)) {
                if (Utils.isSplitScreenExternal(SwitchService.this)) {
                    bindSplitScreenService();
                } else {
                    unbindSplitScreenService();
                }
            }
        }
    }

    private SettingsObserver mSystemPrefsListener = new SettingsObserver((mHandler));

    @Override
    public void onCreate() {
        try {
            super.onCreate();
            mConfiguration = SwitchConfiguration.getInstance(this);
            mConfiguration.initDefaults(this);

            createNotificationChannel();

            if (!canDrawOverlayViews()) {
                createOverlayNotification();
                commitSuicide();
                return;
            }
            if (mConfiguration.mRestrictedMode) {
                createErrorNotification();
            }
            mUserId = UserHandle.myUserId();
            Log.d(TAG, "started SwitchService " + mUserId);

            mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
            mPrefKeyFilter.add(SettingsActivity.PREF_SHOW_FAVORITE);
            mPrefKeyFilter.add(Launcher.WECLOME_SCREEN_DISMISSED);
            mPrefKeyFilter.add(Launcher.STATE_ESSENTIALS_EXPANDED);
            mPrefKeyFilter.add(Launcher.STATE_PANEL_SHOWN);
            if (DEBUG) {
                Log.d(TAG, "mPrefKeyFilter " + mPrefKeyFilter);
            }

            BitmapUtils.clearCachedColors();

            String layoutStyle = mPrefs.getString(SettingsActivity.PREF_LAYOUT_STYLE, "1");
            mManager = new SwitchManager(new ContextThemeWrapper(this, R.style.AppThemeSwitch), Integer.valueOf(layoutStyle));

            mReceiver = new RecentsReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(RecentsReceiver.ACTION_HANDLE_HIDE);
            filter.addAction(RecentsReceiver.ACTION_HANDLE_SHOW);
            filter.addAction(Intent.ACTION_USER_SWITCHED);
            filter.addAction(Intent.ACTION_SHUTDOWN);
            registerReceiver(mReceiver, filter);

            mPackageReceiver = new PackageReceiver();
            IntentFilter pkgFilter = new IntentFilter();
            // TODO - changed comes always after added so maybe we can skip added
            pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            //pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
            pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            pkgFilter.addDataScheme("package");
            registerReceiver(mPackageReceiver, pkgFilter);

            mLocaleReceiver = new LocaleChangeReceiver();
            IntentFilter localeFilter = new IntentFilter();
            localeFilter.addAction(Intent.ACTION_LOCALE_CHANGED);
            registerReceiver(mLocaleReceiver, localeFilter);

            IntentFilter screenFilter = new IntentFilter();
            screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(mScreenReceiver, screenFilter);

            PackageManager.getInstance(this).updatePackageList();

            updatePrefs(mPrefs, null);

            mPrefsListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
                public void onSharedPreferenceChanged(SharedPreferences prefs,
                                                      String key) {
                    try {
                        updatePrefs(prefs, key);
                    } catch (Exception e) {
                        Log.e(TAG, "updatePrefs", e);
                    }
                }
            };

            mPrefs.registerOnSharedPreferenceChangeListener(mPrefsListener);
            mSystemPrefsListener.observe();

            mOverlayMonitor = new OverlayMonitor(this);

            if (mConfiguration.mLaunchStatsEnabled) {
                SwitchStatistics.getInstance(this).loadStatistics();
            }

            if (mConfiguration.mDragHandleShow) {
                mManager.getSwitchGestureView().show();
            }

            if (Utils.isSplitScreenExternal(this)) {
                bindSplitScreenService();
            }

            mIsRunning = true;
        } catch (Exception e) {
            Log.e(TAG, "onCreate", e);
            commitSuicide();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "stopped SwitchService " + mUserId);

        try {
            unregisterReceiver(mReceiver);
            unregisterReceiver(mPackageReceiver);
            unregisterReceiver(mLocaleReceiver);
            unregisterReceiver(mScreenReceiver);
            mPrefs.unregisterOnSharedPreferenceChangeListener(mPrefsListener);
            mSystemPrefsListener.unobserve();
        } catch (IllegalArgumentException e) {
            // ignored on purpose
        }

        if (mOverlayMonitor != null) {
            mOverlayMonitor.unregisterReceiver(this);
        }

        if (mManager != null) {
            mManager.killManager();
            mManager.shutdownService();
        }

        if (mConfiguration.mLaunchStatsEnabled) {
            SwitchStatistics.getInstance(this).saveStatistics();
        }

        unbindSplitScreenService();

        mIsRunning = false;
        BitmapCache.getInstance(this).clear();
        RecentTasksLoader.getInstance(this).clearTaskInfoCache();
    }

    public class LocalBinder extends Binder {
        public SwitchService getService() {
            return SwitchService.this;
        }
    }

    private final LocalBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public static class RecentsReceiver extends BroadcastReceiver {
        public static final String ACTION_HANDLE_HIDE = "org.omnirom.omniswitch.ACTION_HANDLE_HIDE";
        public static final String ACTION_HANDLE_SHOW = "org.omnirom.omniswitch.ACTION_HANDLE_SHOW";
        public static final String ACTION_TOGGLE_OVERLAY = "org.omnirom.omniswitch.ACTION_TOGGLE_OVERLAY";
        public static final String ACTION_TOGGLE_OVERLAY2 = "org.omnirom.omniswitch.ACTION_TOGGLE_OVERLAY2";
        public static final String ACTION_PRELOAD_TASKS = "org.omnirom.omniswitch.ACTION_PRELOAD_TASKS";
        public static final String ACTION_HIDE_OVERLAY = "org.omnirom.omniswitch.ACTION_HIDE_OVERLAY";

        @Override
        public void onReceive(final Context context, Intent intent) {
            try {
                String action = intent.getAction();
                if (DEBUG) {
                    Log.d(TAG, "onReceive " + action);
                }
                if (ACTION_HANDLE_SHOW.equals(action)) {
                    if (mConfiguration.mDragHandleShow) {
                        mManager.getSwitchGestureView().show();
                    }
                } else if (ACTION_HANDLE_HIDE.equals(action)) {
                    mManager.getSwitchGestureView().hide();
                } else if (ACTION_TOGGLE_OVERLAY.equals(action)) {
                    if (DEBUG) {
                        Log.d(TAG, "ACTION_TOGGLE_OVERLAY " + System.currentTimeMillis());
                    }
                    if (mManager.isShowing()) {
                        mManager.hide(false);
                    } else {
                        mManager.show();
                    }
                } else if (ACTION_TOGGLE_OVERLAY2.equals(action)) {
                    if (DEBUG) {
                        Log.d(TAG, "ACTION_TOGGLE_OVERLAY2 " + System.currentTimeMillis());
                    }
                    if (mManager.isShowing()) {
                        mManager.hide(false);
                    } else {
                        if (mPreloadDone) {
                            mManager.showPreloaded();
                        } else {
                            // just in case
                            Log.e(TAG, "ACTION_TOGGLE_OVERLAY2 called without preload - fallback to ACTION_TOGGLE_OVERLAY");
                            mManager.show();
                        }
                    }
                    mPreloadDone = false;
                } else if (ACTION_HIDE_OVERLAY.equals(action)) {
                    if (DEBUG) {
                        Log.d(TAG, "ACTION_HIDE_OVERLAY");
                    }
                    if (mManager.isShowing()) {
                        mManager.hide(false);
                    }
                } else if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                    int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                    Log.d(TAG, "user switch " + mUserId + "->" + userId);
                    if (userId != mUserId) {
                        mManager.getSwitchGestureView().hide();
                    } else {
                        if (mConfiguration.mDragHandleShow) {
                            mManager.getSwitchGestureView().show();
                        }
                    }
                } else if (Intent.ACTION_SHUTDOWN.equals(action)) {
                    Log.d(TAG, "ACTION_SHUTDOWN");
                    mManager.shutdownService();
                } else if (ACTION_PRELOAD_TASKS.equals(action)) {
                    if (DEBUG) {
                        Log.d(TAG, "ACTION_PRELOAD_TASKS " + System.currentTimeMillis());
                    }
                    if (!mManager.isShowing()) {
                        mManager.beforePreloadTasks();
                        RecentTasksLoader.getInstance(context).cancelLoadingTasks();
                        RecentTasksLoader.getInstance(context).setSwitchManager(mManager);
                        RecentTasksLoader.getInstance(context).preloadTasks();
                        mPreloadDone = true;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "onReceive", e);
            }
        }
    }

    private class OverlayMonitor extends BroadcastReceiver {
        private final String ACTION_OVERLAY_CHANGED = "android.intent.action.OVERLAY_CHANGED";

        OverlayMonitor(Context context) {
            context.registerReceiver(this, Utils.getPackageFilter("android", ACTION_OVERLAY_CHANGED));
        }

        public void unregisterReceiver(Context context) {
            try {
                context.unregisterReceiver(this);
            } catch (Exception e) {
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DEBUG) {
                Log.d(TAG, "onReceive " + action);
            }
            PackageManager.getInstance(context).updatePackageIcons();
        }
    }

    public void updatePrefs(SharedPreferences prefs, String key) {
        if (isFilteredPrefsChange(key)) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "updatePrefs " + key);
        }
        BitmapUtils.clearCachedColors();
        IconPackHelper.getInstance(this).updatePrefs(prefs, key);
        mConfiguration.updatePrefs(prefs, key);
        mManager.updatePrefs(prefs, key);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (DEBUG) {
            Log.d(TAG, "onConfigurationChanged");
        }
        try {
            if (mIsRunning) {
                boolean updateDone = false;
                if (mConfiguration.onConfigurationChanged(this)) {
                    updatePrefs(mPrefs, DPI_CHANGE);
                    updateDone = true;
                }
                if (!updateDone && (mConfiguration.mBgStyle == SwitchConfiguration.BgStyle.SOLID_SYSTEM ||
                        mConfiguration.mDynamicDragHandleColor)) {
                    updatePrefs(mPrefs, SettingsActivity.PREF_BG_STYLE);
                }
                int newScreenHeight = Math.round(newConfig.screenHeightDp * mConfiguration.mDensity);
                if (DEBUG) {
                    Log.d(TAG, "newScreenHeight = " + newScreenHeight);
                }
                mManager.onConfigurationChanged(newScreenHeight);
            }
        } catch (Exception e) {
            Log.e(TAG, "onConfigurationChanged", e);
        }
    }

    private void createErrorNotification() {
        final NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        final Notification notifyDetails = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("OmniSwitch restricted mode")
                .setContentText("Failed to gain system permissions")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setShowWhen(false)
                .build();
        notificationManager.cancel(START_SERVICE_ERROR_ID);
        notificationManager.notify(START_SERVICE_ERROR_ID, notifyDetails);
    }

    private void disableAutoStart() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putBoolean(SettingsActivity.PREF_ENABLE, false).commit();
    }

    private void commitSuicide() {
        disableAutoStart();
        Intent stopIntent = new Intent(this, SwitchService.class);
        stopService(stopIntent);
    }

    /**
     * fugly but save since the service is actually a singleton
     */
    public static SwitchManager getRecentsManager() {
        return mManager;
    }

    private boolean isFilteredPrefsChange(String key) {
        return mPrefKeyFilter.contains(key);
    }

    private void createOverlayNotification() {
        final NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        PendingIntent settingsIntent = PendingIntent.getActivity(this, START_PERMISSION_SETTINGS_ID,
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        final Notification notifyDetails = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.dialog_overlay_perms_title))
                .setContentText(getResources().getString(R.string.dialog_overlay_perms_msg))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(settingsIntent)
                .setShowWhen(false)
                .build();

        notificationManager.cancel(START_SERVICE_ERROR_ID);
        notificationManager.notify(START_SERVICE_ERROR_ID, notifyDetails);
    }

    private boolean canDrawOverlayViews() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void createNotificationChannel() {
        final NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        CharSequence name = getString(R.string.notification_channel_name);
        String description = getString(R.string.notification_channel_description);
        int importance = NotificationManager.IMPORTANCE_DEFAULT;
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
        channel.setDescription(description);
        notificationManager.createNotificationChannel(channel);
    }

    private void bindSplitScreenService() {
        if (!mSplitScreenServiceBound) {
            Log.d(TAG, "bindSplitScreenService");
            Intent serviceIntent = new Intent("android.intent.action.OMNI_SPLITSCREEN_SERVICE")
                    .setPackage("com.android.systemui");
            try {
                mSplitScreenServiceBound = bindService(serviceIntent,
                        mSplitsScreenService,
                        Context.BIND_AUTO_CREATE);
            } catch (SecurityException e) {
                Log.e(TAG, "Unable to bind because of security error", e);
            }
        }
    }

    private void unbindSplitScreenService() {
        if (mSplitScreenServiceBound) {
            Log.d(TAG, "unbindSplitScreenService");
            mManager.unbindSplitScreen();
            unbindService(mSplitsScreenService);
            mSplitScreenServiceBound = false;
        }
    }
}
