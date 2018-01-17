/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.compile.incremental;

import org.apache.tools.zip.ZipEntry;
import org.apache.tools.zip.ZipFile;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.tasks.compile.CleaningJavaCompiler;
import org.gradle.api.internal.tasks.compile.JavaCompileSpec;
import org.gradle.api.internal.tasks.compile.incremental.cache.CompileCaches;
import org.gradle.api.internal.tasks.compile.incremental.deps.ClassSetAnalysis;
import org.gradle.api.internal.tasks.compile.incremental.deps.ClassSetAnalysisData;
import org.gradle.api.internal.tasks.compile.incremental.jar.JarClasspathSnapshotMaker;
import org.gradle.api.internal.tasks.compile.incremental.jar.PreviousCompilation;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.incremental.IncrementalTaskInputs;
import org.gradle.language.base.internal.compile.Compiler;

import java.io.File;
import java.io.IOException;

public class IncrementalCompilerDecorator {

    private static final Logger LOG = Logging.getLogger(IncrementalCompilerDecorator.class);
    private final JarClasspathSnapshotMaker jarClasspathSnapshotMaker;
    private final CompileCaches compileCaches;
    private final CleaningJavaCompiler cleaningCompiler;
    private final String displayName;
    private final RecompilationSpecProvider staleClassDetecter;
    private final ClassSetAnalysisUpdater classSetAnalysisUpdater;
    private final CompilationSourceDirs sourceDirs;
    private final FileCollection annotationProcessorPath;
    private final IncrementalCompilationInitializer compilationInitializer;

    public IncrementalCompilerDecorator(JarClasspathSnapshotMaker jarClasspathSnapshotMaker, CompileCaches compileCaches,
                                        IncrementalCompilationInitializer compilationInitializer, CleaningJavaCompiler cleaningCompiler, String displayName,
                                        RecompilationSpecProvider staleClassDetecter, ClassSetAnalysisUpdater classSetAnalysisUpdater,
                                        CompilationSourceDirs sourceDirs, FileCollection annotationProcessorPath) {
        this.jarClasspathSnapshotMaker = jarClasspathSnapshotMaker;
        this.compileCaches = compileCaches;
        this.compilationInitializer = compilationInitializer;
        this.cleaningCompiler = cleaningCompiler;
        this.displayName = displayName;
        this.staleClassDetecter = staleClassDetecter;
        this.classSetAnalysisUpdater = classSetAnalysisUpdater;
        this.sourceDirs = sourceDirs;
        this.annotationProcessorPath = annotationProcessorPath;
    }

    public Compiler<JavaCompileSpec> prepareCompiler(IncrementalTaskInputs inputs) {
        Compiler<JavaCompileSpec> compiler = getCompiler(inputs, sourceDirs);
        return new IncrementalCompilationFinalizer(compiler, jarClasspathSnapshotMaker, classSetAnalysisUpdater);
    }

    private Compiler<JavaCompileSpec> getCompiler(IncrementalTaskInputs inputs, CompilationSourceDirs sourceDirs) {
        if (!inputs.isIncremental()) {
            LOG.info("{} - is not incremental (e.g. outputs have changed, no previous execution, etc.).", displayName);
            return cleaningCompiler;
        }
        if (!sourceDirs.canInferSourceRoots()) {
            LOG.info("{} - is not incremental. Unable to infer the source directories.", displayName);
            return cleaningCompiler;
        }
        if (hasNonIncrementalProcessors()) {
            LOG.info("{} - is not incremental. Non-incremental annotation processors are present.", displayName);
            return cleaningCompiler;
        }
        ClassSetAnalysisData data = compileCaches.getLocalClassSetAnalysisStore().get();
        if (data == null) {
            LOG.info("{} - is not incremental. No class analysis data available from the previous build.", displayName);
            return cleaningCompiler;
        }
        PreviousCompilation previousCompilation = new PreviousCompilation(new ClassSetAnalysis(data), compileCaches.getLocalJarClasspathSnapshotStore(), compileCaches.getJarSnapshotCache());
        return new SelectiveCompiler(inputs, previousCompilation, cleaningCompiler, staleClassDetecter, compilationInitializer, jarClasspathSnapshotMaker);
    }

    private boolean hasNonIncrementalProcessors() {
        for (File file : annotationProcessorPath) {
            if (file.isDirectory()) {
                File processorDeclaration = new File(file, "META-INF/services/javax.annotation.processing.Processor");
                File incrementalDeclaration = new File(file, "/META-INF/services/org.gradle.processing.Processor");
                if (processorDeclaration.isFile() && !incrementalDeclaration.isFile()) {
                    return true;
                }
            }
            if (file.getPath().endsWith(".jar")) {
                ZipFile zipFile = null;
                try {
                    zipFile = new ZipFile(file);
                    ZipEntry processorDeclaration = zipFile.getEntry("META-INF/services/javax.annotation.processing.Processor");
                    ZipEntry incrementalDeclaration = zipFile.getEntry("META-INF/services/org.gradle.processing.Processor");
                    if (processorDeclaration != null && incrementalDeclaration == null) {
                        return true;
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } finally {
                    try {
                        zipFile.close();
                    } catch (IOException ignored) {
                        //ignore in favor of previous failure
                    }
                }
            }
        }
        return false;
    }
}
