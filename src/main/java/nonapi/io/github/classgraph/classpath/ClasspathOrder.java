/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package nonapi.io.github.classgraph.classpath;

import java.io.File;
import java.io.IOError;
import java.lang.reflect.Array;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.classgraph.ClassGraph.ClasspathElementFilter;
import io.github.classgraph.ClassGraph.ClasspathElementURLFilter;
import nonapi.io.github.classgraph.classloaderhandler.ClassLoaderHandlerRegistry;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.JarUtils;
import nonapi.io.github.classgraph.utils.LogNode;

/** A class to find the unique ordered classpath elements. */
public class ClasspathOrder {
    /** The scan spec. */
    private final ScanSpec scanSpec;

    public ReflectionUtils reflectionUtils;

    /** Unique classpath entries. */
    private final Set<String> classpathEntryUniqueResolvedPaths = new HashSet<>();

    /** The classpath order. Keys are instances of {@link String} or {@link URL}. */
    private final List<ClasspathEntry> order = new ArrayList<>();

    /** Suffixes for automatic package roots, e.g. "!/BOOT-INF/classes". */
    private static final List<String> AUTOMATIC_PACKAGE_ROOT_SUFFIXES = new ArrayList<>();

    /** Match URL schemes (must consist of at least two chars, otherwise this is Windows drive letter). */
    private static final Pattern schemeMatcher = Pattern.compile("^[a-zA-Z][a-zA-Z+\\-.]+:");

    static {
        for (final String prefix : ClassLoaderHandlerRegistry.AUTOMATIC_PACKAGE_ROOT_PREFIXES) {
            AUTOMATIC_PACKAGE_ROOT_SUFFIXES.add("!/" + prefix.substring(0, prefix.length() - 1));
        }
    }

    /**
     * A classpath element and the {@link ClassLoader} it was obtained from.
     */
    public static class ClasspathEntry {
        /** The classpath entry object (a {@link String} path, {@link Path}, {@link URL} or {@link URI}). */
        public final Object classpathEntryObj;

        /** The classloader the classpath element was obtained from. */
        public final ClassLoader classLoader;

        /**
         * Constructor.
         *
         * @param classpathEntryObj
         *            the classpath entry object (a {@link String} or {@link URL} or {@link Path}).
         * @param classLoader
         *            the classloader the classpath element was obtained from.
         */
        public ClasspathEntry(final Object classpathEntryObj, final ClassLoader classLoader) {
            this.classpathEntryObj = classpathEntryObj;
            this.classLoader = classLoader;
        }

        @Override
        public int hashCode() {
            return Objects.hash(classpathEntryObj);
        }

        @Override
        public boolean equals(final Object obj) {
            if (obj == this) {
                return true;
            } else if (!(obj instanceof ClasspathEntry)) {
                return false;
            }
            final ClasspathEntry other = (ClasspathEntry) obj;
            return Objects.equals(this.classpathEntryObj, other.classpathEntryObj);
        }

        @Override
        public String toString() {
            return classpathEntryObj + " [" + classLoader + "]";
        }
    }

    /**
     * Constructor.
     *
     * @param scanSpec
     *            the scan spec
     */
    ClasspathOrder(final ScanSpec scanSpec, final ReflectionUtils reflectionUtils) {
        this.scanSpec = scanSpec;
        this.reflectionUtils = reflectionUtils;
    }

    /**
     * Get the order of classpath elements, uniquified and in order.
     *
     * @return the classpath order.
     */
    public List<ClasspathEntry> getOrder() {
        return order;
    }

    /**
     * Get the unique classpath entry strings.
     *
     * @return the classpath entry strings.
     */
    public Set<String> getClasspathEntryUniqueResolvedPaths() {
        return classpathEntryUniqueResolvedPaths;
    }

    /**
     * Test to see if a classpath element has been filtered out by the user.
     * 
     * @param classpathElementURL
     *            the classpath element URL
     * @param classpathElementPath
     *            the classpath element path
     * @return true, if not filtered out
     */
    private boolean filter(final URL classpathElementURL, final String classpathElementPath) {
        if (scanSpec.classpathElementFilters != null) {
            for (final Object filterObj : scanSpec.classpathElementFilters) {
                if ((classpathElementURL != null && filterObj instanceof ClasspathElementURLFilter
                        && !((ClasspathElementURLFilter) filterObj).includeClasspathElement(classpathElementURL))
                        || (classpathElementPath != null && filterObj instanceof ClasspathElementFilter
                                && !((ClasspathElementFilter) filterObj)
                                        .includeClasspathElement(classpathElementPath))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Add a system classpath entry.
     *
     * @param pathEntry
     *            the system classpath entry -- the path string should already have been run through
     *            FastPathResolver.resolve(FileUtils.currDirPath(), path)
     * @param classLoader
     *            the classloader
     * @return true, if added and unique
     */
    boolean addSystemClasspathEntry(final String pathEntry, final ClassLoader classLoader) {
        if (classpathEntryUniqueResolvedPaths.add(pathEntry)) {
            order.add(new ClasspathEntry(pathEntry, classLoader));
            return true;
        }
        return false;
    }

    /**
     * Add a classpath entry.
     *
     * @param pathElement
     *            the {@link String} path, {@link File}, {@link Path}, {@link URL} or {@link URI} of the classpath
     *            element.
     * @param pathElementStr
     *            the path element in string format
     * @param classLoader
     *            the classloader
     * @param scanSpec
     *            the scan spec
     * @return true, if added and unique
     */
    private boolean addClasspathEntry(final Object pathElement, final String pathElementStr,
            final ClassLoader classLoader, final ScanSpec scanSpec) {
        // Check if classpath element path ends with an automatic package root. If so, strip it off to
        // eliminate duplication, since automatic package roots are detected automatically (#435)
        String pathElementStrWithoutSuffix = pathElementStr;
        boolean hasSuffix = false;
        for (final String suffix : AUTOMATIC_PACKAGE_ROOT_SUFFIXES) {
            if (pathElementStr.endsWith(suffix)) {
                // Strip off automatic package root suffix
                pathElementStrWithoutSuffix = pathElementStr.substring(0,
                        pathElementStr.length() - suffix.length());
                hasSuffix = true;
                break;
            }
        }
        if (pathElement instanceof URL || pathElement instanceof URI || pathElement instanceof Path
                || pathElement instanceof File) {
            Object pathElementWithoutSuffix = pathElement;
            if (hasSuffix) {
                try {
                    pathElementWithoutSuffix = pathElement instanceof URL ? new URL(pathElementStrWithoutSuffix)
                            : pathElement instanceof URI ? new URI(pathElementStrWithoutSuffix)
                                    : pathElement instanceof Path ? Paths.get(pathElementStrWithoutSuffix)
                                            // For File, just use path string
                                            : pathElementStrWithoutSuffix;
                } catch (MalformedURLException | URISyntaxException | InvalidPathException e) {
                    try {
                        pathElementWithoutSuffix = pathElement instanceof URL
                                ? new URL("file:" + pathElementStrWithoutSuffix)
                                : pathElement instanceof URI ? new URI("file:" + pathElementStrWithoutSuffix)
                                        : pathElementStrWithoutSuffix;
                    } catch (MalformedURLException | URISyntaxException | InvalidPathException e2) {
                        return false;
                    }
                }
            }
            // Deduplicate classpath elements
            if (classpathEntryUniqueResolvedPaths.add(pathElementStrWithoutSuffix)) {
                // Record classpath element in classpath order
                order.add(new ClasspathEntry(pathElementWithoutSuffix, classLoader));
                return true;
            }
        } else {
            final String pathElementStrResolved = FastPathResolver.resolve(FileUtils.currDirPath(),
                    pathElementStrWithoutSuffix);
            if (scanSpec.overrideClasspath == null //
                    && (SystemJarFinder.getJreLibOrExtJars().contains(pathElementStrResolved)
                            || pathElementStrResolved.equals(SystemJarFinder.getJreRtJarPath()))) {
                // JRE lib and ext jars are handled separately, so reject them as duplicates if they are 
                // returned by a system classloader
                return false;
            }
            if (classpathEntryUniqueResolvedPaths.add(pathElementStrResolved)) {
                order.add(new ClasspathEntry(pathElementStrResolved, classLoader));
                return true;
            }
        }
        return false;
    }

    /**
     * Add a classpath element relative to a base file. May be called by a ClassLoaderHandler to add classpath
     * elements that it knows about. ClassLoaders will be called in order.
     *
     * @param pathElement
     *            the {@link String} path, {@link URL} or {@link URI} of the classpath element, or some object whose
     *            {@link Object#toString()} method can be called to obtain the classpath element.
     * @param classLoader
     *            the ClassLoader that this classpath element was obtained from.
     * @param scanSpec
     *            the scan spec
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathElement is not null, empty, nonexistent, or filtered out
     *         by user-specified criteria, otherwise return false.
     */
    public boolean addClasspathEntry(final Object pathElement, final ClassLoader classLoader,
            final ScanSpec scanSpec, final LogNode log) {
        if (pathElement == null) {
            return false;
        }
        String pathElementStr;
        if (pathElement instanceof Path) {
            try {
                // Path objects have to be converted to URIs before calling .toString(), otherwise scheme is dropped 
                pathElementStr = ((Path) pathElement).toUri().toString();
                // Windows paths ("C:\x\y") are encoded as "file:///C:/x/y" by Path.toUri().toString(),
                // but then Paths.get() can't handle paths of the form "///C:/x/y"
                if (pathElementStr.startsWith("file:///")) {
                    pathElementStr = ((Path) pathElement).toFile().toString();
                }
            } catch (IOError | SecurityException e) {
                pathElementStr = pathElement.toString();
            }
        } else {
            pathElementStr = pathElement.toString();
        }
        pathElementStr = FastPathResolver.resolve(FileUtils.currDirPath(), pathElementStr);
        if (pathElementStr.isEmpty()) {
            return false;
        }
        URL pathElementURL = null;
        boolean hasWildcardSuffix = false;
        // Fallback -- call toString() on the path element, then try converting to a URL
        if (pathElementStr.endsWith("/*") || pathElementStr.endsWith("\\*")) {
            hasWildcardSuffix = true;
            pathElementStr = pathElementStr.substring(0, pathElementStr.length() - 2);
            // Leave pathElementURL null, so that wildcards can be handled below
        } else if (pathElementStr.equals("*")) {
            hasWildcardSuffix = true;
            pathElementStr = "";
            // Leave pathElementURL null, so that wildcards can be handled below
        } else {
            final Matcher m1 = schemeMatcher.matcher(pathElementStr);
            if (m1.find()) {
                // Path element string is URL with scheme other than `[jar:]file:`, so need to actually
                // parse URL, since the scheme may be a custom scheme
                try {
                    pathElementURL = pathElement instanceof URL ? (URL) pathElement
                            : pathElement instanceof URI ? ((URI) pathElement).toURL()
                                    : pathElement instanceof Path ? ((Path) pathElement).toUri().toURL()
                                            : pathElement instanceof File ? ((File) pathElement).toURI().toURL()
                                                    : null;
                } catch (final MalformedURLException | IllegalArgumentException | IOError | SecurityException e2) {
                    // Fall through
                }
                if (pathElementURL == null) {
                    // Escape percentage characters in URLs (#255)
                    final String urlStr = pathElementStr.replace("%", "%25");
                    try {
                        pathElementURL = new URL(urlStr);
                    } catch (final MalformedURLException e) {
                        try {
                            pathElementURL = new File(urlStr).toURI().toURL();
                        } catch (final MalformedURLException | IllegalArgumentException | IOError
                                | SecurityException e1) {
                            // Final fallback -- try just using the raw string as a URL
                            try {
                                pathElementURL = new URL(pathElementStr);
                            } catch (final MalformedURLException e2) {
                                // Fall through
                            }
                        }
                    }
                }
                if (pathElementURL == null) {
                    if (log != null) {
                        log.log("Failed to convert classpath element to URL: " + pathElement);
                    }
                }
            }
        }
        if (pathElementURL != null || pathElement instanceof URI || pathElement instanceof File
                || pathElement instanceof Path) {
            if (!filter(pathElementURL, pathElementStr)) {
                if (log != null) {
                    log.log("Classpath element did not match filter criterion, skipping: " + pathElementStr);
                }
                return false;
            }
            // For URL objects, use the object itself (so that URL scheme handling can be undertaken later);
            // for URI and Path objects, convert to URL; for File objects, use the toString result (the path)
            final Object classpathElementObj;
            classpathElementObj = pathElement instanceof File ? pathElementStr
                    : pathElementURL != null ? pathElementURL : pathElement;
            if (addClasspathEntry(classpathElementObj, pathElementStr, classLoader, scanSpec)) {
                if (log != null) {
                    log.log("Found classpath element: " + pathElementStr);
                }
                return true;
            } else {
                if (log != null) {
                    log.log("Ignoring duplicate classpath element: " + pathElementStr);
                }
                return false;
            }
        }
        if (hasWildcardSuffix) {
            // Has wildcard path element (allowable for local classpaths as of JDK 6)
            // Apply classpath element filters, if any 
            final String baseDirPath = pathElementStr;
            final String baseDirPathResolved = FastPathResolver.resolve(FileUtils.currDirPath(), baseDirPath);
            if (!filter(pathElementURL, baseDirPath)
                    || (!baseDirPathResolved.equals(baseDirPath) && !filter(pathElementURL, baseDirPathResolved))) {
                if (log != null) {
                    log.log("Classpath element did not match filter criterion, skipping: " + pathElementStr);
                }
                return false;
            }

            // Check the path before the "/*" suffix is a directory 
            final File baseDir = new File(baseDirPathResolved);
            if (!baseDir.exists()) {
                if (log != null) {
                    log.log("Directory does not exist for wildcard classpath element: " + pathElementStr);
                }
                return false;
            }
            if (!FileUtils.canRead(baseDir)) {
                if (log != null) {
                    log.log("Cannot read directory for wildcard classpath element: " + pathElementStr);
                }
                return false;
            }
            if (!baseDir.isDirectory()) {
                if (log != null) {
                    log.log("Wildcard is appended to something other than a directory: " + pathElementStr);
                }
                return false;
            }

            // Add all elements in the requested directory to the classpath
            final LogNode dirLog = log == null ? null
                    : log.log("Adding classpath elements from wildcarded directory: " + pathElementStr);
            final File[] baseDirFiles = baseDir.listFiles();
            if (baseDirFiles != null) {
                for (final File fileInDir : baseDirFiles) {
                    final String name = fileInDir.getName();
                    if (!name.equals(".") && !name.equals("..")) {
                        // Add each directory entry as a classpath element
                        final String fileInDirPath = fileInDir.getPath();
                        final String fileInDirPathResolved = FastPathResolver.resolve(FileUtils.currDirPath(),
                                fileInDirPath);
                        if (addClasspathEntry(fileInDirPathResolved, fileInDirPathResolved, classLoader,
                                scanSpec)) {
                            if (dirLog != null) {
                                dirLog.log("Found classpath element: " + fileInDirPath
                                        + (fileInDirPath.equals(fileInDirPathResolved) ? ""
                                                : " -> " + fileInDirPathResolved));
                            }
                        } else {
                            if (dirLog != null) {
                                dirLog.log("Ignoring duplicate classpath element: " + fileInDirPath
                                        + (fileInDirPath.equals(fileInDirPathResolved) ? ""
                                                : " -> " + fileInDirPathResolved));
                            }
                        }
                    }
                }
                return true;
            } else {
                return false;
            }
        } else {
            // Non-wildcarded (standard) classpath element
            if (pathElementStr.indexOf('*') >= 0) {
                if (log != null) {
                    log.log("Wildcard classpath elements can only end with a suffix of \"/*\", "
                            + "can't use globs elsewhere in the path: " + pathElementStr);
                }
                return false;
            }
            final String pathElementResolved = FastPathResolver.resolve(FileUtils.currDirPath(), pathElementStr);
            if (!filter(pathElementURL, pathElementStr) || (!pathElementResolved.equals(pathElementStr)
                    && !filter(pathElementURL, pathElementResolved))) {
                if (log != null) {
                    log.log("Classpath element did not match filter criterion, skipping: " + pathElementStr
                            + (pathElementStr.equals(pathElementResolved) ? "" : " -> " + pathElementResolved));
                }
                return false;
            }
            if (pathElementResolved.startsWith("//")) {
                // Handle Windows UNC paths (#705).
                // File supports UNC paths directly:
                // https://wiki.eclipse.org/Eclipse/UNC_Paths#Programming_with_UNC_paths
                try {
                    final File file = new File(pathElementResolved);
                    if (addClasspathEntry(file, pathElementResolved, classLoader, scanSpec)) {
                        if (log != null) {
                            log.log("Found classpath element: " + file
                                    + (pathElementStr.equals(pathElementResolved) ? ""
                                            : " -> " + pathElementResolved));
                        }
                        return true;
                    } else {
                        if (log != null) {
                            log.log("Ignoring duplicate classpath element: " + pathElementStr
                                    + (pathElementStr.equals(pathElementResolved) ? ""
                                            : " -> " + pathElementResolved));
                        }
                        return false;
                    }
                } catch (final Exception e) {
                    // Fall through
                }
            }
            if (addClasspathEntry(pathElementResolved, pathElementResolved, classLoader, scanSpec)) {
                if (log != null) {
                    log.log("Found classpath element: " + pathElementStr
                            + (pathElementStr.equals(pathElementResolved) ? "" : " -> " + pathElementResolved));
                }
                return true;
            } else {
                if (log != null) {
                    log.log("Ignoring duplicate classpath element: " + pathElementStr
                            + (pathElementStr.equals(pathElementResolved) ? "" : " -> " + pathElementResolved));
                }
                return false;
            }
        }
    }

    /**
     * Add classpath entries, separated by the system path separator character.
     *
     * @param overrideClasspath
     *            a list of delimited path {@link String}, {@link URL}, {@link URI} or {@link File} objects.
     * @param classLoader
     *            the ClassLoader that this classpath was obtained from.
     * @param scanSpec
     *            the scan spec
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    public boolean addClasspathEntries(final List<Object> overrideClasspath, final ClassLoader classLoader,
            final ScanSpec scanSpec, final LogNode log) {
        if (overrideClasspath == null || overrideClasspath.isEmpty()) {
            return false;
        } else {
            for (final Object pathElement : overrideClasspath) {
                addClasspathEntry(pathElement, classLoader, scanSpec, log);
            }
            return true;
        }
    }

    /**
     * Add classpath entries, separated by the system path separator character.
     *
     * @param pathStr
     *            the delimited string of URLs or paths of the classpath.
     * @param classLoader
     *            the ClassLoader that this classpath was obtained from.
     * @param scanSpec
     *            the scan spec
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathElement is not null or empty, otherwise return false.
     */
    public boolean addClasspathPathStr(final String pathStr, final ClassLoader classLoader, final ScanSpec scanSpec,
            final LogNode log) {
        if (pathStr == null || pathStr.isEmpty()) {
            return false;
        } else {
            final String[] parts = JarUtils.smartPathSplit(pathStr, scanSpec);
            if (parts.length == 0) {
                return false;
            } else {
                for (final String pathElement : parts) {
                    addClasspathEntry(pathElement, classLoader, scanSpec, log);
                }
                return true;
            }
        }
    }

    /**
     * Add classpath entries from an object obtained from reflection. The object may be a {@link URL}, a
     * {@link URI}, a {@link File}, a {@link Path} or a {@link String} (containing a single classpath element path,
     * or several paths separated with File.pathSeparator), a List or other Iterable, or an array object. In the
     * case of Iterables and arrays, the elements may be any type whose {@code toString()} method returns a path or
     * URL string (including the {@code URL} and {@code Path} types).
     *
     * @param pathObject
     *            the object containing a classpath string or strings.
     * @param classLoader
     *            the ClassLoader that this classpath was obtained from.
     * @param scanSpec
     *            the scan spec
     * @param log
     *            the LogNode instance to use if logging in verbose mode.
     * @return true (and add the classpath element) if pathEl)ement is not null or empty, otherwise return false.
     */
    public boolean addClasspathEntryObject(final Object pathObject, final ClassLoader classLoader,
            final ScanSpec scanSpec, final LogNode log) {
        boolean valid = false;
        if (pathObject != null) {
            if (pathObject instanceof URL || pathObject instanceof URI || pathObject instanceof Path
                    || pathObject instanceof File) {
                valid |= addClasspathEntry(pathObject, classLoader, scanSpec, log);
            } else if (pathObject instanceof Iterable) {
                for (final Object elt : (Iterable<?>) pathObject) {
                    valid |= addClasspathEntryObject(elt, classLoader, scanSpec, log);
                }
            } else {
                final Class<?> valClass = pathObject.getClass();
                if (valClass.isArray()) {
                    for (int j = 0, n = Array.getLength(pathObject); j < n; j++) {
                        final Object elt = Array.get(pathObject, j);
                        valid |= addClasspathEntryObject(elt, classLoader, scanSpec, log);
                    }
                } else {
                    // Try simply calling toString() as a final fallback, to handle String objects, or to
                    // try to handle anything else
                    valid |= addClasspathPathStr(pathObject.toString(), classLoader, scanSpec, log);
                }
            }
        }
        return valid;
    }
}
