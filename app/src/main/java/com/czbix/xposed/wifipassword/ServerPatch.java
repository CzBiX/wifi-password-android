package com.czbix.xposed.wifipassword;

import android.net.wifi.WifiConfiguration;
import android.os.Build;
import android.text.TextUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;

@SuppressWarnings("WeakerAccess")
public class ServerPatch {
    private static final boolean IS_ABOVE_N = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;

    static void hookWifiStore(final ClassLoader classLoader) throws Throwable {
        final String clsNameToHook = IS_ABOVE_N
                ? "com.android.server.wifi.WifiConfigManager"
                : "com.android.server.wifi.WifiConfigStore";
        final Class<?> clsToHook = XposedHelpers.findClass(clsNameToHook, classLoader);
        final Class<?> wifiSsidCls = XposedHelpers.findClass("android.net.wifi.WifiSsid",
                classLoader);

        // We have to convert encoded ssid for system
        XposedHelpers.findAndHookMethod(clsToHook, IS_ABOVE_N ? "getCredentialsByConfigKeyMap" : "getCredentialsBySsidMap",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        final Object thiz = param.thisObject;
                        final Map<String, String> pskMap = readNetworkVariablesFromSupplicantFile(thiz, "psk");
                        final Map<String, String> wepMap = readNetworkVariablesFromSupplicantFile(thiz, "wep_key0");

                        final HashMap<String, String> result = new HashMap<>(pskMap.size() + wepMap.size());
                        for (Map.Entry<String, String> entry : pskMap.entrySet()) {
                            result.put(convertSsid(wifiSsidCls, entry.getKey()), entry.getValue());
                        }
                        for (Map.Entry<String, String> entry : wepMap.entrySet()) {
                            result.put(convertSsid(wifiSsidCls, entry.getKey()), entry.getValue());
                        }

                        return result;
                    }
                });

        XposedHelpers.findAndHookMethod(clsToHook, IS_ABOVE_N ? "getSavedNetworks" : "getConfiguredNetworks", Map.class,
                new XC_MethodHook() {
                    @SuppressWarnings("unchecked")
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final Map<String, String> map = (Map<String, String>) param.args[0];
                        if (map == null) {
                            return;
                        }

                        // fill up missing WEP key
                        final List<WifiConfiguration> result = (List<WifiConfiguration>) param.getResult();
                        for (WifiConfiguration config : result) {
                            if (config.allowedKeyManagement != null
                                    && config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)) {
                                final String key = IS_ABOVE_N ? config.SSID + "WEP" : config.SSID;
                                if (map.containsKey(key)) {
                                    config.wepKeys[config.wepTxKeyIndex] = map.get(key);
                                }
                            }
                        }
                    }
                });
    }

    // copied from com.android.server.wifi.WifiConfigStore#readNetworkVariables
    private static String convertSsid(Class<?> wifiSsidCls, String ssid) {
        if (!IS_ABOVE_N && !TextUtils.isEmpty(ssid)) {
            if (ssid.charAt(0) != '"') {
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
