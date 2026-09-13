package org.evochora;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * What the build recorded about itself: the git revision of the sources it was made from.
 * <p>
 * The build writes {@code evochora-build.properties} onto the classpath with the key
 * {@code revision}, the commit hash suffixed with {@link #DIRTY_SUFFIX} when the working tree
 * carried uncommitted changes. A build made outside a git checkout, from a source archive for instance,
 * writes {@link #UNKNOWN}; so does a classpath without the file.
 * <p>
 * The revision is written into the metadata of every run, because a run is reproduced exactly
 * only by the build that wrote it. Two revisions that are equal and not {@link #UNKNOWN} name
 * the same sources; anything else is grounds for a warning wherever a run is read.
 */
public final class BuildInfo {

    /** The revision recorded when the build did not know its sources. */
    public static final String UNKNOWN = "unknown";

    /** Appended to the commit hash when the working tree carried uncommitted changes. */
    public static final String DIRTY_SUFFIX = "-dirty";

    /** Classpath location of the file the build writes. */
    static final String RESOURCE = "/evochora-build.properties";

    private static final String REVISION = load();

    private BuildInfo() {
    }

    /**
     * Returns the revision of this build.
     *
     * @return the commit hash, with {@link #DIRTY_SUFFIX} appended when the tree was not clean, or
     *         {@link #UNKNOWN}
     */
    public static String revision() {
        return REVISION;
    }

    /**
     * Whether a run written by the given revision came from the same sources as this build.
     * <p>
     * An unknown revision on either side never matches: the sources cannot be compared.
     *
     * @param recorded the revision a run's metadata carries
     * @return true when both revisions are known and equal
     */
    public static boolean matches(String recorded) {
        return !UNKNOWN.equals(REVISION) && REVISION.equals(recorded);
    }

    private static String load() {
        try (InputStream stream = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return UNKNOWN;
            }
            Properties properties = new Properties();
            properties.load(stream);
            return revisionOf(properties);
        } catch (IOException e) {
            return UNKNOWN;
        }
    }

    /**
     * Reads the revision from the properties the build wrote.
     *
     * @param properties the contents of {@value #RESOURCE}
     * @return the revision, or {@link #UNKNOWN} when the key is missing or blank
     */
    static String revisionOf(Properties properties) {
        String revision = properties.getProperty("revision");
        return revision == null || revision.isBlank() ? UNKNOWN : revision.strip();
    }
}
