package org.fogofwarlabs.sc4j.screnhanced.script;

import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrDrawList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes the commands of a draw list space as text lines, one per command with its kind and values.
 */
public final class DrawListCheck {
    /**
     * Records the painted commands as text.
     */
    private static final class RecordingPainter implements ScrDrawList.Painter {
        private final @NotNull List<String> commands;

        private RecordingPainter() {
            this.commands = new ArrayList<>();
        }

        @Override
        public void fillRectangle(float x, float y, float width, float height, int color) {
            this.commands.add("fillRectangle " + x + ' ' + y + ' ' + width + ' ' + height + ' ' + Integer.toHexString(color));
        }

        @Override
        public void drawRectangle(float x, float y, float width, float height, float thickness, int color) {
            this.commands.add("drawRectangle " + x + ' ' + y + ' ' + width + ' ' + height + ' ' + thickness + ' ' + Integer.toHexString(color));
        }

        @Override
        public void fillCircle(float x, float y, float radius, int color) {
            this.commands.add("fillCircle " + x + ' ' + y + ' ' + radius + ' ' + Integer.toHexString(color));
        }

        @Override
        public void drawCircle(float x, float y, float radius, float thickness, int color) {
            this.commands.add("drawCircle " + x + ' ' + y + ' ' + radius + ' ' + thickness + ' ' + Integer.toHexString(color));
        }

        @Override
        public void drawLine(float sourceX, float sourceY, float destinationX, float destinationY, float thickness, int color) {
            this.commands.add("drawLine " + sourceX + ' ' + sourceY + ' ' + destinationX + ' ' + destinationY + ' ' + thickness + ' ' +
                              Integer.toHexString(color));
        }

        @Override
        public void drawPoint(float x, float y, int color) {
            this.commands.add("drawPoint " + x + ' ' + y + ' ' + Integer.toHexString(color));
        }

        @Override
        public void drawText(@NotNull String text, float x, float y, ScrDrawList.@NotNull Font font, ScrDrawList.@NotNull Align align, int color) {
            this.commands.add("drawText " + text + ' ' + x + ' ' + y + ' ' + font + ' ' + align + ' ' + Integer.toHexString(color));
        }

        @Override
        public void drawPicture(@NotNull Picture picture,
                                float sourceX, float sourceY, float sourceWidth, float sourceHeight,
                                float x, float y, float width, float height,
                                float opacity) {
            boolean whole = sourceX == 0 && sourceY == 0 && sourceWidth == picture.getWidth() && sourceHeight == picture.getHeight();

            this.commands.add("drawPicture " + picture.getWidth() + 'x' + picture.getHeight() + ' ' + x + ' ' + y + ' ' + width + ' ' + height + ' ' +
                              opacity + (whole ? "" : " from " + sourceX + ' ' + sourceY + ' ' + sourceWidth + ' ' + sourceHeight));
        }
    }

    /**
     * Describes the commands of one space.
     *
     * @param list  the draw list, or {@code null} for none
     * @param space the space
     * @return one line per command, in the list's order; none without a list
     */
    public static @NotNull List<String> describe(@Nullable ScrDrawList list, ScrDrawList.@NotNull Space space) {
        RecordingPainter painter = new RecordingPainter();

        if (list != null) {
            list.paint(space, painter);
        }

        return painter.commands;
    }

    private DrawListCheck() {
    }
}
