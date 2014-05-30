package com.germainz.pinnotif;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.XModuleResources;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static de.robv.android.xposed.XposedBridge.hookAllMethods;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookConstructor;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findMethodExact;
import static de.robv.android.xposed.XposedHelpers.getIntField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;

public class XposedMod implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static String TEXT_PIN;
    private static String TEXT_UNPIN;
    private static String TEXT_APP_INFO;
    private static final String INTENT_BROADCAST_ACTION = "com.germainz.pinnotif.BROADCAST";
    private static final String EXTRA_PINNOTIF_MARKER = "com.germainz.pinnotif";
    private static final String EXTRA_NOTIFICATION_FLAGS = "com.germainz.pinnotif.notification_flags";
    // TODO: ICS compatibility

    private static final int PKG_INDEX = 0;
    private static final int TAG_INDEX;
    private static final int ID_INDEX;
    private static final int NOTIFICATION_INDEX;
    private static final int USERID_INDEX;

    static {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            TAG_INDEX = 2;
            ID_INDEX = 3;
            NOTIFICATION_INDEX = 4;
            USERID_INDEX = 6;
        } else {
            TAG_INDEX = 1;
            ID_INDEX = 2;
            NOTIFICATION_INDEX = 3;
            USERID_INDEX = 5;
        }
    }

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        XModuleResources res = XModuleResources.createInstance(startupParam.modulePath, null);
        TEXT_PIN = res.getString(R.string.text_pin);
        TEXT_UNPIN = res.getString(R.string.text_unpin);
        TEXT_APP_INFO = res.getString(R.string.text_app_info);

    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals("com.android.systemui"))
            loadBaseStatusBarHooks(lpparam);
        else if (lpparam.packageName.equals("android"))
            loadNotifManagerServiceHooks(lpparam);
    }

    private void loadNotifManagerServiceHooks(LoadPackageParam loadPackageParam) {
        final Class notificationManagerServiceClass = findClass("com.android.server.NotificationManagerService",
                loadPackageParam.classLoader);
        findAndHookConstructor(notificationManagerServiceClass, Context.class,
                "com.android.server.StatusBarManagerService", "com.android.server.LightsService",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                        BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                Bundle bundle = intent.getExtras();
                                String pkg = bundle.getString("pkg");
                                String tag = bundle.getString("tag");
                                int id = bundle.getInt("id");

                                int userId = 0;
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                                    userId = bundle.getInt("userId");
                                int index = getNotificationIndex(notificationManagerServiceClass,
                                        param.thisObject, pkg, tag, id, userId);

                                // Each notification is associated with a package, and has an id.
                                // To update a certain notification, we send updated notification
                                // using the same id.
                                // Since we only want to update the flags, we get the current
                                // (non-updated) notification and set the flags we want for it.
                                // Get the current notification
                                Notification notification = getNotification(param.thisObject, index);
                                Bundle nExtras = (Bundle) getObjectField(notification, "extras");
                                notification.flags = bundle.getInt(EXTRA_NOTIFICATION_FLAGS);
                                if (nExtras == null)
                                    nExtras = new Bundle();
                                nExtras.putInt(EXTRA_PINNOTIF_MARKER, 0);

                                // We then send it, which will cause it to update and use the new
                                // flags.
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                                    String basePkg = bundle.getString("basePkg");
                                    callMethod(param.thisObject, "enqueueNotificationWithTag", pkg, basePkg, tag,
                                            id, notification, new int[1], userId);
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                                    callMethod(param.thisObject, "enqueueNotificationWithTag", pkg, tag, id,
                                            notification, new int[1], userId);
                                } else {
                                    callMethod(param.thisObject, "enqueueNotificationWithTag", pkg, tag, id,
                                            notification, new int[1]);
                                }
                            }

                        };

                        Context context = (Context) param.args[0];
                        IntentFilter filter = new IntentFilter();
                        filter.addAction(INTENT_BROADCAST_ACTION);
                        context.registerReceiver(broadcastReceiver, filter);
                    }
                }
        );

        hookAllMethods(notificationManagerServiceClass, "enqueueNotificationWithTag", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {

                        Notification n = (Notification) param.args[NOTIFICATION_INDEX];
                        Bundle nExtras = (Bundle) getObjectField(n, "extras");
                        // This is an update caused by our module. Nothing to do here.
                        if (nExtras != null && nExtras.containsKey(EXTRA_PINNOTIF_MARKER))
                            return;

                        Object userId = null;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                            userId = param.args[USERID_INDEX];
                        int index = getNotificationIndex(notificationManagerServiceClass, param.thisObject,
                                param.args[PKG_INDEX], param.args[TAG_INDEX], param.args[ID_INDEX], userId);

                        // This is not a new notification, but an update. Check if we had set custom
                        // flags, and if so, restore them.
                        if (index >= 0) {
                            Notification oldSbnNotification = getNotification(param.thisObject, index);
                            Bundle oldSbnExtras = (Bundle) getObjectField(oldSbnNotification, "extras");
                            // If the marker is set, then we previously set custom flags… restore them
                            if (oldSbnExtras != null && oldSbnExtras.containsKey(EXTRA_PINNOTIF_MARKER)) {
                                if (nExtras == null)
                                    nExtras = new Bundle();
                                nExtras.putInt(EXTRA_PINNOTIF_MARKER, 0);
                                if (isClearable(oldSbnNotification))
                                    n.flags &= ~Notification.FLAG_NO_CLEAR;
                                else
                                    n.flags |= Notification.FLAG_NO_CLEAR;
                            }
                        }
                    }
                }
        );
    }

    private static void loadBaseStatusBarHooks(final LoadPackageParam lpp) {
        Class<?> baseStatusBar = findClass("com.android.systemui.statusbar.BaseStatusBar",
                lpp.classLoader);
        try {
            injectViewTag(baseStatusBar);
        } catch (Throwable e) {
            XposedBridge.log(e);
        }

        try {
            hookLongPressNotif(baseStatusBar);
        } catch (Throwable e) {
            XposedBridge.log(e);
        }
    }

    private static void hookLongPressNotif(Class<?> baseStatusBar) {
        findAndHookMethod(baseStatusBar, "getNotificationLongClicker",
                new XC_MethodReplacement() {
                    protected Object replaceHookedMethod(final MethodHookParam param) throws Throwable {
                        final Context mContext = (Context) getObjectField(param.thisObject, "mContext");
                        return new View.OnLongClickListener() {
                            @Override
                            public boolean onLongClick(final View v) {
                                try {
                                    final Object entry = v.getTag();

                                    final Object sbn = getObjectField(entry, "notification");
                                    final String packageNameF = (String) getObjectField(sbn, "pkg");
                                    final Notification n = (Notification) getObjectField(sbn, "notification");

                                    if (packageNameF == null) return false;
                                    if (v.getWindowToken() == null) return false;

                                    final PopupMenu popup = new PopupMenu(mContext, v);
                                    popup.getMenu().add(TEXT_APP_INFO);
                                    if (!isOngoing(n))
                                        popup.getMenu().add(isClearable(n) ? TEXT_PIN : TEXT_UNPIN);
                                    popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                                        public boolean onMenuItemClick(MenuItem item) {
                                            if (item.getTitle().equals(TEXT_APP_INFO)) {
                                                Intent intent = new Intent(
                                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                        Uri.fromParts("package", packageNameF,
                                                                null)
                                                );
                                                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                                mContext.startActivity(intent);
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                                                    callMethod(param.thisObject, "animateCollapsePanels", 0);
                                                else
                                                    callMethod(param.thisObject, "animateCollapse", 0);
                                            } else if (item.getTitle().equals(TEXT_PIN)) {
                                                item.setTitle(TEXT_UNPIN);
                                                n.flags |= Notification.FLAG_NO_CLEAR;
                                                sendBroadcast(mContext, sbn, n);
                                            } else if (item.getTitle().equals(TEXT_UNPIN)) {
                                                item.setTitle(TEXT_PIN);
                                                n.flags &= ~Notification.FLAG_NO_CLEAR;
                                                sendBroadcast(mContext, sbn, n);
                                            } else {
                                                return false;
                                            }
                                            return true;
                                        }
                                    });
                                    popup.show();
                                    return true;
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    return false;
                                }
                            }
                        };
                    }
                }
        );

    }

    private static void sendBroadcast(Context context, Object sbn, Notification n) {
        Intent intent = new Intent(INTENT_BROADCAST_ACTION);
        intent.putExtra("pkg", (String) getObjectField(sbn, "pkg"));
        intent.putExtra("basePkg", context.getPackageName());
        intent.putExtra("tag", (String) getObjectField(sbn, "tag"));
        intent.putExtra("id", getIntField(sbn, "id"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
            intent.putExtra("userId", (Integer) callMethod(sbn, "getUserId"));
        intent.putExtra(EXTRA_NOTIFICATION_FLAGS, n.flags);
        context.sendBroadcast(intent);
    }

    private static void injectViewTag(Class<?> baseStatusBar) {
        findAndHookMethod(baseStatusBar, "inflateViews",
                "com.android.systemui.statusbar.NotificationData.Entry", ViewGroup.class,
                new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final Object entry = param.args[0];
                        View newRow = (View) getObjectField(entry, "row");
                        newRow.setTag(entry);
                        setObjectField(entry, "row", newRow);
                    }
                }
        );
    }

    public static boolean isClearable(Notification notification) {
        return ((notification.flags & Notification.FLAG_ONGOING_EVENT) == 0)
                && ((notification.flags & Notification.FLAG_NO_CLEAR) == 0);
    }

    public static boolean isOngoing(Notification notification) {
        return (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
    }

    public static int getNotificationIndex(Class clazz, Object object, Object pkg, Object tag, Object id, Object userId) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                return (Integer) findMethodExact(clazz, "indexOfNotificationLocked", String.class, String.class, int.class, int.class).invoke(object, pkg, tag, id, userId);
            else
                return (Integer) findMethodExact(clazz, "indexOfNotificationLocked", String.class, String.class, int.class).invoke(object, pkg, tag, id);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public static Notification getNotification(Object object, int index) {
        ArrayList<Object> notificationList = (ArrayList<Object>) getObjectField(object, "mNotificationList");
        Object notificationRecord = notificationList.get(index);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            Object oldSbn = getObjectField(notificationRecord, "sbn");
            return (Notification) getObjectField(oldSbn, "notification");
        } else {
            return (Notification) getObjectField(notificationRecord, "notification");
        }
    }

}

