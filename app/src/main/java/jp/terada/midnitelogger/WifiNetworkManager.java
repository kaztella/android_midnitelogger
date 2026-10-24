package jp.terada.midnitelogger;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

public class WifiNetworkManager {
    private final WifiManager wifiManager;

    public WifiNetworkManager(Context context) {
        this.wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
    }

    public String getConnectedSsid() {
        if (wifiManager == null) {
            return null;
        }
        WifiInfo info = wifiManager.getConnectionInfo();
        if (info == null) {
            return null;
        }
        String ssid = info.getSSID();
        if (ssid == null) {
            return null;
        }
        return normalizeSsid(ssid);
    }

    public boolean isConnectedTo(String targetSsid) {
        if (TextUtils.isEmpty(targetSsid)) {
            return false;
        }
        String current = getConnectedSsid();
        if (TextUtils.isEmpty(current)) {
            return false;
        }
        return current.equalsIgnoreCase(normalizeSsid(targetSsid));
    }

    public boolean shouldUploadOnCurrentNetwork(List<String> uploadSsids) {
        if (uploadSsids == null || uploadSsids.isEmpty()) {
            return false;
        }
        String current = getConnectedSsid();
        if (TextUtils.isEmpty(current)) {
            return false;
        }
        for (String ssid : uploadSsids) {
            if (!TextUtils.isEmpty(ssid) && current.equalsIgnoreCase(normalizeSsid(ssid))) {
                return true;
            }
        }
        return false;
    }

    public String findUploadNetworkInScanResults(List<String> uploadSsids) {
        if (wifiManager == null || uploadSsids == null || uploadSsids.isEmpty()) {
            return null;
        }
        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
        }
        try {
            wifiManager.startScan();
        } catch (Exception ignored) {
            return null;
        }

        List<ScanResult> results = wifiManager.getScanResults();
        if (results == null) {
            return null;
        }

        for (ScanResult result : results) {
            if (result == null) {
                continue;
            }
            String scannedSsid = normalizeSsid(result.SSID);
            if (TextUtils.isEmpty(scannedSsid)) {
                continue;
            }
            for (String target : uploadSsids) {
                if (TextUtils.isEmpty(target)) {
                    continue;
                }
                String normalizedTarget = normalizeSsid(target);
                if (!TextUtils.isEmpty(normalizedTarget)
                        && scannedSsid.equalsIgnoreCase(normalizedTarget)) {
                    return normalizedTarget;
                }
            }
        }
        return null;
    }

    public boolean connectToSsid(String targetSsid) {
        if (wifiManager == null || TextUtils.isEmpty(targetSsid)) {
            return false;
        }
        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
        }

        List<WifiConfiguration> networks = wifiManager.getConfiguredNetworks();
        if (networks == null) {
            return false;
        }

        String normalizedTarget = normalizeSsid(targetSsid);
        for (WifiConfiguration network : networks) {
            String configured = network.SSID;
            if (TextUtils.isEmpty(configured)) {
                continue;
            }
            if (normalizeSsid(configured).equalsIgnoreCase(normalizedTarget)) {
                boolean ok = wifiManager.enableNetwork(network.networkId, true);
                wifiManager.reconnect();
                return ok;
            }
        }
        return false;
    }

    public boolean restoreClassicNetwork() {
        return connectToSsid(Config.CLASSIC_WIFI_SSID);
    }

    public List<String> parseUploadSsidList(String csv) {
        List<String> items = new ArrayList<String>();
        if (TextUtils.isEmpty(csv)) {
            return items;
        }
        String[] values = csv.split(",");
        for (String candidate : values) {
            if (candidate == null) {
                continue;
            }
            String cleaned = normalizeSsid(candidate.trim());
            if (!TextUtils.isEmpty(cleaned)) {
                items.add(cleaned);
            }
        }
        return items;
    }

    private static String normalizeSsid(String ssid) {
        if (ssid == null) {
            return null;
        }
        String value = ssid.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
