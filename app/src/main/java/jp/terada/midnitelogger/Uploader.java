package jp.terada.midnitelogger;

import android.database.Cursor;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class Uploader {
    public static int upload(Database db, String url, int seconds, int batchSize) throws Exception {
        if (url == null || url.length() == 0) return 0;
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        int sent = 0;
        int batches = 0;
        while (System.currentTimeMillis() < deadline - 8000 && batches < Config.UPLOAD_MAX_BATCHES) {
            Cursor cursor = db.pending(batchSize);
            StringBuilder body = new StringBuilder();
            long maxId = -1;
            int rowCount = 0;
            try {
                while (cursor.moveToNext()) {
                    maxId = cursor.getLong(0);
                    for (int column = 0; column < cursor.getColumnCount(); column++) {
                        if (column > 0) body.append(',');
                        String value = cursor.getString(column);
                        if (value != null) body.append(value.replace("\n", " ").replace("\r", " "));
                    }
                    body.append('\n');
                    rowCount++;
                }
            } finally { cursor.close(); }
            if (maxId < 0) break;
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(7000); connection.setReadTimeout(10000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "text/csv; charset=utf-8");
            byte[] bytes = body.toString().getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            OutputStream output = connection.getOutputStream();
            try { output.write(bytes); } finally { output.close(); }
            int code = connection.getResponseCode(); connection.disconnect();
            if (code < 200 || code >= 300) break;
            db.acknowledge(maxId); sent += rowCount; batches++;
        }
        return sent;
    }
}
