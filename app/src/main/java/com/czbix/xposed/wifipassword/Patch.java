package com.czbix.xposed.wifipassword;

import android.content.Context;
import android.content.res.Resources;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiManager;
import android.view.View;
import android.view.ViewGroup;

import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class Patch implements IXposedHookLoadPackage {
    private static final String PKG_NAME = "com.android.settings";

    @Override
    public void handleLoadPackage(LoadPackageParam param) throws Throwable {
        if (!param.packageName.equals(PKG_NAME)) {
            return;
        }

        hookWifiController(param.classLoader);
    }

    private void hookWifiController(ClassLoader loader) {
        final Class<?> controller = XposedHelpers.findClass("com.android.settings.wifi.WifiConfigController", loader);
        XposedHelpers.findAndHookMethod(controller, "showSecurityFields", showSecurityFieldsHook);
    }

    private final XC_MethodHook showSecurityFieldsHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {

            if (XposedHelpers.getBooleanField(param.thisObject, "mInXlSetupWizard")) {
                return;
            }

            final int mSecurity = XposedHelpers.getIntField(param.thisObject, "mAccessPointSecurity");
            if (mSecurity != 1 && mSecurity != 2) {
                // open network or EAP
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

            final View mView = (View) XposedHelpers.getObjectField(param.thisObject, "mView");
            String pwd = getWiFiPassword(mView.getContext(), networkId);

            final Resources resources = mView.getContext().getResources();
            final int idInfo = resources.getIdentifier("info", "id", PKG_NAME);
            final int idPwd = resources.getIdentifier("wifi_password", "string", PKG_NAME);

            final ViewGroup group = (ViewGroup) mView.findViewById(idInfo);

            XposedHelpers.callMethod(param.thisObject, "addRow", group, idPwd, pwd);
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
}
