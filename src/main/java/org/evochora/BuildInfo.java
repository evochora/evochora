package org.evochora;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the build recorded about itself: the git revision of the sources it was made from, and the
 * version it was built as.
 * <p>
 * The build writes {@code evochora-build.properties} onto the classpath with the key
 * {@code revision}, the commit hash suffixed with {@link #DIRTY_SUFFIX} when the working tree
 * carried uncommitted changes. A build made outside a git checkout writes the revision that a
 * {@code GIT_REVISION} file beside its sources names, suffixed with {@link #REBUILT_SUFFIX}, and
 * {@link #UNKNOWN} where there is no such file, as in a source archive; so does a classpath
 * without the file. The key {@code version} holds the
 * release tag of a build made for a release and is empty for every other build, a nightly or one
 * made by hand: such a build is named by its revision alone. The key {@code doi} holds the DOI
 * under which that release is archived, and is empty where the release has none and for every
 * build without a version. The key
 * {@code requiredJava} holds the Java version the code is compiled for; the entry point
 * {@code JavaVersionGuard} checks it against the running Java, and this class does not read it.
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

    /**
     * Appended to the commit hash by a build made without a git checkout from sources shipped with
     * their revision: such a build cannot tell whether the sources are still those of the commit.
     */
    public static final String REBUILT_SUFFIX = "-rebuilt";

    /** Classpath location of the file the build writes. */
    static final String RESOURCE = "/evochora-build.properties";

    private static final Logger LOG = LoggerFactory.getLogger(BuildInfo.class);

    private static final Properties RECORDED = load();

    private static final String REVISION = revisionOf(RECORDED);

    private static final Optional<String> VERSION = versionOf(RECORDED);

    private static final Optional<String> DOI = doiOf(RECORDED);

    private BuildInfo() {
    }

    /**
     * Returns the revision of this build.
     *
     * @return the commit hash, with {@link #DIRTY_SUFFIX} appended when the tree was not clean or
     *         {@link #REBUILT_SUFFIX} when the build was made from shipped sources, or
     *         {@link #UNKNOWN}
     */
    public static String revision() {
        return REVISION;
    }

    /**
     * Returns the version of this build, which only a build made for a release has.
     *
     * @return the release tag, or empty for a build made for no release
     */
    public static Optional<String> version() {
        return VERSION;
    }

    /**
     * Returns the DOI under which the release this build was made for is archived.
     *
     * @return the DOI, or empty for a build made for no release or for a release without one
     */
    public static Optional<String> doi() {
        return DOI;
    }

    /**
     * Returns what this build answers when asked for its version.
     *
     * @return the lines of {@link #versionLines(Optional, String, Optional)} for this build
     */
    public static String[] versionLines() {
        return versionLines(VERSION, REVISION, DOI);
    }

    /**
     * Names a build to someone who asks for its version: in one line by its version and revision,
     * or by its revision alone when it has no version, and in a second line by its DOI when it
     * has one, so that the DOI can be read off without the rest.
     *
     * @param version  the version of the build, or empty for a build without one
     * @param revision the revision of the build
     * @param doi      the DOI of the build's release, or empty
     * @return for instance {@code Evochora v1.0.0 (9c80bde4)} and {@code DOI 10.5281/zenodo.1234567},
     *         or {@code Evochora (revision 9c80bde4)} alone
     */
    static String[] versionLines(Optional<String> version, String revision, Optional<String> doi) {
        String build = version.map(tag -> "Evochora " + tag + " (" + revision + ")")
            .orElse("Evochora (revision " + revision + ")");
        return doi.map(name -> new String[] {build, "DOI " + name}).orElse(new String[] {build});
    }

    /**
     * Names a build in a sentence: by its version and revision when it has a version, by its
     * revision alone when it has none.
     *
     * @param version  the version of the build, or empty or {@code null} for a build without one
     * @param revision the revision of the build
     * @return for instance {@code Evochora v1.0.0 (9c80bde4)} or {@code a build of revision 9c80bde4}
     */
    public static String describe(String version, String revision) {
        if (version != null && !version.isBlank()) {
            return "Evochora " + version + " (" + revision + ")";
        }
        return UNKNOWN.equals(revision) ? "a build of unknown revision" : "a build of revision " + revision;
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

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream stream = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (stream != null) {
                properties.load(stream);
            }
        } catch (IOException e) {
            LOG.warn("Cannot read {}, so runs are written without a build revision: {}", RESOURCE, e.getMessage());
            properties.clear();
        }
        return properties;
    }

    /**
     * Reads the revision from the properties the build wrote.
     *
     * @param properties the contents of {@value #RESOURCE}
     * @return the revision, or {@link #UNKNOWN} when the key is missing or blank
     */
    static String revisionOf(Properties properties) {
        return recorded(properties, "revision");
    }

    /**
     * Reads the version from the properties the build wrote.
     *
     * @param properties the contents of {@value #RESOURCE}
     * @return the version, or empty when the key is missing or blank
     */
    static Optional<String> versionOf(Properties properties) {
        return optional(properties, "version");
    }

    /**
     * Reads the DOI from the properties the build wrote.
     *
     * @param properties the contents of {@value #RESOURCE}
     * @return the DOI, or empty when the key is missing or blank
     */
    static Optional<String> doiOf(Properties properties) {
        return optional(properties, "doi");
    }

    private static Optional<String> optional(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }

    private static String recorded(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? UNKNOWN : value.strip();
    }
}
