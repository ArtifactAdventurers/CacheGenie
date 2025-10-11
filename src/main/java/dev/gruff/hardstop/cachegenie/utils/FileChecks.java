package dev.gruff.hardstop.cachegenie.utils;

import java.io.File;
import java.util.Locale;

/**
 * Small utility class with validation helpers for files.
 *
 * <p>All methods throw a RuntimeException with a descriptive message when the
 * supplied argument does not meet the required condition. This keeps behavior
 * consistent with the historical implementation.</p>
 */
public final class FileChecks {

    private FileChecks() {
        // utility class
    }

    /**
     * Verifies that the given File exists, is a regular file (not a directory),
     * and that its name ends with the given suffix (case-insensitive).
     *
     * <p>The suffix may be specified with or without a leading dot. Blank suffixes
     * are rejected.</p>
     *
     * @param f      the file to check; must not be null
     * @param suffix the expected file-name suffix/extension; e.g. ".pom" or "pom"
     */
    public static void checkFileExistsIsFileOfType(File f, String suffix) {
        if (f == null) error("file is null");
        if (suffix == null) error("no suffix type presented");

        if (!f.exists()) error("file " + f.getAbsolutePath() + " does not exist");
        if (!f.isFile()) error("file " + f.getAbsolutePath() + " is not a regular file");

        suffix = suffix.trim();
        if (suffix.isBlank()) error("no suffix type presented");
        if (!suffix.startsWith(".")) suffix = "." + suffix;
        suffix = suffix.toLowerCase(Locale.ROOT);

        String name = f.getName().toLowerCase(Locale.ROOT).trim();
        if (!name.endsWith(suffix)) error("file " + f.getAbsolutePath() + " is not of type " + suffix);
    }

    private static void error(String msg) {
        throw new RuntimeException(msg);
    }

    /**
     * Verifies that the given File reference is non-null and exists (file or directory).
     *
     * @param ref a human-readable reference name used in error messages
     * @param f   the file to test for existence
     */
    public static void checkFileExists(String ref, File f) {
        if (f == null) error(ref + " is null");
        if (!f.exists()) error(ref + " " + f.getAbsolutePath() + " does not exist");
    }
}
