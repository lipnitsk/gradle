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

import com.google.common.collect.Lists;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.file.collections.SimpleFileCollection;
import org.gradle.api.internal.tasks.compile.JavaCompileSpec;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.internal.Factory;

import java.io.File;
import java.util.Collection;
import java.util.List;

class IncrementalCompilationInitializer {
    private final FileOperations fileOperations;

    public IncrementalCompilationInitializer(FileOperations fileOperations) {
        this.fileOperations = fileOperations;
    }

    public void initializeCompilation(JavaCompileSpec spec, Collection<String> staleClasses) {
        if (staleClasses.isEmpty()) {
            spec.setSource(new SimpleFileCollection());
            return; //do nothing. No classes need recompilation.
        }

        Factory<PatternSet> patternSetFactory = fileOperations.getFileResolver().getPatternSetFactory();
        PatternSet classesToDelete = patternSetFactory.create();
        PatternSet sourceToCompile = patternSetFactory.create();
        PatternSet sourceToDelete = patternSetFactory.create();

        preparePatterns(staleClasses, classesToDelete, sourceToCompile, sourceToDelete);

        //selectively configure the source
        spec.setSource(spec.getSource().getAsFileTree().matching(sourceToCompile));
        //since we're compiling selectively we need to include the classes compiled previously
        List<File> classpath = Lists.newArrayList(spec.getCompileClasspath());
        classpath.add(spec.getDestinationDir());
        spec.setCompileClasspath(classpath);
        //get rid of stale files
        fileOperations.delete(fileOperations.fileTree(spec.getDestinationDir()).matching(classesToDelete));
        File generatedSourcesDirectory = spec.getCompileOptions().getAnnotationProcessorGeneratedSourcesDirectory();
        if (generatedSourcesDirectory == null) {
            generatedSourcesDirectory = spec.getDestinationDir();
        }
        fileOperations.delete(fileOperations.fileTree(generatedSourcesDirectory).matching(sourceToDelete));
    }

    void preparePatterns(Collection<String> staleClasses, PatternSet classesToDelete, PatternSet sourceToCompile, PatternSet sourceToDelete) {
        assert !staleClasses.isEmpty(); //if stale classes are empty (e.g. nothing to recompile), the patterns will not have any includes and will match all (e.g. recompile everything).
        for (String staleClass : staleClasses) {
            String path = staleClass.replaceAll("\\.", "/");
            classesToDelete.include(path.concat(".class"));
            classesToDelete.include(path.concat("$*.class"));
            classesToDelete.include(path.concat("*Helper.class"));
            classesToDelete.include(path.concat("$*Helper.class"));

            sourceToDelete.include(path.concat("*Helper.java"));
            sourceToDelete.include(path.concat("$*Helper.java"));

            //the stale class might be a source class that was deleted
            //it's no harm to include it in sourceToCompile anyway
            sourceToCompile.include(path.concat(".java"));
            sourceToCompile.include(path.concat("$*.java"));
        }
    }
}
