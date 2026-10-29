package jp.terada.midnitelogger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class SupabaseUploader {
    private final Context context;
    private final Database db;

    public SupabaseUploader(Context context, Database db) {
        this.context = context;
        this.db = db;
    }

    public int upload30s() throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(Config.PREFS, 0);
        String url = clean(preferences.getString("supabase_rpc_url", ""));
        String key = clean(preferences.getString("supabase_key", ""));
        String deviceId = clean(preferences.getString("device_id", "shl24-01"));

        if (url.length() == 0 || key.length() == 0) {
            throw new IOException("Supabase URL/API key未設定");
        }
        if (!url.startsWith("https://")) {
            throw new IOException("Supabase URLはhttps://で指定してください");
        }

        long deadline = System.currentTimeMillis() + Config.UPLOAD_LIMIT_MS;
        int uploaded = 0;
        boolean tlsLogged = false;

        while (System.currentTimeMillis() < deadline) {
            List<Record> rows = db.pending(Config.BATCH_SIZE);
            if (rows.isEmpty()) break;

            JSONObject request = new JSONObject();
            JSONArray records = new JSONArray();
            for (Record row : rows) records.put(toJson(row, deviceId));
            request.put("p_rows", records);

            URL endpoint = new URL(url);
            HttpURLConnection raw = (HttpURLConnection) endpoint.openConnection();
            if (!(raw instanceof HttpsURLConnection)) {
                throw new IOException("HTTPS接続ではありません");
            }
            HttpsURLConnection connection = (HttpsURLConnection) raw;

            // SHL24 (API 17) must not use the platform's old OpenSSL provider.
            if (Build.VERSION.SDK_INT < 21) {
                connection.setSSLSocketFactory(ConscryptTls.getSocketFactory());
                if (!tlsLogged) {
                    db.event("INFO", "TLS provider=" + ConscryptTls.providerInfo()
                            + " Android API=" + Build.VERSION.SDK_INT);
                    tlsLogged = true;
                }
            }

            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("apikey", key);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Connection", "close");

            byte[] payload = request.toString().getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(payload.length);
            db.event("INFO", "Supabase HTTPS接続開始 rows=" + rows.size()
                    + " bytes=" + payload.length);

            try {
                OutputStream output = connection.getOutputStream();
                output.write(payload);
                output.flush();
                output.close();

                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    String response = read(connection.getErrorStream());
                    throw new IOException("Supabase HTTP " + status + " " + response);
                }

                read(connection.getInputStream());
                db.markUploaded(rows);
                uploaded += rows.size();
                db.event("OK", "Supabase batch成功 rows=" + rows.size());
            } catch (javax.net.ssl.SSLHandshakeException e) {
                db.event("ERROR", "Conscrypt TLS handshake失敗 provider="
                        + ConscryptTls.providerInfo() + " cause=" + chain(e));
                throw e;
            } finally {
                connection.disconnect();
            }
        }
        return uploaded;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "").trim();
    }

    private JSONObject toJson(Record r, String deviceId) throws JSONException {
        JSONObject j = new JSONObject();
        j.put("device_id", deviceId);
        j.put("measured_at", r.ts.replace(' ', 'T') + "+09:00");
        j.put("battery_v", r.batteryV);
        j.put("pv_v", r.pvV);
        j.put("classic_a", r.classicA);
        j.put("pv_a", r.pvA);
        j.put("watts", r.watts);
        j.put("daily_kwh", r.dailyKwh);
        j.put("charge_stage", r.chargeStage);
        j.put("classic_state", r.classicState);
        j.put("battery_temp", r.batteryTemp);
        j.put("fet_temp", r.fetTemp);
        j.put("pcb_temp", r.pcbTemp);
        j.put("wb_a", r.wbA);
        j.put("wb_precise_a", r.wbPreciseA);
        return j;
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) result.append(line);
        reader.close();
        return result.toString();
    }

    private static String chain(Throwable t) {
        StringBuilder value = new StringBuilder();
        Throwable current = t;
        int count = 0;
        while (current != null && count < 5) {
            if (count > 0) value.append(" <- ");
            value.append(current.getClass().getSimpleName());
            if (current.getMessage() != null) value.append(": ").append(current.getMessage());
            current = current.getCause();
            count++;
        }
        return value.toString();
    }
}
