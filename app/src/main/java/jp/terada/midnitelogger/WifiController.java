package jp.terada.midnitelogger;

import android.content.Context;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WifiController {
    private final Context context;
    private final WifiManager wifi;
    public WifiController(Context context) {
        this.context = context.getApplicationContext();
        wifi = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
    }

    public String currentSsid() {
        try {
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            if (info == null || info.getIpAddress() == 0) return null;
            String ssid = clean(info.getSSID());
            if (ssid.length() == 0 || "<unknown ssid>".equalsIgnoreCase(ssid) || "unknown ssid".equalsIgnoreCase(ssid)) return null;
            return ssid;
        } catch (SecurityException e) { return null; }
    }


    public String currentIpAddress() {
        try {
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            if (info == null || info.getIpAddress() == 0) return "取得不可";
            int ip = info.getIpAddress();
            return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "."
                    + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
        } catch (Exception e) { return "取得不可"; }
    }

    public boolean isLocationServiceEnabled() {
        if (android.os.Build.VERSION.SDK_INT < 23) return true;
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            return lm != null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
        } catch (Exception e) { return false; }
    }

    private int networkId(String ssid) {
        if (ssid == null || ssid.length() == 0) return -1;
        try {
            List<WifiConfiguration> list = wifi.getConfiguredNetworks();
            if (list == null) return -1;
            for (WifiConfiguration item : list) if (ssid.equals(clean(item.SSID))) return item.networkId;
        } catch (SecurityException ignored) { }
        return -1;
    }

    public boolean connect(String ssid, int timeoutSeconds) throws InterruptedException {
        if (ssid == null || ssid.length() == 0) return false;
        if (ssid.equals(currentSsid())) return true;
        int id = networkId(ssid); if (id < 0) return false;
        wifi.disconnect(); wifi.enableNetwork(id, true); wifi.reconnect();
        long end = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < end) {
            if (ssid.equals(currentSsid())) return true;
            Thread.sleep(1000);
        }
        return false;
    }

    public String bestConfiguredCandidate(String loggerSsid) throws InterruptedException {
        try {
            wifi.startScan(); Thread.sleep(5000);
            Set<String> configured = new HashSet<String>();
            List<WifiConfiguration> configs = wifi.getConfiguredNetworks();
            if (configs != null) for (WifiConfiguration c : configs) configured.add(clean(c.SSID));
            ScanResult best = null; List<ScanResult> scans = wifi.getScanResults();
            if (scans != null) for (ScanResult r : scans) {
                if (configured.contains(r.SSID) && !r.SSID.equals(loggerSsid) && r.level >= -75 && (best == null || r.level > best.level)) best = r;
            }
            return best == null ? null : best.SSID;
        } catch (SecurityException e) { return null; }
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value;
    }
}
