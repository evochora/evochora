package org.evochora.runtime.worldgen;

import java.util.Arrays;

import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.ScanLineArc;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * The scan lines one organism's genome lies on along its direction vector, and the stretch of each
 * scan line the organism occupies.
 * <p>
 * An organism's instruction pointer moves along its direction vector, so while the organism keeps
 * that vector the code it reads is what lies on one scan line within its body. An operator that
 * reads or writes a genome needs to know which scan lines there are and where on each of them the
 * body begins and ends. This class answers that once per build, for the reading frame
 * ({@link GenomeFrame}), for {@link GenomeFlow} and for the operators that search a scan line for
 * room.
 * <p>
 * <strong>One direction.</strong> Everything this class answers holds for the one direction vector
 * it is built with. An organism that turns reads its cells along other scan lines, and none of
 * the answers describes that. The mutation operators build it for a newborn with the direction
 * vector it is born with and accept this limit; nothing outside them may rely on it, which is why
 * the class is not visible outside this package.
 * <p>
 * <strong>What a scan line holds.</strong> The organism's cells are grouped by every coordinate but
 * the one the direction vector points along. The extent of a scan line is the arc of {@link
 * ScanLineArc} over the coordinates the organism owns on it: from the smallest to the largest, gaps
 * and foreign cells included, crossing the world edge only where the world is toroidal and the
 * largest gap lies inside. The organism's initial position counts among those coordinates where it
 * lies on a scan line the organism owns cells on, owned or not: its instruction pointer enters the
 * code there, and the cells it runs over from there belong to the genome it reads. An initial
 * position on a scan line without such a cell opens no scan line.
 * <p>
 * <strong>Order.</strong> The organism's cells are visited in flat-index order. Scan lines are
 * numbered in the order their first cell is met, and the organism's LABEL cells are listed in the
 * order they are met, so that a draw over either does not depend on the order in which the cells
 * were written.
 * <p>
 * <strong>Allocation.</strong> The tables survive a build and are cleared at the beginning of the
 * next one; after the first builds the only allocations left per build are the visitors handed to
 * the environment's owned-cell visit.
 * <p>
 * <strong>Thread safety.</strong> Not thread-safe: an instance holds the tables of the build that
 * is running, so every user keeps its own.
 */
final class GenomeScanLines {

    /** Index of a scan line per perpendicular key. */
    private final Int2IntOpenHashMap scanLineByKey = new Int2IntOpenHashMap();

    /** Smallest coordinate along the walk's dimension the arc is resolved over, per scan line. */
    private int[] minDv = new int[0];

    /** Largest coordinate along the walk's dimension the arc is resolved over, per scan line. */
    private int[] maxDv = new int[0];

    /**
     * Number of coordinates the arc is resolved over, per scan line: the owned cells, and the
     * initial position where it lies on the scan line.
     */
    private int[] count = new int[0];

    /** Flat index of the first owned cell met, per scan line. */
    private int[] sampleFlatIndex = new int[0];

    /** First coordinate of the arc, per scan line. */
    private int[] arcStart = new int[0];

    /** Last coordinate of the arc, per scan line; smaller than the first if the arc crosses the edge. */
    private int[] arcEnd = new int[0];

    /** Start of a scan line's segment in the coordinate collector while arcs are resolved. */
    private int[] segmentStart = new int[0];

    /** Number of coordinates already placed in a scan line's segment. */
    private int[] segmentFill = new int[0];

    /** Number of scan lines of the current build. */
    private int scanLineCount;

    /** Flat indices of the organism's LABEL cells, in flat-index order. */
    private final IntArrayList labels = new IntArrayList();

    /** The coordinates an arc is resolved over, segment by scan line, while the arcs are resolved. */
    private int[] dvCoordCollector;

    /** Receives the ends of a scan line's arc. */
    private final ScanLineArc.Result arc = new ScanLineArc.Result();

    /** Coordinates of the cell a grouping pass is looking at. */
    private int[] coordBuffer;

    /** Strides of the perpendicular key, zero in the dimension the walk runs along. */
    private int[] perpStrides;

    /** The organism whose cells were grouped. */
    private int organismId;

    /** Flat index of the organism's initial position; {@code -1} where there are no scanLines. */
    private int initialFlatIndex = -1;

    /** The scan line the initial position lies on, or {@code -1} if it lies on none. */
    private int initialScanLine = -1;

    /** The dimension the direction vector points along; {@code -1} where it names no direction. */
    private int dvDim = -1;

    /** The step along that dimension, {@code +1} or {@code -1}. */
    private int step;

    /** The number of dimensions of the world. */
    private int dims;

    /** The size of the world along the dimension the walk runs along. */
    private int axisSize;

    /**
     * Groups one organism's cells into scan lines along its direction vector and resolves the
     * extent of each.
     * <p> Everything a previous build recorded is dropped first. A direction vector without a
     * non-zero component names no direction and leaves no scan line, and so does an organism that
     * owns no cell.
     *
     * @param env The environment holding the cells and their owners.
     * @param organismId The organism whose cells are grouped.
     * @param dv The organism's direction vector, a unit vector along one dimension.
     * @param initialPosition The position the organism started at, counted into the extent of its
     *                        scan line where the organism owns cells on that scan line; not
     *                        modified.
     */
    public void build(Environment env, int organismId, int[] dv, int[] initialPosition) {
        scanLineByKey.clear();
        labels.clear();
        scanLineCount = 0;
        initialFlatIndex = -1;
        initialScanLine = -1;
        this.organismId = organismId;

        EnvironmentProperties props = env.getProperties();
        dims = props.getDimensions();
        dvDim = -1;
        for (int i = 0; i < dims; i++) {
            if (dv[i] != 0) {
                dvDim = i;
                step = dv[i] > 0 ? 1 : -1;
                break;
            }
        }
        if (dvDim == -1) {
            return;
        }
        axisSize = props.getDimensionSize(dvDim);
        if (coordBuffer == null || coordBuffer.length != dims) {
            coordBuffer = new int[dims];
            perpStrides = new int[dims];
        }
        computePerpStrides(props);

        groupOwnedCells(env, props);
        if (scanLineCount == 0) {
            return;
        }
        initialFlatIndex = props.toFlatIndex(initialPosition);
        addInitialPosition(initialPosition);
        resolveArcs(env, props, props.isToroidal());
    }

    /**
     * Reports the cell the organism started at.
     *
     * @return The flat index of the initial position, or {@code -1} if the build left no scan line.
     */
    public int initialFlatIndex() {
        return initialFlatIndex;
    }

    /**
     * Reports the organism the scan lines were built for.
     *
     * @return The organism's id.
     */
    public int organismId() {
        return organismId;
    }

    /**
     * Reports the dimension the direction vector points along.
     *
     * @return The dimension, or {@code -1} if the direction vector named no direction.
     */
    public int dvDim() {
        return dvDim;
    }

    /**
     * Reports the step along the direction vector's dimension.
     *
     * @return {@code +1} or {@code -1}; meaningless where {@link #dvDim()} is {@code -1}.
     */
    public int step() {
        return step;
    }

    /**
     * Reports the size of the world along the direction vector's dimension.
     *
     * @return The number of cells along that axis; meaningless where {@link #dvDim()} is
     *         {@code -1}.
     */
    public int axisSize() {
        return axisSize;
    }

    /**
     * Reports how many scan lines the organism occupies.
     *
     * @return The number of scan lines, numbered from zero in the order their first cell was met.
     */
    public int scanLineCount() {
        return scanLineCount;
    }

    /**
     * Reports where a scan line's extent begins, in the direction of rising coordinates.
     *
     * @param line The scan line's number.
     * @return The first coordinate of the arc along the direction vector's dimension.
     */
    public int arcStart(int line) {
        return arcStart[line];
    }

    /**
     * Reports where a scan line's extent ends, in the direction of rising coordinates.
     *
     * @param line The scan line's number.
     * @return The last coordinate of the arc; smaller than {@link #arcStart} if the arc crosses
     *         the world edge.
     */
    public int arcEnd(int line) {
        return arcEnd[line];
    }

    /**
     * Counts the cells of a scan line's extent.
     *
     * @param line The scan line's number.
     * @return The number of cells from the first to the last coordinate of the arc.
     */
    public int arcLength(int line) {
        return arcEnd[line] >= arcStart[line]
                ? arcEnd[line] - arcStart[line] + 1
                : axisSize - arcStart[line] + arcEnd[line] + 1;
    }

    /**
     * Reports one cell the organism owns on a scan line, from which the scan line's coordinates in
     * every other dimension can be read.
     *
     * @param line The scan line's number.
     * @return The flat index of the first owned cell met on the scan line.
     */
    public int sampleFlatIndex(int line) {
        return sampleFlatIndex[line];
    }

    /**
     * Finds the scan line a cell lies on.
     *
     * @param cell The coordinates of the cell; not modified.
     * @return The scan line's number, or {@code -1} if the organism owns no cell on that scan line.
     */
    public int scanLineOf(int[] cell) {
        if (dvDim == -1 || cell.length != dims) {
            return -1;
        }
        return scanLineByKey.getOrDefault(perpKey(cell), -1);
    }

    /**
     * Counts the cells of the organism's extent on a cell's scan line that lie before the cell,
     * against the direction vector.
     *
     * @param cell The coordinates of the cell; not modified.
     * @return The number of cells before it, or {@code -1} if the cell lies outside the extent.
     */
    public int cellsBefore(int[] cell) {
        int line = scanLineHolding(cell);
        if (line == -1) {
            return -1;
        }
        int offsetInArc = forwardDistance(arcStart[line], cell[dvDim]);
        return step > 0 ? offsetInArc : arcLength(line) - 1 - offsetInArc;
    }

    /**
     * Counts the cells of the organism's extent on a cell's scan line from the cell to the end the
     * direction vector points to, the cell included.
     *
     * @param cell The coordinates of the cell; not modified.
     * @return The number of cells from it to the end of the extent, or {@code -1} if the cell lies
     *         outside the extent.
     */
    public int cellsFrom(int[] cell) {
        int line = scanLineHolding(cell);
        if (line == -1) {
            return -1;
        }
        int offsetInArc = forwardDistance(arcStart[line], cell[dvDim]);
        return step > 0 ? arcLength(line) - offsetInArc : offsetInArc + 1;
    }

    /**
     * Reports how many LABEL cells the organism owns.
     *
     * @return The number of its LABEL cells.
     */
    public int labelCount() {
        return labels.size();
    }

    /**
     * Reports one of the organism's LABEL cells.
     *
     * @param index The position in the list, from zero in flat-index order.
     * @return The flat index of the LABEL cell.
     */
    public int labelFlatIndex(int index) {
        return labels.getInt(index);
    }

    /**
     * Finds the scan line whose extent holds a cell.
     *
     * @param cell The coordinates of the cell; not modified.
     * @return The scan line's number, or {@code -1} if the cell lies outside every extent.
     */
    private int scanLineHolding(int[] cell) {
        int line = scanLineOf(cell);
        if (line == -1 || forwardDistance(arcStart[line], cell[dvDim]) >= arcLength(line)) {
            return -1;
        }
        return line;
    }

    /**
     * Groups the organism's cells into scan lines and collects the LABEL cells among them.
     *
     * @param env The environment holding the cells and their owners.
     * @param props The properties flat indices are computed with.
     */
    private void groupOwnedCells(Environment env, EnvironmentProperties props) {
        env.visitCellsOwnedBy(organismId, cell -> {
            System.arraycopy(cell.coordinate(), 0, coordBuffer, 0, dims);
            int key = perpKey(coordBuffer);
            int dvCoord = coordBuffer[dvDim];
            int line = scanLineByKey.getOrDefault(key, -1);
            if (line == -1) {
                line = addScanLine(dvCoord, props.toFlatIndex(coordBuffer));
                scanLineByKey.put(key, line);
            } else {
                if (dvCoord < minDv[line]) minDv[line] = dvCoord;
                if (dvCoord > maxDv[line]) maxDv[line] = dvCoord;
                count[line]++;
            }
            if ((cell.moleculeInt() & Config.TYPE_MASK) == Config.TYPE_LABEL) {
                labels.add(props.toFlatIndex(coordBuffer));
            }
        });
    }

    /**
     * Counts the initial position among the coordinates of its scan line, if the organism owns
     * cells on that scan line.
     *
     * @param initialPosition The position the organism started at; not modified.
     */
    private void addInitialPosition(int[] initialPosition) {
        int line = scanLineOf(initialPosition);
        if (line == -1) {
            return;
        }
        initialScanLine = line;
        int dvCoord = initialPosition[dvDim];
        if (dvCoord < minDv[line]) minDv[line] = dvCoord;
        if (dvCoord > maxDv[line]) maxDv[line] = dvCoord;
        count[line]++;
    }

    /**
     * Opens a scan line at the first cell met on it.
     *
     * @param dvCoord The cell's coordinate along the walk's dimension.
     * @param flatIndex The cell's flat index.
     * @return The new scan line's number.
     */
    private int addScanLine(int dvCoord, int flatIndex) {
        if (scanLineCount == minDv.length) {
            int capacity = Math.max(16, scanLineCount * 2);
            minDv = Arrays.copyOf(minDv, capacity);
            maxDv = Arrays.copyOf(maxDv, capacity);
            count = Arrays.copyOf(count, capacity);
            sampleFlatIndex = Arrays.copyOf(sampleFlatIndex, capacity);
            arcStart = Arrays.copyOf(arcStart, capacity);
            arcEnd = Arrays.copyOf(arcEnd, capacity);
            segmentStart = Arrays.copyOf(segmentStart, capacity);
            segmentFill = Arrays.copyOf(segmentFill, capacity);
        }
        int line = scanLineCount++;
        minDv[line] = dvCoord;
        maxDv[line] = dvCoord;
        count[line] = 1;
        sampleFlatIndex[line] = flatIndex;
        return line;
    }

    /**
     * Determines the extent of each scan line.
     * <p> A scan line whose owned cells cannot reach around the world edge spans the arc from its
     * smallest to its largest coordinate, which the grouping pass already knows. Only a scan line
     * that can reach around it needs the coordinates in between: for those the coordinates are
     * collected, the initial position among them, sorted and handed to {@link ScanLineArc}, which
     * decides where the body ends and the outside begins.
     *
     * @param env The environment holding the cells and their owners.
     * @param props The properties flat indices are converted with.
     * @param toroidal Whether the world wraps around at the ends of the walk's dimension.
     */
    private void resolveArcs(Environment env, EnvironmentProperties props, boolean toroidal) {
        boolean anyWrapping = false;
        for (int line = 0; line < scanLineCount; line++) {
            arcStart[line] = minDv[line];
            arcEnd[line] = maxDv[line];
            if (ScanLineArc.largestGapRuleApplies(minDv[line], maxDv[line], axisSize, toroidal)) {
                anyWrapping = true;
            }
        }
        if (!anyWrapping) {
            return;
        }

        int total = 0;
        for (int line = 0; line < scanLineCount; line++) {
            segmentStart[line] = total;
            segmentFill[line] = 0;
            total += count[line];
        }
        if (dvCoordCollector == null || dvCoordCollector.length < total) {
            dvCoordCollector = new int[total];
        }
        env.visitCellsOwnedBy(organismId, cell -> {
            System.arraycopy(cell.coordinate(), 0, coordBuffer, 0, dims);
            int line = scanLineByKey.get(perpKey(coordBuffer));
            dvCoordCollector[segmentStart[line] + segmentFill[line]++] = coordBuffer[dvDim];
        });
        if (initialScanLine != -1) {
            props.flatIndexToCoordinates(initialFlatIndex, coordBuffer);
            dvCoordCollector[segmentStart[initialScanLine] + segmentFill[initialScanLine]++] = coordBuffer[dvDim];
        }

        for (int line = 0; line < scanLineCount; line++) {
            if (!ScanLineArc.largestGapRuleApplies(minDv[line], maxDv[line], axisSize, toroidal)) {
                continue;
            }
            Arrays.sort(dvCoordCollector, segmentStart[line], segmentStart[line] + count[line]);
            ScanLineArc.resolve(dvCoordCollector, segmentStart[line], count[line], axisSize, toroidal, arc);
            arcStart[line] = arc.start;
            arcEnd[line] = arc.end;
        }
    }

    /**
     * Counts the cells from one coordinate to another in the direction of rising coordinates.
     *
     * @param from The coordinate counted from.
     * @param to The coordinate counted to.
     * @return The number of steps from {@code from} to {@code to}, around the world edge if
     *         {@code to} lies before {@code from}.
     */
    private int forwardDistance(int from, int to) {
        int distance = to - from;
        return distance < 0 ? distance + axisSize : distance;
    }

    /**
     * Computes the key of the scan line a coordinate lies on, which is its coordinates in every
     * dimension but the one the walk runs along.
     *
     * @param coord The cell's coordinates.
     * @return The key of the cell's scan line.
     */
    private int perpKey(int[] coord) {
        int key = 0;
        for (int i = 0; i < coord.length; i++) {
            key += coord[i] * perpStrides[i];
        }
        return key;
    }

    /**
     * Computes the strides of the scan line key, which leave the walk's own dimension out.
     *
     * @param props The properties holding the world's shape.
     */
    private void computePerpStrides(EnvironmentProperties props) {
        int stride = 1;
        for (int i = dims - 1; i >= 0; i--) {
            if (i == dvDim) {
                perpStrides[i] = 0;
            } else {
                perpStrides[i] = stride;
                stride *= props.getDimensionSize(i);
            }
        }
    }
}
