package com.czbix.xposed.wifipassword;

import android.net.wifi.WifiConfiguration;
import android.text.TextUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;

public class ServerPatch {
    public static void hookWifiStore(final ClassLoader classLoader) throws Throwable {
        final Class<?> configStoreCls = XposedHelpers.findClass("com.android.server.wifi.WifiConfigStore", classLoader);

        // we have to convert encoded ssid for system
        XposedHelpers.findAndHookMethod(configStoreCls, "getCredentialsBySsidMap",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        final Object thiz = param.thisObject;
                        final Map<String, String> pskMap = readNetworkVariablesFromSupplicantFile(thiz, "psk");
                        final Map<String, String> wepMap = readNetworkVariablesFromSupplicantFile(thiz, "wep_key0");

                        final HashMap<String, String> result = new HashMap<>(pskMap.size() + wepMap.size());
                        for (Map.Entry<String, String> entry : pskMap.entrySet()) {
                            result.put(convertSsid(classLoader, entry.getKey()), entry.getValue());
                        }
                        for (Map.Entry<String, String> entry : wepMap.entrySet()) {
                            result.put(convertSsid(classLoader, entry.getKey()), entry.getValue());
                        }

                        return result;
                    }
                });

        XposedHelpers.findAndHookMethod(configStoreCls, "getConfiguredNetworks", Map.class,
                new XC_MethodHook() {
                    @SuppressWarnings("unchecked")
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final Map<String, String> map = (Map<String, String>) param.args[0];
                        if (map == null) {
                            return;
                        }

                        final List<WifiConfiguration> result = (List<WifiConfiguration>) param.getResult();
                        for (WifiConfiguration config : result) {
                            if (config.allowedKeyManagement != null
                                    && config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)
                                    && map.containsKey(config.SSID)) {
                                config.wepKeys[config.wepTxKeyIndex] = map.get(config.SSID);
                            }
                        }
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

    @SuppressWarnings("unchecked")
    private static Map<String, String> readNetworkVariablesFromSupplicantFile(Object thiz, String key) {
        return (Map<String, String>) XposedHelpers.callMethod(thiz, "readNetworkVariablesFromSupplicantFile", key);
    }
}
