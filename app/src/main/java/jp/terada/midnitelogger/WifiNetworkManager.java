package jp.terada.midnitelogger;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.List;

public class WifiNetworkManager {
    private static final long POLL_MS = 1000L;
    private final WifiManager wifiManager;

    public WifiNetworkManager(Context context) {
        Context app = context.getApplicationContext();
        this.wifiManager = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
    }

    public String getConnectedSsid() {
        try {
            WifiInfo info = getInfo();
            if (!hasIp(info)) return null;
            String ssid = normalizeSsid(info.getSSID());
            return isInvalidSsid(ssid) ? null : ssid;
        } catch (Exception ignored) {
            return null;
        }
    }

    public boolean isNetworkReady() {
        try {
            WifiInfo info = getInfo();
            return hasIp(info) && info.getSupplicantState() == SupplicantState.COMPLETED;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Wait until Wi-Fi association and DHCP are both complete. */
    public boolean waitForNetworkReady(long timeoutMs) {
        long end = System.currentTimeMillis() + Math.max(POLL_MS, timeoutMs);
        while (System.currentTimeMillis() < end) {
            if (isNetworkReady()) return true;
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isNetworkReady();
    }

    public String connectionDiagnostics() {
        try {
            WifiInfo info = getInfo();
            if (info == null) return "info=null";
            return "SSID=" + normalizeSsid(info.getSSID())
                    + " IP=" + ip(info.getIpAddress())
                    + " state=" + String.valueOf(info.getSupplicantState())
                    + " RSSI=" + info.getRssi();
        } catch (Exception e) {
            return "diag=" + e.getClass().getSimpleName();
        }
    }

    public boolean isConnectedTo(String targetSsid) {
        String current = getConnectedSsid();
        return !TextUtils.isEmpty(targetSsid)
                && !TextUtils.isEmpty(current)
                && current.equalsIgnoreCase(normalizeSsid(targetSsid));
    }

    public boolean shouldUploadOnCurrentNetwork(List<String> uploadSsids) {
        String current = getConnectedSsid();
        if (TextUtils.isEmpty(current) || uploadSsids == null) return false;
        for (String ssid : uploadSsids) {
            if (!TextUtils.isEmpty(ssid)
                    && current.equalsIgnoreCase(normalizeSsid(ssid))) return true;
        }
        return false;
    }

    public String findUploadNetworkInScanResults(List<String> uploadSsids) {
        if (wifiManager == null || uploadSsids == null || uploadSsids.isEmpty()) return null;
        if (!wifiManager.isWifiEnabled()) wifiManager.setWifiEnabled(true);
        try {
            wifiManager.startScan();
            List<ScanResult> results = wifiManager.getScanResults();
            if (results == null) return null;
            for (ScanResult result : results) {
                String seen = result == null ? null : normalizeSsid(result.SSID);
                for (String wanted : uploadSsids) {
                    if (!TextUtils.isEmpty(seen)
                            && seen.equalsIgnoreCase(normalizeSsid(wanted))) {
                        return normalizeSsid(wanted);
                    }
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    public boolean connectToSsid(String targetSsid) {
        return connectToSsid(targetSsid, Config.WIFI_SWITCH_TIMEOUT_MS);
    }

    /**
     * A successful return means association is COMPLETED and DHCP assigned a
     * non-zero IP address. enableNetwork() alone is not treated as success.
     */
    public boolean connectToSsid(String targetSsid, long timeoutMs) {
        if (wifiManager == null || TextUtils.isEmpty(targetSsid)) return false;
        if (!wifiManager.isWifiEnabled()) wifiManager.setWifiEnabled(true);

        int networkId = findConfiguredNetworkId(targetSsid);
        if (networkId < 0) return false;

        try {
            wifiManager.disconnect();
            if (!wifiManager.enableNetwork(networkId, true)) return false;
            wifiManager.reconnect();
        } catch (Exception e) {
            return false;
        }

        long end = System.currentTimeMillis() + Math.max(POLL_MS, timeoutMs);
        String wanted = normalizeSsid(targetSsid);
        while (System.currentTimeMillis() < end) {
            WifiInfo info = getInfo();
            if (hasIp(info) && info.getSupplicantState() == SupplicantState.COMPLETED) {
                String current = normalizeSsid(info.getSSID());
                // Android 4.2 may temporarily return 0x/unknown even after DHCP.
                if (wanted.equalsIgnoreCase(current) || isInvalidSsid(current)) return true;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public boolean restoreClassicNetwork(String classicSsid) {
        // Use a bounded Wi-Fi/DHCP wait. LoggerService performs the longer
        // Modbus verification loop after this method returns.
        long wait = Math.min(Config.UPLOAD_RESTORE_WAIT_MS, 60000L);
        return connectToSsid(classicSsid, wait);
    }

    public List<String> parseUploadSsidList(String csv) {
        List<String> result = new ArrayList<String>();
        if (TextUtils.isEmpty(csv)) return result;
        String[] values = csv.split(",");
        for (String value : values) {
            String clean = normalizeSsid(value);
            if (!TextUtils.isEmpty(clean)) result.add(clean);
        }
        return result;
    }

    private WifiInfo getInfo() {
        return wifiManager == null ? null : wifiManager.getConnectionInfo();
    }

    private boolean hasIp(WifiInfo info) {
        return info != null && info.getIpAddress() != 0;
    }

    private int findConfiguredNetworkId(String targetSsid) {
        try {
            List<WifiConfiguration> networks = wifiManager.getConfiguredNetworks();
            if (networks == null) return -1;
            String wanted = normalizeSsid(targetSsid);
            for (WifiConfiguration network : networks) {
                if (network != null
                        && wanted.equalsIgnoreCase(normalizeSsid(network.SSID))) {
                    return network.networkId;
                }
            }
        } catch (Exception ignored) {
            return -1;
        }
        return -1;
    }

    private static boolean isInvalidSsid(String ssid) {
        return TextUtils.isEmpty(ssid)
                || "0x".equalsIgnoreCase(ssid)
                || "<unknown ssid>".equalsIgnoreCase(ssid)
                || "unknown ssid".equalsIgnoreCase(ssid);
    }

    private static String normalizeSsid(String ssid) {
        if (ssid == null) return null;
        String value = ssid.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String ip(int value) {
        if (value == 0) return "0.0.0.0";
        return (value & 255) + "."
                + ((value >> 8) & 255) + "."
                + ((value >> 16) & 255) + "."
                + ((value >> 24) & 255);
    }
}
