package org.embulk.cli;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;

class HomeDirectories {
    private HomeDirectories(
            final Path embulkHome,
            final Path m2Repo,
            final Path gemHome,
            final List<Path> gemPath,
            final Properties mergedProperties,
            final List<Log> logs) {
        this.embulkHome = embulkHome;
        this.m2Repo = m2Repo;
        this.gemHome = gemHome;
        this.gemPath = gemPath;
        this.mergedProperties = mergedProperties;
        this.logs = logs;
    }

    static HomeDirectories find(
            final Properties commandLineProperties,
            final Properties javaSystemProperties,
            final Map<String, String> env) throws IOException {
        return new Builder(commandLineProperties, javaSystemProperties, env).build();
    }

    static HomeDirectories find(final Properties commandLineProperties) throws IOException {
        return find(commandLineProperties, System.getProperties(), System.getenv());
    }

    Path getEmbulkHome() {
        return this.embulkHome;
    }

    Path getM2Repo() {
        return this.m2Repo;
    }

    Path getGemHome() {
        return this.gemHome;
    }

    List<Path> getGemPath() {
        return this.gemPath;
    }

    Properties getMergedProperties() {
        return this.mergedProperties;
    }

    void logSavedLogs(final Logger logger) {
        for (final Log log : this.logs) {
            log.logSavedLog(logger);
        }
    }

    static class Builder {
        Builder(final Properties commandLineProperties, final Properties javaSystemProperties, final Map<String, String> env) {
            this.commandLineProperties = commandLineProperties;
            this.javaSystemProperties = javaSystemProperties;
            this.env = env;
            this.logs = new ArrayList<>();

            this.userHomeOrEx = getUserHome(javaSystemProperties, this.logs);
            this.userDirOrEx = getUserDir(javaSystemProperties, this.logs);
        }

        HomeDirectories build() throws IOException {
            final Path embulkHome = findEmbulkHome();
            final Optional<Properties> embulkPropertiesFromFile = loadEmbulkPropertiesFromFile(embulkHome);
            final Path m2Repo = findM2Repo(embulkHome, embulkPropertiesFromFile);
            final Path gemHome = findGemHome(embulkHome, embulkPropertiesFromFile);
            final List<Path> gemPath = null;

            // Properties from the command-line is prioritized, but @@@
            final Properties mergedProperties = new Properties();

            return new HomeDirectories(embulkHome, m2Repo, gemHome, gemPath, mergedProperties, this.logs);
        }

        static PathOrException getUserHome(final Properties javaSystemProperties, final ArrayList<Log> logs) {
            return normalizePathInJavaSystemProperty("user.home", javaSystemProperties, logs);
        }

        static PathOrException getUserDir(final Properties javaSystemProperties, final ArrayList<Log> logs) {
            return normalizePathInJavaSystemProperty("user.dir", javaSystemProperties, logs);
        }

        /**
         * Finds an appropriate "embulk_home" directory based on a rule defined.
         *
         * <ul>
         * <li>1) If a system config {@code "embulk_home"} is set from the command line, it is the most prioritized.
         * <li>2) If an environment variable {@code "EMBULK_HOME"} is set, it is the second prioritized.
         * <li>3) If neither (1) nor (2) is set, it iterates up over parent directories from "user.dir" for a directory that:
         *   <ul>
         *   <li>is named ".embulk",
         *   <li>has "embulk.properties" just under itself.
         *   </ul>
         *   <ul>
         *   <li>3-1) If "user.dir" (almost equal to the working directory) is under "user.home", it iterates up till "user.home".
         *   <li>3-2) If "user.dir" is not under "user.home", Embulk iterates until the root directory.
         *   </ul>
         * </li>
         * <li>4) If none of the above does not work, use the traditional predefined directory "~/.embulk".
         * </ul>
         */
        Path findEmbulkHome() {
            // 1) If a system config "embulk_home" is set from the command line, it is the most prioritized.
            final Optional<Path> ofCommandLine = normalizePathInCommandLineProperties("embulk_home");
            if (ofCommandLine.isPresent()) {
                return ofCommandLine.get();
            }

            // 2) If an environment variable "EMBULK_HOME" is set, it is the second prioritized.
            final Optional<Path> ofEnv = normalizePathInEnv("EMBULK_HOME");
            if (ofEnv.isPresent()) {
                return ofEnv.get();
            }

            // (3) and (4) depend on "user.home" and "user.dir". Exception if they are unavailable.
            final Path userHome = userHomeOrEx.orRethrow();
            final Path userDir = userDirOrEx.orRethrow();

            final Path iterateUpTill;
            if (isUnderHome()) {
                // 3-1) If "user.dir" (almost equal to the working directory) is under "user.home", it iterates up till "user.home".
                iterateUpTill = userHome;
            } else {
                // 3-2) If "user.dir" is not under "user.home", it iterates up till the root directory.
                iterateUpTill = userDir.getRoot();
            }

            // 3) If neither (1) nor (2) is set, it iterates up over parent directories from "user.dir" for a directory that:
            //   * is named ".embulk",
            //   * has a readable file "embulk.properties" just under itself.
            if (iterateUpTill != null) {
                for (Path pwd = userDir; pwd != null && pwd.startsWith(iterateUpTill); pwd = pwd.getParent()) {
                    // When checking the actual file/directory, symbolic links are resolved.

                    final Path dotEmbulk;
                    try {
                        dotEmbulk = pwd.resolve(".embulk");
                        if (Files.notExists(dotEmbulk) || (!Files.isDirectory(dotEmbulk))) {
                            continue;
                        }
                    } catch (final RuntimeException ex) {
                        debug("Failed to check for \".embulk\" at: " + pwd.toString(), ex);
                        continue;
                    }

                    try {
                        final Path properties = dotEmbulk.resolve("embulk.properties");
                        if (Files.notExists(properties) || (!Files.isRegularFile(properties)) || (!Files.isReadable(properties))) {
                            continue;
                        }
                    } catch (final RuntimeException ex) {
                        debug("Failed to check for \"embulk.properties\" at: " + pwd.toString(), ex);
                        continue;
                    }

                    return pwd;
                }
            }

            // 4) If none of the above does not work, use the traditional predefined directory "~/.embulk".
            return userHome.resolve(".embulk");
        }

        Optional<Properties> loadEmbulkPropertiesFromFile(final Path embulkHome) throws IOException {
            final Path path = embulkHome.resolve("embulk.properties");

            if (Files.notExists(path)) {
                info(path.toString() + " does not exist. Ignored.");
                return Optional.empty();
            }
            if (!Files.isRegularFile(path)) {
                info(path.toString() + " exists, but not a regular file. Ignored.");
                return Optional.empty();
            }
            if (!Files.isReadable(path)) {
                info(path.toString() + " exists, but not readable. Ignored.");
                return Optional.empty();
            }

            final Properties properties = new Properties();
            try (final InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
                properties.load(input);
            } catch (final IOException ex) {
                error(path.toString() + " exists, but failed to load.", ex);
                throw new IOException(path.toString() + " exists, but failed to load.", ex);
            }
            return Optional.of(properties);
        }

        Path findM2Repo(final Path embulkHome, final Optional<Properties> embulkPropertiesFromFile) {
            return findSubdirectory(embulkHome, embulkPropertiesFromFile, "m2_repo", "M2_REPO", M2_REPO_RELATIVE);
        }

        Path findGemHome(final Path embulkHome, final Optional<Properties> embulkPropertiesFromFile) {
            return findSubdirectory(embulkHome, embulkPropertiesFromFile, "gem_home", "GEM_HOME", GEM_HOME_RELATIVE);
        }

        /*
        List<Path> findGemPath(final Path embulkHome, final Optional<Properties> embulkPropertiesFromFile) {
            return findSubdirectory(embulkHome, embulkPropertiesFromFile, true, "gem_path", "GEM_PATH", GEM_HOME_RELATIVE);
        }
        */

        private Path findSubdirectory(
                final Path embulkHome,
                final Optional<Properties> embulkPropertiesFromFile,
                final String propertyName,
                final String envName,
                final Path subPath) {
            // 1) If a system config <propertyName> is set from the command line, it is the most prioritized.
            final Optional<Path> ofCommandLine = normalizePathInCommandLineProperties(propertyName);
            if (ofCommandLine.isPresent()) {
                return ofCommandLine.get();
            }

            // 2) If a system config <propertyName> is set from "embulk.properties", it is the second prioritized.
            final Optional<Path> ofEmbulkPropertiesFile =
                    normalizePathInEmbulkPropertiesFile(propertyName, embulkPropertiesFromFile, embulkHome);
            if (ofEmbulkPropertiesFile.isPresent()) {
                return ofEmbulkPropertiesFile.get();
            }

            // 3) If an environment variable <envName> is set, it is the third prioritized.
            final Optional<Path> ofEnv = normalizePathInEnv(envName);
            if (ofEnv.isPresent()) {
                return ofEnv.get();
            }

            // 4) If none of the above does not match, use the specific sub directory of "embulk_home".
            return embulkHome.resolve(subPath);
        }

        private List<Path> findSubdirectories(
                final Path embulkHome,
                final Optional<Properties> embulkPropertiesFromFile,
                final String propertyName,
                final String envName,
                final Path subPath) {
            // 1) If a system config <propertyName> is set from the command line, it is the most prioritized.
            final Optional<Path> ofCommandLine = normalizePathInCommandLineProperties(propertyName);
            if (ofCommandLine.isPresent()) {
                return ofCommandLine.get();
            }

            // 2) If a system config <propertyName> is set from "embulk.properties", it is the second prioritized.
            final Optional<Path> ofEmbulkPropertiesFile =
                    normalizePathInEmbulkPropertiesFile(propertyName, embulkPropertiesFromFile, embulkHome);
            if (ofEmbulkPropertiesFile.isPresent()) {
                return ofEmbulkPropertiesFile.get();
            }

            // 3) If an environment variable <envName> is set, it is the third prioritized.
            final Optional<Path> ofEnv = normalizePathInEnv(envName);
            if (ofEnv.isPresent()) {
                return ofEnv.get();
            }

            // 4) If none of the above does not match, use the specific sub directory of "embulk_home".
            return embulkHome.resolve(subPath);
        }

        /**
         * Returns a normalized path in a specified Java system property "user.home" or "user.dir".
         *
         * <p>Note that a path in a Java system property should be an absolute path.
         */
        static PathOrException normalizePathInJavaSystemProperty(
                final String propertyName, final Properties javaSystemProperties, final ArrayList<Log> logs) {
            final String property = javaSystemProperties.getProperty(propertyName);

            if (property == null || property.isEmpty()) {
                final String message = "Java system property \"" + propertyName + "\" is unexpectedly unset.";
                final IllegalArgumentException ex = new IllegalArgumentException(message);
                error(logs, message, ex);
                return new PathOrException(ex);
            }

            final Path path;
            try {
                path = Paths.get(property);
            } catch (final InvalidPathException ex) {
                error(logs, "Java system property \"" + propertyName + "\" is unexpectedly invalid: \"" + property + "\"", ex);
                return new PathOrException(ex);
            }

            if (!path.isAbsolute()) {
                final String message = "Java system property \"" + propertyName + "\" is unexpectedly not absolute.";
                final IllegalArgumentException ex = new IllegalArgumentException(message);
                error(logs, message, ex);
                return new PathOrException(ex);
            }

            final Path normalized = path.normalize();
            if (!normalized.equals(path)) {
                warn(logs, "Java system property \"" + propertyName + "\" is unexpectedly not normalized: \"" + property + "\", "
                        + "then resolved to: \"" + normalized.toString() + "\"");
            }

            // Symbolic links are intentionally NOT resolved with Path#toRealPath.
            return new PathOrException(normalized);
        }

        private Optional<Path> normalizePathInCommandLineProperties(final String propertyName) {
            final List<Path> paths = normalizePathsInCommandLineProperties(propertyName, false);
            if (paths.size() > 1) {
                throw new IllegalStateException("Multiple paths returned for an unsplit path.");
            }

            if (paths.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(paths.get(0));
        }

        /**
         * Returns normalized paths in a specified property from the command line.
         *
         * <p>Note that a path in the command line should be an absolute path, or a relative path from the working directory.
         */
        private List<Path> normalizePathsInCommandLineProperties(final String propertyName, final boolean multi) {
            if (!this.commandLineProperties.containsKey(propertyName)) {
                return Collections.unmodifiableList(new ArrayList<Path>());
            }
            final String property = this.commandLineProperties.getProperty(propertyName);
            if (property == null || property.isEmpty()) {
                return Collections.unmodifiableList(new ArrayList<Path>());
            }

            final List<String> pathStrings = splitPathStrings(property, multi);
            final ArrayList<Path> paths = new ArrayList<>();
            for (final String pathString : pathStrings) {
                if (pathString.isEmpty()) {
                    continue;
                }

                final Path path;
                try {
                    path = Paths.get(pathString);
                } catch (final InvalidPathException ex) {
                    error("Embulk system property \"" + propertyName + "\" in command-line is invalid: \"" + pathString + "\"", ex);
                    throw ex;
                }

                final Path absolute;
                if (path.isAbsolute()) {
                    absolute = path;
                } else {
                    absolute = path.toAbsolutePath();
                }

                final Path normalized = absolute.normalize();
                if (!normalized.equals(path)) {
                    warn("Embulk system property \"" + propertyName + "\" in command-line is not normalized: "
                            + "\"" + pathString + "\", " + "then resolved to: \"" + normalized.toString() + "\"");
                }

                // Symbolic links are intentionally NOT resolved with Path#toRealPath.
                paths.add(normalized);
            }

            return Collections.unmodifiableList(paths);
        }

        private Optional<Path> normalizePathInEnv(final String envName) {
            final List<Path> paths = normalizePathsInEnv(envName, false);
            if (paths.size() > 1) {
                throw new IllegalStateException("Multiple paths returned for an unsplit path.");
            }

            if (paths.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(paths.get(0));
        }

        /**
         * Returns normalized paths in an environment variable.
         *
         * <p>Note that a path in an environment variable should be an absolute path.
         */
        private List<Path> normalizePathsInEnv(final String envName, final boolean multi) {
            if (!this.env.containsKey(envName)) {
                return Collections.unmodifiableList(new ArrayList<Path>());
            }
            final String value = this.env.get(envName);
            if (value == null || value.isEmpty()) {
                return Collections.unmodifiableList(new ArrayList<Path>());
            }

            final List<String> pathStrings = splitPathStrings(value, multi);
            final ArrayList<Path> paths = new ArrayList<>();
            for (final String pathString : pathStrings) {
                if (pathString.isEmpty()) {
                    continue;
                }

                final Path path;
                try {
                    path = Paths.get(pathString);
                } catch (final InvalidPathException ex) {
                    error("Environment variable \"" + envName + "\" is invalid: \"" + pathString + "\"", ex);
                    throw ex;
                }

                if (!path.isAbsolute()) {
                    final String message = "Environment variable \"" + envName + "\" is not absolute.";
                    final IllegalArgumentException ex = new IllegalArgumentException(message);
                    error(message, ex);
                    throw ex;
                }

                final Path normalized = path.normalize();
                if (!normalized.equals(path)) {
                    warn("Environment variable \"" + envName + "\" is not normalized: "
                            + "\"" + pathString + "\", " + "then resolved to: \"" + normalized.toString() + "\"");
                }

                // Symbolic links are intentionally NOT resolved with Path#toRealPath.
                paths.add(normalized);
            }

            return Collections.unmodifiableList(paths);
        }

        private Optional<Path> normalizePathInEmbulkPropertiesFile(
                final String propertyName,
                final Optional<Properties> embulkPropertiesFromFileOptional,
                final Path embulkHome) {
            final List<Path> paths = normalizePathsInEmbulkPropertiesFile(
                    propertyName, embulkPropertiesFromFileOptional, embulkHome, false);
            if (paths.size() > 1) {
                throw new IllegalStateException("Multiple paths returned for an unsplit path.");
            }

            if (paths.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(paths.get(0));
        }

        /**
         * Returns normalized paths from the "embulk.properties" file.
         *
         * <p>Note that a path in the "embulk.properties" file should be an absolute path, or a relative path from "embulk_home".
         */
        private List<Path> normalizePathsInEmbulkPropertiesFile(
                final String propertyName,
                final Optional<Properties> embulkPropertiesFromFileOptional,
                final Path embulkHome,
                final boolean multi) {
            if (!embulkPropertiesFromFileOptional.isPresent()) {
                return Collections.unmodifiableList(new ArrayList<Path>());
            }
            final Properties embulkPropertiesFromFile = embulkPropertiesFromFileOptional.get();
            if (!embulkPropertiesFromFile.containsKey(propertyName)) {
                return Collections.unmodifiableList(new ArrayList<Path>());
            }
            final String property = embulkPropertiesFromFile.getProperty(propertyName);
            if (property == null || property.isEmpty()) {
                return Collections.unmodifiableList(new ArrayList<Path>());
            }

            final List<String> pathStrings = splitPathStrings(property, multi);
            final ArrayList<Path> paths = new ArrayList<>();
            for (final String pathString : pathStrings) {
                if (pathString.isEmpty()) {
                    continue;
                }

                final Path path;
                try {
                    path = Paths.get(pathString);
                } catch (final InvalidPathException ex) {
                    error("Embulk system property \"" + propertyName + "\" in embulk.properties is invalid: \"" + pathString + "\"",
                          ex);
                    throw ex;
                }

                final Path absolute;
                if (path.isAbsolute()) {
                    absolute = path;
                } else {
                    absolute = embulkHome.resolve(path);
                }

                final Path normalized = absolute.normalize();
                if (!normalized.equals(path)) {
                    warn("Embulk system property \"" + propertyName + "\" in embulk.properties is not normalized: "
                            + "\"" + pathString + "\", " + "then resolved to: \"" + normalized.toString() + "\"");
                }

                // Symbolic links are intentionally NOT resolved with Path#toRealPath.
                paths.add(normalized);
            }

            return Collections.unmodifiableList(paths);
        }

        private List<String> splitPathStrings(final String pathStrings, final boolean multi) {
            final ArrayList<String> split = new ArrayList<>();
            if (multi) {
                for (final String pathString : pathStrings.split(File.pathSeparator)) {
                    split.add(pathString);
                }
            } else {
                split.add(pathStrings);
            }
            return Collections.unmodifiableList(split);
        }

        /**
         * Returns {@code true} if {@code userDir} is under {@code userHome}.
         *
         * <p>Note that the check is performed "literally". It does not take care of the existence of the path.
         * It does not resolve a symbolic link.
         */
        private boolean isUnderHome() {
            return this.userDirOrEx.orRethrow().startsWith(this.userHomeOrEx.orRethrow());
        }

        private void debug(final String message, final Throwable exception) {
            this.logs.add(new Log(LogLevel.DEBUG, message, exception));
        }

        private void debug(final String message) {
            this.logs.add(new Log(LogLevel.DEBUG, message, null));
        }

        private static void error(final ArrayList<Log> logs, final String message, final Throwable exception) {
            logs.add(new Log(LogLevel.ERROR, message, exception));
        }

        private void error(final String message, final Throwable exception) {
            this.logs.add(new Log(LogLevel.ERROR, message, exception));
        }

        private void error(final String message) {
            this.logs.add(new Log(LogLevel.ERROR, message, null));
        }

        private void info(final String message, final Throwable exception) {
            this.logs.add(new Log(LogLevel.INFO, message, exception));
        }

        private void info(final String message) {
            this.logs.add(new Log(LogLevel.INFO, message, null));
        }

        private void trace(final String message, final Throwable exception) {
            this.logs.add(new Log(LogLevel.TRACE, message, exception));
        }

        private void trace(final String message) {
            this.logs.add(new Log(LogLevel.TRACE, message, null));
        }

        private static void warn(final ArrayList<Log> logs, final String message) {
            logs.add(new Log(LogLevel.WARN, message, null));
        }

        private void warn(final String message, final Throwable exception) {
            this.logs.add(new Log(LogLevel.WARN, message, exception));
        }

        private void warn(final String message) {
            this.logs.add(new Log(LogLevel.WARN, message, null));
        }

        private final Properties commandLineProperties;
        private final Properties javaSystemProperties;
        private final Map<String, String> env;
        private final ArrayList<Log> logs;
        private final PathOrException userHomeOrEx;
        private final PathOrException userDirOrEx;
    }

    private static class PathOrException {
        PathOrException(final Path path) {
            if (path == null) {
                this.path = null;
                this.exception = new NullPointerException("Path is null.");
            } else {
                this.path = path;
                this.exception = null;
            }
        }

        PathOrException(final RuntimeException exception) {
            this.path = null;
            this.exception = exception;
        }

        Path orRethrow() {
            if (this.path == null) {
                throw this.exception;
            }
            return this.path;
        }

        private final Path path;
        private final RuntimeException exception;
    }

    private static class Log {
        Log(final LogLevel loglevel, final String message, final Throwable exception) {
            if (loglevel == null) {
                throw new NullPointerException("Null loglevel.");
            }
            this.loglevel = loglevel;
            this.message = message;
            this.exception = exception;
        }

        void logSavedLog(final Logger logger) {
            switch (this.loglevel) {
                case DEBUG:
                    logger.debug(this.message, this.exception);
                    break;
                case ERROR:
                    logger.error(this.message, this.exception);
                    break;
                case INFO:
                    logger.info(this.message, this.exception);
                    break;
                case TRACE:
                    logger.trace(this.message, this.exception);
                    break;
                case WARN:
                    logger.warn(this.message, this.exception);
                    break;
                default:
                    throw new UnsupportedOperationException("Unknown loglevel: " + this.loglevel.toString());
            }
        }

        private final LogLevel loglevel;
        private final String message;
        private final Throwable exception;
    }

    private enum LogLevel {
        ERROR,
        WARN,
        INFO,
        DEBUG,
        TRACE,
        ;
    }

    private static final String EMBULK_HOME_PROP = "embulk_home";
    private static final String M2_REPO_PROP = "m2_repo";

    private static final Path M2_REPO_RELATIVE = Paths.get("lib").resolve("m2").resolve("repository");
    private static final Path GEM_HOME_RELATIVE = Paths.get("lib").resolve("gems");

    private final Path embulkHome;
    private final Path m2Repo;
    private final Path gemHome;
    private final List<Path> gemPath;
    private final Properties mergedProperties;
    private final List<Log> logs;
}
