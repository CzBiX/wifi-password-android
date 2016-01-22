package com.czbix.xposed.wifipassword;

import android.text.TextUtils;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public class ServerPatch {
    public static void hookWifiStore(final ClassLoader classLoader) throws Throwable {
        // we have to convert encoded ssid for system
        XposedHelpers.findAndHookMethod("com.android.server.wifi.WifiConfigStore", classLoader,
                "getCredentialsBySsidMap", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        @SuppressWarnings("unchecked")
                        final Map<String, String> oldResult = (Map<String, String>) param.getResult();
                        Map<String, String> result = new HashMap<>(oldResult.size());
                        for (Map.Entry<String, String> entry : oldResult.entrySet()) {
                            result.put(convertSsid(classLoader, entry.getKey()), entry.getValue());
                        }

                        param.setResult(result);
                    }
                });
    }

    // copied from com.android.server.wifi.WifiConfigStore#readNetworkVariables
    private static String convertSsid(ClassLoader classLoader, String ssid) {
        if (!TextUtils.isEmpty(ssid)) {
            if (ssid.charAt(0) != '"') {
                final Class<?> wifiSsidCls = XposedHelpers.findClass("android.net.wifi.WifiSsid",
                        classLoader);
                final String encodedSsid = XposedHelpers.callStaticMethod(wifiSsidCls, "createFromHex", ssid).toString();

                ssid = "\"" + encodedSsid + "\"";
            }
        }

        return ssid;
    }
}
