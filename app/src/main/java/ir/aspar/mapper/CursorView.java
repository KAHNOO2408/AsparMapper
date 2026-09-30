package ir.aspar.mapper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** The mouse pointer drawn by Aspar Mapper while its own cursor is in use (loot boxes, menus). */
final class CursorView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();

    CursorView(Context c) {
        super(c);
        fill.setColor(0xFFFFFFFF);
        stroke.setColor(0xFF000000);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(c, 1.5f));
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    protected void onDraw(Canvas c) {
        float u = getWidth() / 24f;
        arrow.reset();
        // classic arrow, tip at the top-left corner
        arrow.moveTo(1 * u, 1 * u);
        arrow.lineTo(1 * u, 19 * u);
        arrow.lineTo(6 * u, 14.5f * u);
        arrow.lineTo(9.5f * u, 22 * u);
        arrow.lineTo(12.5f * u, 20.5f * u);
        arrow.lineTo(9 * u, 13.5f * u);
        arrow.lineTo(15 * u, 13.5f * u);
        arrow.close();
        c.drawPath(arrow, fill);
        c.drawPath(arrow, stroke);
    }
}
