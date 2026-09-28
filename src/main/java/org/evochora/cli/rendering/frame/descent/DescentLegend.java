package org.evochora.cli.rendering.frame.descent;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Locale;

import org.evochora.cli.rendering.overlay.OverlayFonts;
import org.evochora.node.processes.http.api.visualizer.descent.Ancestry;

/**
 * The legend of the {@code descent} renderer: a panel in the bottom-left corner of a frame that
 * says what the colours of the frame mean.
 * <p>
 * The panel has two columns of three rows, filled column by column:
 * <ul>
 *   <li><em>Root</em> (column 1, row 1): {@code Root born 192.75M}, the birth tick of the frame's
 *       root, or {@code Root all} for the virtual root above the founders.</li>
 *   <li><em>Line cells</em> (column 1 rows 2 and 3, column 2 rows 1 and 2): the first
 *       {@value #LINE_CELLS} coloured lines in rank order that have living organisms in the frame,
 *       each with a square in the colour the line has in the frame, its share of the living
 *       organisms and its birth tick.</li>
 *   <li><em>More</em> (column 2, row 3): shown when living organisms belong to lines without a
 *       cell of their own. One square per colour that falls under it (every further coloured line
 *       with living organisms in rank order, then the tone of the lines beyond the palette), the
 *       number of these lines and their joint share: {@code 3 more (2%)}.</li>
 * </ul>
 * Cells with nothing to show stay empty. The panel matches the info overlay's panel in font,
 * padding, line spacing, corners, border, background and height, and its bottom edge lies on the
 * same pixel row. It is two columns wide on a frame whose second column holds a cell (a line cell
 * or the "more" cell), else one column wide, so that it hides no more of the picture than it
 * uses. The column widths are fixed for the whole video: each column is as wide as the widest
 * text a cell can take, worked out once from template texts (the widest birth tick up to the
 * last tick of the range, {@code 100%}, the most lines a root has), so that no cell moves from
 * frame to frame.
 * <p>
 * What the cells show is decided by {@link #update} and can be read back without drawing; the
 * pixels are drawn by {@link #draw}. Neither allocates a collection or an array; the tick and
 * count texts are formatted when they change and kept otherwise.
 * <p>
 * <strong>Thread Safety:</strong> not thread-safe; one instance per renderer instance.
 */
final class DescentLegend {

    /** Number of line cells. */
    static final int LINE_CELLS = 4;

    /** Line cells in the first column, below the root cell. */
    private static final int FIRST_COLUMN_CELLS = 2;

    /** Coloured ranks: one per palette colour. */
    private static final int COLOURED = DescentRenderer.PALETTE.length;

    /** Index of the lines beyond the palette in the arrays handed to {@link #update}. */
    static final int OTHER = COLOURED;

    /** Most squares the "more" cell can show: every coloured rank without a cell, and the other lines. */
    private static final int MORE_SQUARES = COLOURED - LINE_CELLS + 1;

    /** Ticks from this on are written in millions. */
    private static final long MILLION = 1_000_000L;

    private static final String ROOT_LABEL = "Root";
    private static final String ALL = "all";
    private static final String BORN_LABEL = "born";
    private static final String MORE_LABEL = " more ";
    private static final String FULL_SHARE = "100%";
    private static final String BELOW_ONE = "<1%";
    private static final String BRACKETED_BELOW_ONE = "(" + BELOW_ONE + ")";

    /** Share texts, indexed by whole percent. */
    private static final String[] SHARES = new String[101];
    /** Share texts in brackets, indexed by whole percent. */
    private static final String[] BRACKETED_SHARES = new String[101];

    static {
        for (int percent = 0; percent <= 100; percent++) {
            SHARES[percent] = percent + "%";
            BRACKETED_SHARES[percent] = "(" + percent + "%)";
        }
    }

    // ── Content, rewritten by update() ──────────────────────────────────────────
    private boolean updated;
    private boolean rootAll;
    private String rootBorn;
    private int cellCount;
    private final int[] cellRanks = new int[LINE_CELLS];
    private final int[] cellColours = new int[LINE_CELLS];
    private final String[] cellShares = new String[LINE_CELLS];
    private final String[] cellBorns = new String[LINE_CELLS];
    private int moreSquares;
    private final int[] moreColours = new int[MORE_SQUARES];
    private int moreLines;
    private String moreShare;

    // Text caches: the root cell and every line cell keep the text of their last tick
    private final long[] cachedTicks = new long[LINE_CELLS + 1];
    private final String[] cachedTickTexts = new String[LINE_CELLS + 1];
    private int cachedCount = -1;
    private String cachedCountText;

    // Colours of the squares: the line cells first, then the squares of the "more" cell
    private final int[] cachedRgb = new int[LINE_CELLS + MORE_SQUARES];
    private final Color[] cachedColours = new Color[LINE_CELLS + MORE_SQUARES];

    // ── Layout, fixed at construction ───────────────────────────────────────────
    private final Font font;
    private final FontMetrics metrics;
    private final BasicStroke stroke;
    private final int borderRadius;
    private final int borderWidth;
    private final int panelX;
    private final int panelY;
    /** Width of the panel with the first column alone. */
    private final int narrowWidth;
    /** Width of the panel with both columns. */
    private final int wideWidth;
    /** Width of the panel for the content {@link #update} decided last. */
    private int panelWidth;
    private final int panelHeight;
    private final int[] columnX = new int[2];
    private final int firstBaseline;
    private final int rowStep;
    private final int square;
    private final int squareGap;
    private final int space;
    private final int rootValueOffset;
    private final int bornWidth;
    private final int shareWidth;

    /**
     * Creates a legend for frames of one size and works out its layout.
     *
     * @param imageWidth  Width of the frames in pixels (must be &gt; 0)
     * @param imageHeight Height of the frames in pixels (must be &gt; 0)
     * @param lastTick    The last tick of the rendered range: no birth tick shown lies beyond it
     * @param maxLines    The most lines any root of the video has: the "more" cell counts no more
     * @throws IllegalArgumentException if a size is not positive
     */
    DescentLegend(final int imageWidth, final int imageHeight, final long lastTick, final int maxLines) {
        if (imageWidth <= 0 || imageHeight <= 0) {
            throw new IllegalArgumentException("Legend needs a positive frame size, got " + imageWidth + "x" + imageHeight);
        }
        Arrays.fill(cachedTicks, Long.MIN_VALUE);
        Arrays.fill(cachedRgb, -1);

        // The same measures InfoOverlayRenderer derives from the image width
        final int fontSize = OverlayFonts.computeFontSize(imageWidth);
        final int margin = OverlayFonts.computeMargin(imageWidth);
        final int paddingX = fontSize;
        final int paddingY = fontSize / 2;
        final int lineSpacing = fontSize / 4;
        this.borderRadius = fontSize / 2;
        this.borderWidth = Math.max(1, fontSize / 10);
        this.stroke = new BasicStroke(borderWidth);
        this.font = OverlayFonts.getDataFont(fontSize);

        final BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = scratch.createGraphics();
        final int digitHeight;
        try {
            applyHints(g);
            g.setFont(font);
            this.metrics = g.getFontMetrics();
            digitHeight = (int) Math.round(font.createGlyphVector(g.getFontRenderContext(), "0")
                .getVisualBounds().getHeight());
        } finally {
            g.dispose();
        }

        this.square = Math.max(1, digitHeight);
        this.squareGap = Math.max(1, square / 3);
        this.space = metrics.stringWidth(" ");
        this.bornWidth = metrics.stringWidth(BORN_LABEL + " ");
        this.shareWidth = metrics.stringWidth(FULL_SHARE);
        this.rootValueOffset = metrics.stringWidth(ROOT_LABEL + " ");

        final long highest = Math.max(0, lastTick);
        final int tickWidth = Math.max(metrics.stringWidth(formatTick(Math.min(highest, MILLION - 1))),
            metrics.stringWidth(formatTick(highest)));
        final int rootCell = rootValueOffset + Math.max(bornWidth + tickWidth, metrics.stringWidth(ALL));
        final int lineCell = square + space + shareWidth + space + bornWidth + tickWidth;
        final int moreCell = MORE_SQUARES * (square + squareGap) - squareGap + space
            + metrics.stringWidth(Integer.toString(Math.max(0, maxLines)))
            + metrics.stringWidth(MORE_LABEL) + metrics.stringWidth("(" + FULL_SHARE + ")");
        final int column1 = Math.max(rootCell, lineCell);
        final int column2 = Math.max(lineCell, moreCell);
        final int columnGap = metrics.stringWidth("    ");

        final int lineHeight = metrics.getHeight();
        this.narrowWidth = paddingX * 2 + column1;
        this.wideWidth = narrowWidth + columnGap + column2;
        this.panelWidth = narrowWidth;
        this.panelHeight = paddingY * 2 + lineHeight * 3 + lineSpacing * 2;
        this.panelX = margin;
        this.panelY = imageHeight - panelHeight - margin;
        this.columnX[0] = panelX + paddingX;
        this.columnX[1] = columnX[0] + column1 + columnGap;
        this.firstBaseline = panelY + paddingY + metrics.getAscent();
        this.rowStep = lineHeight + lineSpacing;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Content
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Decides what the cells show for a frame.
     * <p>
     * The coloured ranks are taken in rank order; the first {@value #LINE_CELLS} with living
     * organisms get a line cell, every further one with living organisms falls under the "more"
     * cell, as do the lines beyond the palette when they have living organisms. A share is the
     * living organisms of a line, or of the lines under "more", over all living organisms of the
     * frame, in whole percent; a share above 0 that rounds below 1 reads {@code <1%}.
     *
     * @param root           The frame's root, {@link Ancestry#NO_PARENT} for the virtual root
     * @param rootBirthTick  The root's birth tick; not read for the virtual root
     * @param lineBirthTicks The birth tick of the line of every coloured rank the root has; every
     *                       rank with living organisms must have one
     * @param living         Living organisms per coloured rank, and at {@link #OTHER} those of the
     *                       lines beyond the palette together (length &gt; {@link #OTHER})
     * @param otherLines     Distinct lines beyond the palette with living organisms
     * @param total          All living organisms of the frame, the root and the organisms outside
     *                       it included (must be at least the sum of {@code living})
     * @param colours        The colour of every coloured rank in this frame, and at {@link #OTHER}
     *                       that of the lines beyond the palette (0xRRGGBB, length &gt; {@link #OTHER})
     */
    void update(final int root, final long rootBirthTick, final long[] lineBirthTicks, final int[] living,
                final int otherLines, final int total, final int[] colours) {
        rootAll = root == Ancestry.NO_PARENT;
        rootBorn = rootAll ? null : tickText(0, rootBirthTick);
        cellCount = 0;
        moreSquares = 0;
        moreLines = 0;
        int moreLiving = 0;
        for (int rank = 0; rank < COLOURED; rank++) {
            final int count = living[rank];
            if (count == 0) {
                continue;
            }
            if (cellCount < LINE_CELLS) {
                cellRanks[cellCount] = rank;
                cellColours[cellCount] = colours[rank];
                cellShares[cellCount] = shareText(count, total, SHARES, BELOW_ONE);
                cellBorns[cellCount] = tickText(cellCount + 1, lineBirthTicks[rank]);
                cellCount++;
            } else {
                moreColours[moreSquares++] = colours[rank];
                moreLines++;
                moreLiving += count;
            }
        }
        if (living[OTHER] > 0) {
            moreColours[moreSquares++] = colours[OTHER];
            moreLines += otherLines;
            moreLiving += living[OTHER];
        }
        moreShare = moreLiving > 0 ? shareText(moreLiving, total, BRACKETED_SHARES, BRACKETED_BELOW_ONE) : null;
        if (moreLiving > 0 && moreLines != cachedCount) {
            cachedCount = moreLines;
            cachedCountText = Integer.toString(moreLines);
        }
        panelWidth = hasSecondColumn() ? wideWidth : narrowWidth;
        updated = true;
    }

    /**
     * Whether the root cell shows the virtual root ({@code Root all}).
     *
     * @return {@code true} for the virtual root above the founders
     */
    boolean rootIsAll() {
        return rootAll;
    }

    /**
     * The birth tick the root cell shows.
     *
     * @return The formatted tick, {@code null} for the virtual root
     */
    String rootBorn() {
        return rootBorn;
    }

    /**
     * The number of line cells shown.
     *
     * @return 0 to {@value #LINE_CELLS}
     */
    int cellCount() {
        return cellCount;
    }

    /**
     * The rank of the line a line cell shows.
     *
     * @param cell The line cell, from 0 (below {@link #cellCount()})
     * @return The line's rank among the lines of the root, from 0
     */
    int cellRank(final int cell) {
        return cellRanks[cell];
    }

    /**
     * The colour of a line cell's square.
     *
     * @param cell The line cell, from 0 (below {@link #cellCount()})
     * @return The colour (0xRRGGBB)
     */
    int cellColour(final int cell) {
        return cellColours[cell];
    }

    /**
     * The share a line cell shows.
     *
     * @param cell The line cell, from 0 (below {@link #cellCount()})
     * @return The share, such as {@code 42%} or {@code <1%}
     */
    String cellShare(final int cell) {
        return cellShares[cell];
    }

    /**
     * The birth tick a line cell shows.
     *
     * @param cell The line cell, from 0 (below {@link #cellCount()})
     * @return The formatted tick
     */
    String cellBorn(final int cell) {
        return cellBorns[cell];
    }

    /**
     * Whether the "more" cell is shown.
     *
     * @return {@code true} when living organisms belong to lines without a cell of their own
     */
    boolean hasMore() {
        return moreShare != null;
    }

    /**
     * Whether the second column holds a cell: a line cell beyond the first column's two, or the
     * "more" cell. The panel is two columns wide exactly then.
     *
     * @return {@code true} when the panel is two columns wide
     */
    boolean hasSecondColumn() {
        return cellCount > FIRST_COLUMN_CELLS || hasMore();
    }

    /**
     * The number of squares of the "more" cell.
     *
     * @return The count, 0 when the cell is not shown
     */
    int moreSquareCount() {
        return moreSquares;
    }

    /**
     * The colour of a square of the "more" cell.
     *
     * @param square The square, from 0 (below {@link #moreSquareCount()})
     * @return The colour (0xRRGGBB)
     */
    int moreSquareColour(final int square) {
        return moreColours[square];
    }

    /**
     * The number of lines the "more" cell counts.
     *
     * @return Distinct lines with living organisms that have no cell of their own
     */
    int moreLines() {
        return moreLines;
    }

    /**
     * The text of the "more" cell, such as {@code 3 more (2%)}. Made on each call; the drawing
     * does not use it.
     *
     * @return The text, {@code null} when the cell is not shown
     */
    String moreText() {
        return hasMore() ? cachedCountText + MORE_LABEL + moreShare : null;
    }

    /**
     * Formats a tick as the legend shows it: from a million on in millions with two decimals
     * ({@code 192.75M}), below that in full with a comma between groups of three digits
     * ({@code 123,456}); both in {@link Locale#US}.
     *
     * @param tick The tick (&gt;= 0)
     * @return The text
     */
    static String formatTick(final long tick) {
        if (tick >= MILLION) {
            return String.format(Locale.US, "%.2fM", tick / (double) MILLION);
        }
        return String.format(Locale.US, "%,d", tick);
    }

    private static String shareText(final int count, final int total, final String[] texts, final String belowOne) {
        final int percent = (int) Math.round(100.0 * count / total);
        return percent == 0 ? belowOne : texts[percent];
    }

    /**
     * The text of a tick for one cell, formatted only when the cell's tick changed.
     */
    private String tickText(final int cell, final long tick) {
        if (cachedTicks[cell] != tick || cachedTickTexts[cell] == null) {
            cachedTicks[cell] = tick;
            cachedTickTexts[cell] = formatTick(tick);
        }
        return cachedTickTexts[cell];
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Drawing
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * The panel as it is filled and outlined for the content {@link #update} decided last, one
     * column wide before the first; the border reaches half its width beyond it.
     *
     * @return A new rectangle
     */
    Rectangle panel() {
        return new Rectangle(panelX, panelY, panelWidth, panelHeight);
    }

    /**
     * The width of the panel's border in pixels.
     *
     * @return The width, at least 1
     */
    int borderWidth() {
        return borderWidth;
    }

    /**
     * Draws the panel with what {@link #update} decided last.
     *
     * @param image The frame, of the size the legend was made for
     * @throws IllegalStateException if {@link #update} has not been called
     */
    void draw(final BufferedImage image) {
        if (!updated) {
            throw new IllegalStateException("Legend drawn before its content was decided");
        }
        final Graphics2D g = image.createGraphics();
        try {
            applyHints(g);
            g.setFont(font);
            g.setColor(OverlayFonts.BACKGROUND);
            g.fillRoundRect(panelX, panelY, panelWidth, panelHeight, borderRadius, borderRadius);
            g.setColor(OverlayFonts.BORDER);
            g.setStroke(stroke);
            g.drawRoundRect(panelX, panelY, panelWidth, panelHeight, borderRadius, borderRadius);

            drawRoot(g, columnX[0], firstBaseline);
            for (int cell = 0; cell < cellCount; cell++) {
                final int position = cell + 1;
                drawLine(g, cell, columnX[position / 3], firstBaseline + (position % 3) * rowStep);
            }
            if (hasMore()) {
                drawMore(g, columnX[1], firstBaseline + 2 * rowStep);
            }
        } finally {
            g.dispose();
        }
    }

    private void drawRoot(final Graphics2D g, final int x, final int baseline) {
        g.setColor(OverlayFonts.TEXT_SECONDARY);
        g.drawString(ROOT_LABEL, x, baseline);
        final int valueX = x + rootValueOffset;
        if (rootAll) {
            g.setColor(OverlayFonts.TEXT_PRIMARY);
            g.drawString(ALL, valueX, baseline);
        } else {
            drawBorn(g, rootBorn, valueX, baseline);
        }
    }

    private void drawLine(final Graphics2D g, final int cell, final int x, final int baseline) {
        drawSquare(g, cell, cellColours[cell], x, baseline);
        final int shareX = x + square + space;
        final String share = cellShares[cell];
        g.setColor(OverlayFonts.TEXT_PRIMARY);
        g.drawString(share, shareX + shareWidth - metrics.stringWidth(share), baseline);
        drawBorn(g, cellBorns[cell], shareX + shareWidth + space, baseline);
    }

    private void drawMore(final Graphics2D g, final int x, final int baseline) {
        int squareX = x;
        for (int i = 0; i < moreSquares; i++) {
            drawSquare(g, LINE_CELLS + i, moreColours[i], squareX, baseline);
            squareX += square + squareGap;
        }
        int textX = squareX - squareGap + space;
        g.setColor(OverlayFonts.TEXT_PRIMARY);
        g.drawString(cachedCountText, textX, baseline);
        textX += metrics.stringWidth(cachedCountText);
        g.setColor(OverlayFonts.TEXT_SECONDARY);
        g.drawString(MORE_LABEL, textX, baseline);
        textX += metrics.stringWidth(MORE_LABEL);
        g.setColor(OverlayFonts.TEXT_PRIMARY);
        g.drawString(moreShare, textX, baseline);
    }

    private void drawBorn(final Graphics2D g, final String tick, final int x, final int baseline) {
        g.setColor(OverlayFonts.TEXT_SECONDARY);
        g.drawString(BORN_LABEL, x, baseline);
        g.setColor(OverlayFonts.TEXT_PRIMARY);
        g.drawString(tick, x + bornWidth, baseline);
    }

    /**
     * Fills a square standing on the baseline, as high as a digit.
     */
    private void drawSquare(final Graphics2D g, final int position, final int rgb, final int x, final int baseline) {
        if (cachedRgb[position] != rgb) {
            cachedRgb[position] = rgb;
            cachedColours[position] = new Color(rgb);
        }
        g.setColor(cachedColours[position]);
        g.fillRect(x, baseline - square, square, square);
    }

    /**
     * The rendering hints of the info overlay, so that both panels are drawn alike.
     */
    private static void applyHints(final Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
    }
}
