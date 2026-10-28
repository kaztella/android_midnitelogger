package jp.terada.midnitelogger;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.List;

public class WifiNetworkManager {
    private final WifiManager wifiManager;
    private final Context context;

    public WifiNetworkManager(Context context) {
        this.context = context.getApplicationContext();
        this.wifiManager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
    }

    public String getConnectedSsid() {
        try {
            if (wifiManager == null) return null;
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null || info.getIpAddress() == 0) return null;
            String ssid = normalizeSsid(info.getSSID());
            if (TextUtils.isEmpty(ssid) || "<unknown ssid>".equalsIgnoreCase(ssid)
                    || "unknown ssid".equalsIgnoreCase(ssid) || "0x".equalsIgnoreCase(ssid)) return null;
            return ssid;
        } catch (Exception ignored) { return null; }
    }

    public String connectionDiagnostics() {
        try {
            if (wifiManager == null) return "wifi=null";
            WifiInfo i = wifiManager.getConnectionInfo();
            if (i == null) return "info=null";
            return "SSID=" + normalizeSsid(i.getSSID()) + " IP=" + ip(i.getIpAddress())
                    + " state=" + String.valueOf(i.getSupplicantState()) + " RSSI=" + i.getRssi();
        } catch (Exception e) { return "diag=" + e.getClass().getSimpleName(); }
    }

    public boolean isConnectedTo(String targetSsid) {
        String current = getConnectedSsid();
        return !TextUtils.isEmpty(targetSsid) && !TextUtils.isEmpty(current)
                && current.equalsIgnoreCase(normalizeSsid(targetSsid));
    }

    public boolean shouldUploadOnCurrentNetwork(List<String> uploadSsids) {
        String current = getConnectedSsid();
        if (TextUtils.isEmpty(current) || uploadSsids == null) return false;
        for (String s : uploadSsids) if (!TextUtils.isEmpty(s) && current.equalsIgnoreCase(normalizeSsid(s))) return true;
        return false;
    }

    public String findUploadNetworkInScanResults(List<String> uploadSsids) {
        if (wifiManager == null || uploadSsids == null || uploadSsids.isEmpty()) return null;
        if (!wifiManager.isWifiEnabled()) wifiManager.setWifiEnabled(true);
        try { wifiManager.startScan(); } catch (Exception ignored) { return null; }
        List<ScanResult> results = wifiManager.getScanResults();
        if (results == null) return null;
        for (ScanResult r : results) {
            String seen = r == null ? null : normalizeSsid(r.SSID);
            for (String wanted : uploadSsids) if (!TextUtils.isEmpty(seen) && seen.equalsIgnoreCase(normalizeSsid(wanted))) return normalizeSsid(wanted);
        }
        return null;
    }

    public boolean connectToSsid(String targetSsid) { return connectToSsid(targetSsid, Config.WIFI_SWITCH_TIMEOUT_MS); }

    /** Success means DHCP/IP is ready, not merely that enableNetwork() returned true. */
    public boolean connectToSsid(String targetSsid, long timeoutMs) {
        if (wifiManager == null || TextUtils.isEmpty(targetSsid)) return false;
        if (!wifiManager.isWifiEnabled()) wifiManager.setWifiEnabled(true);
        List<WifiConfiguration> networks = wifiManager.getConfiguredNetworks();
        if (networks == null) return false;
        String wanted = normalizeSsid(targetSsid);
        int id = -1;
        for (WifiConfiguration n : networks) {
            if (n != null && wanted.equalsIgnoreCase(normalizeSsid(n.SSID))) { id = n.networkId; break; }
        }
        if (id < 0) return false;
        wifiManager.disconnect();
        if (!wifiManager.enableNetwork(id, true)) return false;
        wifiManager.reconnect();
        long end = System.currentTimeMillis() + Math.max(1000L, timeoutMs);
        while (System.currentTimeMillis() < end) {
            try {
                WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null && info.getIpAddress() != 0
                        && info.getSupplicantState() == SupplicantState.COMPLETED) {
                    String current = normalizeSsid(info.getSSID());
                    if (wanted.equalsIgnoreCase(current) || TextUtils.isEmpty(current)
                            || "0x".equalsIgnoreCase(current) || "<unknown ssid>".equalsIgnoreCase(current)) return true;
                }
                Thread.sleep(1000L);
            } catch (Exception ignored) { }
        }
        return false;
    }

    public boolean restoreClassicNetwork(String classicSsid) {
        return connectToSsid(classicSsid, Config.UPLOAD_RESTORE_WAIT_MS);
    }

    public List<String> parseUploadSsidList(String csv) {
        List<String> out = new ArrayList<String>();
        if (TextUtils.isEmpty(csv)) return out;
        for (String s : csv.split(",")) { String c = normalizeSsid(s); if (!TextUtils.isEmpty(c)) out.add(c); }
        return out;
    }

    private static String normalizeSsid(String ssid) {
        if (ssid == null) return null;
        String v = ssid.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length()-1);
        return v;
    }
    private static String ip(int x) {
        if (x == 0) return "0.0.0.0";
        return (x&255)+"."+((x>>8)&255)+"."+((x>>16)&255)+"."+((x>>24)&255);
    }
}
