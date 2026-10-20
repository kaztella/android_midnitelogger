package jp.terada.midnitelogger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChartView extends View {
    public static class Point {
        public final long time;
        public final float value;
        public Point(long time, float value) { this.time = time; this.value = value; }
    }

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private List<Point> points = new ArrayList<Point>();
    private String title = "バッテリー電圧（24時間）";
    private String unit = "V";

    public ChartView(Context context) { super(context); init(); }
    public ChartView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public ChartView(Context context, AttributeSet attrs, int style) { super(context, attrs, style); init(); }

    private void init() {
        setBackgroundColor(Color.WHITE);
        grid.setColor(Color.rgb(210, 215, 220));
        grid.setStrokeWidth(1f);
        line.setColor(Color.rgb(0, 105, 92));
        line.setStrokeWidth(3f);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setColor(Color.DKGRAY);
        text.setTextSize(24f);
    }

    public void setSeries(String title, String unit, List<Point> values) {
        this.title = title;
        this.unit = unit;
        this.points = values == null ? new ArrayList<Point>() : values;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float left = 70f, top = 55f, right = getWidth() - 18f, bottom = getHeight() - 48f;
        canvas.drawText(title, left, 32f, text);
        if (right <= left || bottom <= top) return;

        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            canvas.drawLine(left, y, right, y, grid);
        }
        for (int i = 0; i <= 4; i++) {
            float x = left + (right - left) * i / 4f;
            canvas.drawLine(x, top, x, bottom, grid);
        }
        if (points.size() < 2) {
            canvas.drawText("表示できるデータがありません", left + 20f, (top + bottom) / 2f, text);
            return;
        }

        float min = points.get(0).value, max = min;
        long minTime = points.get(0).time, maxTime = minTime;
        for (Point p : points) {
            if (p.value < min) min = p.value;
            if (p.value > max) max = p.value;
            if (p.time < minTime) minTime = p.time;
            if (p.time > maxTime) maxTime = p.time;
        }
        float padding = Math.max((max - min) * 0.12f, 0.2f);
        min -= padding; max += padding;
        if (maxTime == minTime) maxTime++;

        text.setTextSize(20f);
        canvas.drawText(format(max) + unit, 2f, top + 8f, text);
        canvas.drawText(format(min) + unit, 2f, bottom, text);
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.US);
        canvas.drawText(fmt.format(new Date(minTime)), left, getHeight() - 12f, text);
        String end = fmt.format(new Date(maxTime));
        canvas.drawText(end, right - text.measureText(end), getHeight() - 12f, text);

        path.reset();
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            float x = left + (right - left) * (p.time - minTime) / (float) (maxTime - minTime);
            float y = bottom - (bottom - top) * (p.value - min) / (max - min);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, line);
        text.setTextSize(24f);
    }

    private String format(float value) { return String.format(Locale.US, "%.1f", value); }
}
