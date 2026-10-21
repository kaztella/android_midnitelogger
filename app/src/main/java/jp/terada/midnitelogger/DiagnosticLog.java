package jp.terada.midnitelogger;

import android.content.Context;
import android.util.Log;

public final class DiagnosticLog {
    private static final String TAG = "MidniteLogger";
    private DiagnosticLog() { }

    public static void i(Context context, String message) { write(context, "INFO", message, null); }
    public static void ok(Context context, String message) { write(context, "OK", message, null); }
    public static void send(Context context, String message) { write(context, "SEND", message, null); }
    public static void recv(Context context, String message) { write(context, "RECV", message, null); }
    public static void data(Context context, String message) { write(context, "DATA", message, null); }
    public static void e(Context context, String message, Throwable error) { write(context, "ERROR", message, error); }

    private static void write(Context context, String level, String message, Throwable error) {
        String clean = message == null ? "" : message.replace('\n', ' ');
        if (clean.length() > 300) clean = clean.substring(0, 300);
        String line = "[" + level + "] " + clean;
        if (error == null) Log.i(TAG, line); else Log.e(TAG, line, error);
        Database db = null;
        try {
            db = new Database(context.getApplicationContext());
            db.addEvent(line);
            db.trimEvents(200);
        } catch (Exception ignored) {
            Log.e(TAG, "Could not persist diagnostic log", ignored);
        } finally {
            if (db != null) db.close();
        }
    }
}
