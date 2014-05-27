package com.germainz.pinnotif;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.XModuleResources;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
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

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookConstructor;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

public class XposedMod implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static String TEXT_PIN;
    private static String TEXT_UNPIN;
    private static String TEXT_APP_INFO;
    private static final String INTENT_BROADCAST_ACTION = "com.germainz.pinnotif.BROADCAST";
    // TODO: ICS compatibility

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
        findAndHookConstructor("com.android.server.NotificationManagerService", loadPackageParam.classLoader,
                Context.class, "com.android.server.StatusBarManagerService",
                "com.android.server.LightsService",
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
                                int userId = bundle.getInt("userId");
                                Notification notification = bundle.getParcelable("notification");
                                callMethod(param.thisObject, "enqueueNotificationWithTag", pkg, basePkg,
                                        tag, id, notification, new int[1], userId);
                            }
                        };
                        Context context = (Context) param.args[0];
                        IntentFilter filter = new IntentFilter();
                        filter.addAction(INTENT_BROADCAST_ACTION);
                        context.registerReceiver(broadcastReceiver, filter);
                    }
                }
        );

        findAndHookMethod("com.android.server.NotificationManagerService", loadPackageParam.classLoader,
                "enqueueNotificationInternal", String.class, String.class, int.class, int.class,
                String.class, int.class, Notification.class, int[].class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                        int index = (Integer) callMethod(param.thisObject, "indexOfNotificationLocked",
                                param.args[0], param.args[4], param.args[5], param.args[8]);
                        if (index >= 0) {
                            ArrayList<Object> notificationList = (ArrayList<Object>) getObjectField(param.thisObject, "mNotificationList");
                            Object notificationRecord = notificationList.get(index);
                            StatusBarNotification oldSbn = ((StatusBarNotification) getObjectField(notificationRecord, "sbn"));
                            Notification n = (Notification) param.args[6];
                            if (oldSbn.getNotification().extras.containsKey("pinnotif") && !n.extras.containsKey("pinnotif")) {
                                n.extras.putInt("pinnotif", 0);
                                if (oldSbn.isClearable())
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

                                    final StatusBarNotification sbn = (StatusBarNotification) XposedHelpers.getObjectField(entry, "notification");
                                    final String packageNameF = (String) XposedHelpers.getObjectField(sbn, "pkg");
                                    final Notification n = (Notification) XposedHelpers.getObjectField(sbn, "notification");

                                    if (packageNameF == null) return false;
                                    if (v.getWindowToken() == null) return false;

                                    final PopupMenu popup = new PopupMenu(mContext, v);
                                    popup.getMenu().add(TEXT_APP_INFO);
                                    if (!sbn.isOngoing())
                                        popup.getMenu().add(sbn.isClearable() ? TEXT_PIN : TEXT_UNPIN);
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

    private static void sendBroadcast(Context context, StatusBarNotification sbn, Notification n) {
        Intent intent = new Intent(INTENT_BROADCAST_ACTION);
        intent.putExtra("pkg", sbn.getPackageName());
        intent.putExtra("basePkg", context.getPackageName());
        intent.putExtra("tag", sbn.getTag());
        intent.putExtra("id", sbn.getId());
        intent.putExtra("userId", sbn.getUserId());
        intent.putExtra("notification", n);
        n.extras.putInt("pinnotif", 0);
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

}

