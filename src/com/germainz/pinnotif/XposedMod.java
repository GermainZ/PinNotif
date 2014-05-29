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

import java.util.ArrayList;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static de.robv.android.xposed.XposedBridge.hookAllMethods;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookConstructor;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getIntField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

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
            loadNewHooks(lpparam);
        else if (lpparam.packageName.equals("android"))
            loadNotifManagerServiceHooks(lpparam);
    }

    private void loadNotifManagerServiceHooks(LoadPackageParam loadPackageParam) {
        Class notificationManagerServiceClass = findClass("com.android.server.NotificationManagerService",
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
                                String basePkg = bundle.getString("basePkg");
                                String tag = bundle.getString("tag");
                                int id = bundle.getInt("id");

                                int userId = 0;
                                int index;
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                                    userId = bundle.getInt("userId");
                                    // TODO: make a function that calls the appropriate indexOfNotificationLocked,
                                    // TODO:    and use it here and below (in the enqueueNotificationWithTag hook)
                                    index = (Integer) callMethod(param.thisObject, "indexOfNotificationLocked",
                                            pkg, tag, id, userId);
                                } else {
                                    index = (Integer) callMethod(param.thisObject, "indexOfNotificationLocked",
                                            pkg, tag, id);
                                }

                                // TODO: move to Notification getNotification(Object thisObject) method?
                                ArrayList<Object> notificationList = (ArrayList<Object>) getObjectField(param.thisObject, "mNotificationList");
                                Object notificationRecord = notificationList.get(index);
                                Notification notification;
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                                    Object oldSbn = getObjectField(notificationRecord, "sbn");
                                    notification = (Notification) getObjectField(oldSbn, "notification");
                                } else {
                                    notification = (Notification) getObjectField(notificationRecord, "notification");
                                }
                                Bundle nExtras = (Bundle) getObjectField(notification, "extras");
                                notification.flags = bundle.getInt(EXTRA_NOTIFICATION_FLAGS);
                                if (nExtras == null)
                                    nExtras = new Bundle();
                                nExtras.putInt(EXTRA_PINNOTIF_MARKER, 0);

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
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
                        if (nExtras != null && nExtras.containsKey(EXTRA_PINNOTIF_MARKER))
                            return;

                        int index;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                            index = (Integer) callMethod(param.thisObject, "indexOfNotificationLocked",
                                    param.args[PKG_INDEX], param.args[TAG_INDEX], param.args[ID_INDEX],
                                    param.args[USERID_INDEX]);
                        else
                            index = (Integer) callMethod(param.thisObject, "indexOfNotificationLocked",
                                    param.args[PKG_INDEX], param.args[TAG_INDEX], param.args[ID_INDEX]);

                        if (index >= 0) {
                            ArrayList<Object> notificationList = (ArrayList<Object>) getObjectField(param.thisObject, "mNotificationList");
                            Object notificationRecord = notificationList.get(index);
                            Notification oldSbnNotification;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                                Object oldSbn = getObjectField(notificationRecord, "sbn");
                                oldSbnNotification = (Notification) getObjectField(oldSbn, "notification");
                            } else {
                                oldSbnNotification = (Notification) getObjectField(notificationRecord, "notification");
                            }

                            Bundle oldSbnExtras = (Bundle) getObjectField(oldSbnNotification, "extras");
                            if (oldSbnExtras.containsKey(EXTRA_PINNOTIF_MARKER)) {
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

    private static void loadNewHooks(final LoadPackageParam lpp) {
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
                        final Object thiz = param.thisObject;
                        final Context mContext = (Context) XposedHelpers.findField(
                                thiz.getClass(), "mContext").get(thiz);
                        return new View.OnLongClickListener() {
                            @Override
                            public boolean onLongClick(final View v) {
                                try {
                                    final Object entry = v.getTag();

                                    final Object sbn = XposedHelpers.getObjectField(entry, "notification");
                                    final String packageNameF = (String) XposedHelpers.getObjectField(sbn, "pkg");
                                    final Notification n = (Notification) XposedHelpers.getObjectField(sbn, "notification");

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
                                                callMethod(param.thisObject, "animateCollapsePanels", 0);
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
                        View newRow = (View) XposedHelpers.getObjectField(entry, "row");
                        newRow.setTag(entry);
                        XposedHelpers.setObjectField(entry, "row", newRow);
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
}

