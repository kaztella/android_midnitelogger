package jp.terada.midnitelogger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChartView extends View {
    public interface ChartGestureListener {
        void onPan(float deltaRatio);
        void onZoom(float scale);
    }

    private List<float[]> points = new ArrayList<float[]>();
    private String title = "";
    private long startMs;
    private long endMs;
    private Float fixedMin;
    private Float fixedMax;
    private boolean autoY = true;
    private ChartGestureListener gestureListener;
    private float lastTouchX = Float.NaN;
    private float lastPinchSpan = 0f;

    public ChartView(Context context) {
        super(context);
    }

    public void setChartGestureListener(ChartGestureListener listener) {
        this.gestureListener = listener;
    }

    public void set(List<float[]> values, String chartTitle, long s, long e,
                    Float min, Float max, boolean auto) {
        this.points = values == null ? new ArrayList<float[]>() : values;
        this.title = chartTitle;
        this.startMs = s;
        this.endMs = Math.max(e, s + 1000L);
        this.fixedMin = min;
        this.fixedMax = max;
        this.autoY = auto;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.DKGRAY);
        paint.setTextSize(28);
        canvas.drawText(title, 12, 30, paint);

        float left = 60f;
        float top = 48f;
        float right = getWidth() - 12f;
        float bottom = getHeight() - 58f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.LTGRAY);
        canvas.drawRect(left, top, right, bottom, paint);
        labels(canvas, paint, left, right, bottom);

        if (points == null || points.size() < 2) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.DKGRAY);
            paint.setTextSize(24);
            canvas.drawText("表示データがありません", 30, 100, paint);
            return;
        }

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float[] row : points) {
            if (row != null && row.length > 1) {
                min = Math.min(min, row[1]);
                max = Math.max(max, row[1]);
            }
        }

        if (autoY || fixedMin == null || fixedMax == null) {
            if (max == min) {
                max += 1f;
                min -= 1f;
            }
        } else {
            min = fixedMin;
            max = fixedMax;
            if (max <= min) {
                float span = Math.abs(max - min);
                if (span < 0.0001f) {
                    if (max == 0f) {
                        min = -1f;
                        max = 1f;
                    } else {
                        min = max - 1f;
                        max = max + 1f;
                    }
                } else {
                    if (fixedMax < fixedMin) {
                        float temp = min;
                        min = max;
                        max = temp;
                    }
                }
            }
        }

        Path path = new Path();
        for (int i = 0; i < points.size(); i++) {
            float[] row = points.get(i);
            if (row == null || row.length < 2) continue;
            long timestamp = startMs + (long) row[0] * 1000L;
            float ratio = (float) (timestamp - startMs) / (float) (endMs - startMs);
            ratio = Math.max(0f, Math.min(1f, ratio));
            float x = left + (right - left) * ratio;
            float y = bottom - (bottom - top) * (row[1] - min) / (max - min);
            if (i == 0 || path.isEmpty()) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }

        paint.setColor(Color.BLUE);
        paint.setStrokeWidth(3f);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(path, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.DKGRAY);
        paint.setTextSize(20);
        canvas.drawText(String.format(Locale.US, "%.2f", max), 2, top + 10, paint);
        canvas.drawText(String.format(Locale.US, "%.2f", min), 2, bottom, paint);
    }

    private void labels(Canvas canvas, Paint paint, float left, float right, float bottom) {
        SimpleDateFormat format = new SimpleDateFormat("MM/dd HH:mm", Locale.US);
        String start = format.format(new Date(startMs));
        String middle = format.format(new Date(startMs + (endMs - startMs) / 2));
        String end = format.format(new Date(endMs));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.DKGRAY);
        paint.setTextSize(18);
        canvas.drawText(start, left, bottom + 28, paint);
        canvas.drawText(middle, (left + right - paint.measureText(middle)) / 2, bottom + 28, paint);
        canvas.drawText(end, right - paint.measureText(end), bottom + 28, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (gestureListener == null) {
            return super.onTouchEvent(event);
        }

        final int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastPinchSpan = 0f;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2) {
                    lastPinchSpan = spacing(event);
                    if (lastPinchSpan > 0f) {
                        return true;
                    }
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() >= 2 && lastPinchSpan > 0f) {
                    float current = spacing(event);
                    if (current > 0f) {
                        float scale = current / lastPinchSpan;
                        lastPinchSpan = current;
                        gestureListener.onZoom(scale);
                    }
                    return true;
                }
                if (!Float.isNaN(lastTouchX)) {
                    float dx = event.getX() - lastTouchX;
                    lastTouchX = event.getX();
                    if (Math.abs(dx) > 3f) {
                        gestureListener.onPan(dx / getWidth());
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lastTouchX = Float.NaN;
                lastPinchSpan = 0f;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private float spacing(MotionEvent event) {
        if (event.getPointerCount() < 2) {
            return 0f;
        }
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
