package jp.terada.midnitelogger;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WifiController {
    private final WifiManager wifi;
    public WifiController(Context context) { wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE); }
    private int networkId(String ssid) {
        List<WifiConfiguration> list = wifi.getConfiguredNetworks();
        if (list == null) return -1;
        for (WifiConfiguration item : list) if (ssid.equals(clean(item.SSID))) return item.networkId;
        return -1;
    }
    public boolean connect(String ssid, int timeoutSeconds) throws InterruptedException {
        int id = networkId(ssid); if (id < 0) return false;
        wifi.disconnect(); wifi.enableNetwork(id, true); wifi.reconnect();
        long end = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < end) {
            WifiInfo info = wifi.getConnectionInfo();
            if (info != null && ssid.equals(clean(info.getSSID())) && info.getIpAddress() != 0) return true;
            Thread.sleep(1000);
        }
        return false;
    }
    public String bestConfiguredCandidate(String loggerSsid) throws InterruptedException {
        wifi.startScan(); Thread.sleep(5000);
        Set<String> configured = new HashSet<String>();
        List<WifiConfiguration> configs = wifi.getConfiguredNetworks();
        if (configs != null) for (WifiConfiguration c : configs) configured.add(clean(c.SSID));
        ScanResult best = null; List<ScanResult> scans = wifi.getScanResults();
        if (scans != null) for (ScanResult r : scans) {
            if (configured.contains(r.SSID) && !loggerSsid.equals(r.SSID) && r.level >= -75 && (best == null || r.level > best.level)) best = r;
        }
        return best == null ? null : best.SSID;
    }
    private static String clean(String text) { return text == null ? "" : text.replace("\"", ""); }
}
