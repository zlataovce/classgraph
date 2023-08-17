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
package io.github.classgraph;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import nonapi.io.github.classgraph.concurrency.SingletonMap;
import nonapi.io.github.classgraph.concurrency.SingletonMap.NewInstanceException;
import nonapi.io.github.classgraph.concurrency.SingletonMap.NullSingletonException;
import nonapi.io.github.classgraph.concurrency.WorkQueue;
import nonapi.io.github.classgraph.fastzipfilereader.LogicalZipFile;
import nonapi.io.github.classgraph.fileslice.reader.ClassfileReader;
import nonapi.io.github.classgraph.recycler.RecycleOnClose;
import nonapi.io.github.classgraph.recycler.Recycler;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.scanspec.ScanSpec.ScanSpecPathMatch;
import nonapi.io.github.classgraph.utils.CollectionUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.ProxyingInputStream;
import nonapi.io.github.classgraph.utils.VersionFinder;

/**
 * A module classpath element.
 *
 * @author luke
 */
class ClasspathElementModule extends ClasspathElement {

    /** The module ref. */
    final ModuleRef moduleRef;

    /** A singleton map from a {@link ModuleRef} to a {@link ModuleReaderProxy} recycler for the module. */
    SingletonMap<ModuleRef, Recycler<ModuleReaderProxy, IOException>, IOException> //
    moduleRefToModuleReaderProxyRecyclerMap;

    /** The module reader proxy recycler. */
    private Recycler<ModuleReaderProxy, IOException> moduleReaderProxyRecycler;

    /** All resource paths. */
    private final Set<String> allResourcePaths = new HashSet<>();

    /**
     * A zip/jarfile classpath element.
     *
     * @param moduleRef
     *            the module ref
     * @param workUnit
     *            the work unit
     * @param moduleRefToModuleReaderProxyRecyclerMap
     *            the module ref to module reader proxy recycler map
     * @param scanSpec
     *            the scan spec
     */
    ClasspathElementModule(final ModuleRef moduleRef,
            final SingletonMap<ModuleRef, Recycler<ModuleReaderProxy, IOException>, IOException> // 
            moduleRefToModuleReaderProxyRecyclerMap, final ClasspathEntryWorkUnit workUnit,
            final ScanSpec scanSpec) {
        super(workUnit, scanSpec);
        this.moduleRefToModuleReaderProxyRecyclerMap = moduleRefToModuleReaderProxyRecyclerMap;
        this.moduleRef = moduleRef;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ClasspathElement#open(
     * nonapi.io.github.classgraph.concurrency.WorkQueue, nonapi.io.github.classgraph.utils.LogNode)
     */
    @Override
    void open(final WorkQueue<ClasspathEntryWorkUnit> workQueueIgnored, final LogNode log)
            throws InterruptedException {
        if (!scanSpec.scanModules) {
            if (log != null) {
                log(classpathElementIdx, "Skipping module, since module scanning is disabled: " + getModuleName(),
                        log);
            }
            skipClasspathElement = true;
            return;
        }
        try {
            moduleReaderProxyRecycler = moduleRefToModuleReaderProxyRecyclerMap.get(moduleRef, log);
        } catch (final IOException | NullSingletonException | NewInstanceException e) {
            if (log != null) {
                log(classpathElementIdx, "Skipping invalid module " + getModuleName() + " : "
                        + (e.getCause() == null ? e : e.getCause()), log);
            }
            skipClasspathElement = true;
            return;
        }
    }

    /**
     * Create a new {@link Resource} object for a resource or classfile discovered while scanning paths.
     *
     * @param resourcePath
     *            the resource path
     * @return the resource
     */
    private Resource newResource(final String resourcePath) {
        return new Resource(this, /* length unknown */ -1L) {
            /** The module reader proxy. */
            private ModuleReaderProxy moduleReaderProxy;

            /** True if the resource is open. */
            private final AtomicBoolean isOpen = new AtomicBoolean();

            @Override
            public String getPath() {
                return resourcePath;
            }

            @Override
            public long getLastModified() {
                return 0L; // Unknown
            }

            @Override
            public Set<PosixFilePermission> getPosixFilePermissions() {
                return null; // N/A
            }

            @Override
            public ByteBuffer read() throws IOException {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IOException("Module could not be opened");
                }
                if (isOpen.getAndSet(true)) {
                    throw new IOException(
                            "Resource is already open -- cannot open it again without first calling close()");
                }
                try {
                    moduleReaderProxy = moduleReaderProxyRecycler.acquire();
                    // ModuleReader#read(String name) internally calls:
                    // InputStream is = open(name); return ByteBuffer.wrap(is.readAllBytes());
                    byteBuffer = moduleReaderProxy.read(resourcePath);
                    length = byteBuffer.remaining();
                    return byteBuffer;

                } catch (final SecurityException | OutOfMemoryError e) {
                    close();
                    throw new IOException("Could not open " + this, e);
                }
            }

            @Override
            ClassfileReader openClassfile() throws IOException {
                return new ClassfileReader(open(), this);
            }

            @Override
            public InputStream open() throws IOException {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IOException("Module could not be opened");
                }
                if (isOpen.getAndSet(true)) {
                    throw new IOException(
                            "Resource is already open -- cannot open it again without first calling close()");
                }
                try {
                    final Resource thisResource = this;
                    moduleReaderProxy = moduleReaderProxyRecycler.acquire();
                    inputStream = new ProxyingInputStream(moduleReaderProxy.open(resourcePath)) {
                        @Override
                        public void close() throws IOException {
                            // Close the wrapped InputStream obtained from moduleReaderProxy
                            super.close();
                            try {
                                // Close the Resource, releasing any underlying ByteBuffer and recycling
                                // the moduleReaderProxy
                                thisResource.close();
                            } catch (final Exception e) {
                                // Ignore
                            }
                        }
                    };
                    // Length cannot be obtained from ModuleReader
                    length = -1L;
                    return inputStream;

                } catch (final SecurityException e) {
                    close();
                    throw new IOException("Could not open " + this, e);
                }
            }

            @Override
            public byte[] load() throws IOException {
                try (Resource res = this) { // Close this after use
                    read(); // Fill byteBuffer
                    final byte[] byteArray;
                    if (res.byteBuffer.hasArray() && res.byteBuffer.position() == 0
                            && res.byteBuffer.limit() == res.byteBuffer.capacity()) {
                        byteArray = res.byteBuffer.array();
                    } else {
                        byteArray = new byte[res.byteBuffer.remaining()];
                        res.byteBuffer.get(byteArray);
                    }
                    res.length = byteArray.length;
                    return byteArray;
                }
            }

            @Override
            public void close() {
                if (isOpen.getAndSet(false)) {
                    if (moduleReaderProxy != null) {
                        if (byteBuffer != null) {
                            // Release any open ByteBuffer
                            moduleReaderProxy.release(byteBuffer);
                            byteBuffer = null;
                        }
                        // Recycle the (open) ModuleReaderProxy instance.
                        moduleReaderProxyRecycler.recycle(moduleReaderProxy);
                        // Don't call ModuleReaderProxy#close(), leave the ModuleReaderProxy open in the recycler.
                        // Just set the ref to null here. The ModuleReaderProxy will be closed by
                        // ClasspathElementModule#close().
                        moduleReaderProxy = null;
                    }

                    // Close inputStream
                    super.close();
                }
            }
        };
    }

    /**
     * Get the {@link Resource} for a given relative path.
     *
     * @param relativePath
     *            The relative path of the {@link Resource} to return.
     * @return The {@link Resource} for the given relative path, or null if relativePath does not exist in this
     *         classpath element.
     */
    @Override
    Resource getResource(final String relativePath) {
        return allResourcePaths.contains(relativePath) ? newResource(relativePath) : null;
    }

    /**
     * Scan for package matches within module.
     *
     * @param log
     *            the log
     */
    @Override
    void scanPaths(final LogNode log) {
        if (skipClasspathElement) {
            return;
        }
        if (scanned.getAndSet(true)) {
            // Should not happen
            throw new IllegalArgumentException("Already scanned classpath element " + this);
        }

        final LogNode subLog = log == null ? null
                : log(classpathElementIdx, "Scanning module " + moduleRef.getName(), log);

        // Determine whether this is a modular jar running under JRE 9+
        final boolean isModularJar = VersionFinder.JAVA_MAJOR_VERSION >= 9 && getModuleName() != null;

        try (RecycleOnClose<ModuleReaderProxy, IOException> moduleReaderProxyRecycleOnClose //
                = moduleReaderProxyRecycler.acquireRecycleOnClose()) {
            // Look for accepted files in the module.
            List<String> resourceRelativePaths;
            try {
                resourceRelativePaths = moduleReaderProxyRecycleOnClose.get().list();
            } catch (final SecurityException e) {
                if (subLog != null) {
                    subLog.log("Could not get resource list for module " + moduleRef.getName(), e);
                }
                return;
            }
            CollectionUtils.sortIfNotEmpty(resourceRelativePaths);

            String prevParentRelativePath = null;
            ScanSpecPathMatch prevParentMatchStatus = null;
            for (final String relativePath : resourceRelativePaths) {
                // From ModuleReader#find(): "If the module reader can determine that the name locates a
                // directory then the resulting URI will end with a slash ('/')."  But from the documentation
                // for ModuleReader#list(): "Whether the stream of elements includes names corresponding to
                // directories in the module is module reader specific."  We don't have a way of checking if
                // a resource is a directory without trying to open it, unless ModuleReader#list() also decides
                // to put a "/" on the end of resource paths corresponding to directories. Skip directories if
                // they are found, but if they are not able to be skipped, we will have to settle for having
                // some IOExceptions thrown when directories are mistaken for resource files.
                if (relativePath.endsWith("/")) {
                    continue;
                }

                // Paths in modules should never start with "META-INF/versions/{version}/", because the module
                // system should already strip these prefixes away. If they are found, then the jarfile must
                // contain a path like "META-INF/versions/{version}/META-INF/versions/{version}/", which cannot
                // be valid (META-INF should only ever exist in the module root), and the nested versioned section
                // should be ignored.
                if (!scanSpec.enableMultiReleaseVersions
                        && relativePath.startsWith(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX)) {
                    if (subLog != null) {
                        subLog.log(
                                "Found unexpected nested versioned entry in module -- skipping: " + relativePath);
                    }
                    continue;
                }

                // If this is a modular jar, ignore all classfiles other than "module-info.class" in the
                // default package, since these are disallowed.
                if (isModularJar && relativePath.indexOf('/') < 0 && relativePath.endsWith(".class")
                        && !relativePath.equals("module-info.class")) {
                    continue;
                }

                // Accept/reject classpath elements based on file resource paths
                if (!checkResourcePathAcceptReject(relativePath, log)) {
                    continue;
                }

                // Get match status of the parent directory of this resource's relative path (or reuse the last
                // match status for speed, if the directory name hasn't changed).
                final int lastSlashIdx = relativePath.lastIndexOf('/');
                final String parentRelativePath = lastSlashIdx < 0 ? "/"
                        : relativePath.substring(0, lastSlashIdx + 1);
                final boolean parentRelativePathChanged = !parentRelativePath.equals(prevParentRelativePath);
                final ScanSpecPathMatch parentMatchStatus = //
                        prevParentRelativePath == null || parentRelativePathChanged
                                ? scanSpec.dirAcceptMatchStatus(parentRelativePath)
                                : prevParentMatchStatus;
                prevParentRelativePath = parentRelativePath;
                prevParentMatchStatus = parentMatchStatus;

                if (parentMatchStatus == ScanSpecPathMatch.HAS_REJECTED_PATH_PREFIX) {
                    // The parent dir or one of its ancestral dirs is rejected
                    if (subLog != null) {
                        subLog.log("Skipping rejected path: " + relativePath);
                    }
                    continue;
                }

                // Found non-rejected relative path
                if (allResourcePaths.add(relativePath)) {
                    // If resource is accepted
                    if (parentMatchStatus == ScanSpecPathMatch.HAS_ACCEPTED_PATH_PREFIX
                            || parentMatchStatus == ScanSpecPathMatch.AT_ACCEPTED_PATH
                            || (parentMatchStatus == ScanSpecPathMatch.AT_ACCEPTED_CLASS_PACKAGE
                                    && scanSpec.classfileIsSpecificallyAccepted(relativePath))) {
                        // Add accepted resource
                        addAcceptedResource(newResource(relativePath), parentMatchStatus,
                                /* isClassfileOnly = */ false, subLog);
                    } else if (scanSpec.enableClassInfo && relativePath.equals("module-info.class")) {
                        // Add module descriptor as an accepted classfile resource, so that it is scanned,
                        // but don't add it to the list of resources in the ScanResult, since it is not
                        // in an accepted package (#352)
                        addAcceptedResource(newResource(relativePath), parentMatchStatus,
                                /* isClassfileOnly = */ true, subLog);
                    }
                }
            }

            // Save last modified time for the module file
            final File moduleFile = moduleRef.getLocationFile();
            if (moduleFile != null && moduleFile.exists()) {
                fileToLastModified.put(moduleFile, moduleFile.lastModified());
            }

        } catch (final IOException e) {
            if (subLog != null) {
                subLog.log("Exception opening module " + moduleRef.getName(), e);
            }
            skipClasspathElement = true;
        }

        finishScanPaths(subLog);
    }

    /**
     * Get the ModuleRef for this classpath element.
     *
     * @return the module ref
     */
    ModuleRef getModuleRef() {
        return moduleRef;
    }

    /**
     * Get the module name from the module reference or the module descriptor.
     *
     * @return the module name, or null if the module does not have a name.
     */
    @Override
    public String getModuleName() {
        String moduleName = moduleRef.getName();
        if (moduleName == null || moduleName.isEmpty()) {
            moduleName = moduleNameFromModuleDescriptor;
        }
        return moduleName == null || moduleName.isEmpty() ? null : moduleName;
    }

    /**
     * Get the module name from the module reference or the module descriptor.
     *
     * @return the module name, or the empty string if the module does not have a name.
     */
    private String getModuleNameOrEmpty() {
        final String moduleName = getModuleName();
        return moduleName == null ? "" : moduleName;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ClasspathElement#getURI()
     */
    @Override
    URI getURI() {
        final URI uri = moduleRef.getLocation();
        if (uri == null) {
            // Some modules have no known module location (ModuleReference#location() can return null)
            throw new IllegalArgumentException("Module " + getModuleName() + " has a null location");
        }
        return uri;
    }

    @Override
    List<URI> getAllURIs() {
        return Collections.singletonList(getURI());
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ClasspathElement#getFile()
     */
    @Override
    File getFile() {
        try {
            final URI uri = moduleRef.getLocation();
            if (uri != null && !uri.getScheme().equals("jrt")) {
                final File file = new File(uri);
                if (file.exists()) {
                    return file;
                }
            }
        } catch (final Exception e) {
            // Invalid "file:" URI
        }
        return null;
    }

    /**
     * Return the module reference as a String.
     *
     * @return the string
     */
    @Override
    public String toString() {
        return moduleRef.toString();
    }

    /**
     * Equals.
     *
     * @param obj
     *            the obj
     * @return true, if successful
     */
    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof ClasspathElementModule)) {
            return false;
        }
        final ClasspathElementModule other = (ClasspathElementModule) obj;
        return this.getModuleNameOrEmpty().equals(other.getModuleNameOrEmpty());
    }

    /**
     * Hash code.
     *
     * @return the int
     */
    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return getModuleNameOrEmpty().hashCode();
    }
}
