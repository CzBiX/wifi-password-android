package com.czbix.xposed.wifipassword;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Resources;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.UserHandle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class Patch implements IXposedHookLoadPackage {
    private static final String PKG_NAME = "com.android.settings";
    private static final boolean isAboveM = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;

    @Override
    public void handleLoadPackage(LoadPackageParam param) throws Throwable {
        if (!param.packageName.equals(PKG_NAME)) {
            return;
        }

        hookWifiController(param.classLoader);
    }

    private void hookWifiController(ClassLoader loader) {
        final Class<?> controller = XposedHelpers.findClass("com.android.settings.wifi.WifiConfigController", loader);
        if (isAboveM) {
            XposedHelpers.findAndHookConstructor(controller,
                    "com.android.settings.wifi.WifiConfigUiBase",
                    View.class,
                    "com.android.settingslib.wifi.AccessPoint",
                    boolean.class,
                    boolean.class,
                    methodHook);
        } else {
            boolean hookFailed = false;
            try {
                XposedHelpers.findAndHookConstructor(controller,
                        "com.android.settings.wifi.WifiConfigUiBase",
                        View.class,
                        "com.android.settings.wifi.AccessPoint",
                        boolean.class,
                        methodHook);
            } catch (NoSuchMethodError e) {
                XposedBridge.log("Hook default WifiConfigController constructor failed");
                hookFailed = true;
            }

            if (hookFailed) {
                // HACK: Samsung changed the constructor, try to hook it
                XposedHelpers.findAndHookConstructor(controller,
                        "com.android.settings.wifi.WifiConfigUiBase",
                        View.class,
                        "com.android.settings.wifi.AccessPoint",
                        boolean.class,
                        boolean.class,
                        methodHook);
            }
        }
    }

    private final XC_MethodHook methodHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            if (!isOwner()) {
                // only show password for owner
                return;
            }

            final Object mAccessPoint = XposedHelpers.getObjectField(param.thisObject, "mAccessPoint");
            if (mAccessPoint == null) {
                return;
            }

            final int networkId = XposedHelpers.getIntField(mAccessPoint, "networkId");
            if (networkId == -1) {
                return;
            }

            final boolean mEdit = XposedHelpers.getBooleanField(param.thisObject, "mEdit");
            if (mEdit) {
                return;
            }

            final View mView = (View) XposedHelpers.getObjectField(param.thisObject, "mView");

            final Resources resources = mView.getContext().getResources();
            final int idInfo = resources.getIdentifier("info", "id", PKG_NAME);
            final int idPwd = resources.getIdentifier("wifi_password", "string", PKG_NAME);
            final ViewGroup group = (ViewGroup) mView.findViewById(idInfo);

            final int mSecurity = XposedHelpers.getIntField(param.thisObject, "mAccessPointSecurity");
            String pwd;
            if (mSecurity != 1 && mSecurity != 2) {
                // open network or EAP
                pwd = "N/A";
            } else {
                pwd = getWiFiPassword(mView.getContext(), networkId);
            }

            final String ssid = (String) XposedHelpers.getObjectField(mAccessPoint, "ssid");
            addRow(param, idPwd, group, ssid, pwd);
        }

        private void addRow(MethodHookParam param, int idPwd, ViewGroup group, final String ssid, final String pwd) {
            XposedHelpers.callMethod(param.thisObject, "addRow", group, idPwd, "\\(╯-╰)/");
            final View view = group.getChildAt(group.getChildCount() - 1);

            view.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final int idValue = v.getContext().getResources().getIdentifier("value", "id", PKG_NAME);
                    ((TextView) view.findViewById(idValue)).setText(pwd);
                }
            });

            view.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    final Context context = v.getContext();
                    final ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboardManager.setPrimaryClip(ClipData.newPlainText(null,
                            String.format("SSID: %s\nPWD: %s", ssid, pwd)));
                    Toast.makeText(context, "WiFi info copied!", Toast.LENGTH_SHORT).show();
                    return false;
                }
            });
        }

        private String getWiFiPassword(Context context, int networkId) {
            final WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            @SuppressWarnings("unchecked")
            final List<WifiConfiguration> list = (List<WifiConfiguration>) XposedHelpers.callMethod(wifiManager, "getPrivilegedConfiguredNetworks");

            for (WifiConfiguration config : list) {
                if (config.networkId == networkId) {
                    if (config.allowedKeyManagement.get(KeyMgmt.WPA_PSK)) {
                        return config.preSharedKey;
                    } else {
                        return config.wepKeys[config.wepTxKeyIndex];
                    }
                }
            }

            return null;
        }
    };

    private static boolean isOwner() {
        final int currentUser = (int) XposedHelpers.callStaticMethod(ActivityManager.class, "getCurrentUser");
        return currentUser == XposedHelpers.getStaticIntField(UserHandle.class, "USER_OWNER");
    }
}
