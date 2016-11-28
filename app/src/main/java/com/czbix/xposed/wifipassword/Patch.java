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
    private static final boolean IS_ABOVE_M = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    private static final boolean IS_SAMSUNG = Build.BRAND.equals("samsung");
    private static final boolean IS_HTC = Build.BRAND.equals("htc");

    public void handleLoadPackage(LoadPackageParam param) throws Throwable {
        if (param.packageName.equals("android")) {
            ServerPatch.hookWifiStore(param.classLoader);
        } else if (param.packageName.equals(PKG_NAME)) {
            hookWifiController(param.classLoader);
        }
    }

    private void hookWifiController(ClassLoader loader) {
        final Class<?> controller = XposedHelpers.findClass("com.android.settings.wifi.WifiConfigController", loader);

        do {
            if (IS_ABOVE_M && tryHookConstructor(controller,
                    "Hook M constructor",
                    "com.android.settings.wifi.WifiConfigUiBase",
                    View.class,
                    "com.android.settingslib.wifi.AccessPoint",
                    boolean.class,
                    boolean.class,
                    methodHook)) {
                break;
            }

            if (tryHookConstructor(controller,
                    "Hook default WifiConfigController constructor",
                    "com.android.settings.wifi.WifiConfigUiBase",
                    View.class,
                    "com.android.settings.wifi.AccessPoint",
                    boolean.class,
                    methodHook)) {
                break;
            }

            if (IS_SAMSUNG && tryHookConstructor(controller,
                    "Hook Samsung constructor",
                    "com.android.settings.wifi.WifiConfigUiBase",
                    View.class,
                    "com.android.settings.wifi.AccessPoint",
                    boolean.class,
                    boolean.class,
                    methodHook)) {
                break;
            }

            if (IS_HTC && tryHookConstructor(controller,
                    "Hook HTC constructor",
                    "com.android.settings.wifi.WifiConfigUiBase",
                    View.class,
                    "com.android.settings.wifi.AccessPoint",
                    int.class,
                    methodHook)) {
                break;
            }

            XposedBridge.log("All constructor hook failed!");
        }
        while (false);
    }

    private static boolean tryHookConstructor(Class<?> clazz, String msg,
            Object... parameterTypesAndCallback) {
        try {
            XposedHelpers.findAndHookConstructor(clazz, parameterTypesAndCallback);
        } catch (Error e) {
            XposedBridge.log(msg + " failed");
            return false;
        }

        XposedBridge.log(msg + " success");
        return true;
    }

    private final XC_MethodHook methodHook = new XC_MethodHook() {
        private Context mContext;

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

            if (IS_HTC) {
                final int mEdit = XposedHelpers.getIntField(param.thisObject, "mEdit");
                if (mEdit != 0) {
                    return;
                }
            } else {
                final boolean mEdit = XposedHelpers.getBooleanField(param.thisObject, "mEdit");
                if (mEdit) {
                    return;
                }
            }

            final View mView = (View) XposedHelpers.getObjectField(param.thisObject, "mView");

            final Context context = mView.getContext();
            mContext = context.createPackageContext(BuildConfig.APPLICATION_ID,
                    Context.CONTEXT_RESTRICTED);

            final Resources resources = context.getResources();
            final int idInfo = resources.getIdentifier("info", "id", PKG_NAME);
            final int idPwd = resources.getIdentifier("wifi_password", "string", PKG_NAME);
            final ViewGroup group = (ViewGroup) mView.findViewById(idInfo);

            final int mSecurity = XposedHelpers.getIntField(param.thisObject, "mAccessPointSecurity");
            final String emptyPassword = mContext.getString(R.string.empty_password);
            String pwd;
            if (mSecurity != 1 && mSecurity != 2) {
                // not WEP/PSK
                pwd = emptyPassword;
            } else {
                pwd = getWiFiPassword(context, networkId);
                // more check, more safe
                if (pwd == null) {
                    pwd = emptyPassword;
                }
            }

            final String ssid = (String) XposedHelpers.getObjectField(mAccessPoint, "ssid");
            addRow(param, idPwd, group, ssid, pwd);
        }

        private void addRow(MethodHookParam param, int idPwd, ViewGroup group, final String ssid, final String pwd) {
            String defaultPwd = mContext.getString(R.string.empty_password);
            if (!pwd.equals(defaultPwd)) {
                defaultPwd = new String(new char[pwd.length()]).replace("\0", "·");
            }
            XposedHelpers.callMethod(param.thisObject, "addRow", group, idPwd, defaultPwd);
            final View view = group.getChildAt(group.getChildCount() - 1);

            view.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final Context context = v.getContext();
                    final int idValue = context.getResources().getIdentifier("value", "id", PKG_NAME);
                    final TextView textView = (TextView) view.findViewById(idValue);
                    if (textView == null) {
                        if (IS_HTC) {
                            // I hate HTC
                            final int idItem = context.getResources().getIdentifier("item", "id", PKG_NAME);
                            XposedHelpers.callMethod(view.findViewById(idItem), "setSecondaryText", pwd);
                        } else {
                            // show password in toast as alternative
                            Toast.makeText(context, pwd, Toast.LENGTH_LONG).show();
                        }
                    } else {
                        textView.setText(pwd);
                    }
                }
            });

            view.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    final Context context = v.getContext();
                    final ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboardManager.setPrimaryClip(ClipData.newPlainText(null,
                            mContext.getString(R.string.clip_info_format, ssid, pwd)));
                    Toast.makeText(context, mContext.getString(R.string.toast_wifi_info_copied), Toast.LENGTH_SHORT).show();
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
                        return config.preSharedKey.replaceAll("^\"|\"$", "");
                    } else {
                        return config.wepKeys[config.wepTxKeyIndex].replaceAll("^\"|\"$", "");
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
