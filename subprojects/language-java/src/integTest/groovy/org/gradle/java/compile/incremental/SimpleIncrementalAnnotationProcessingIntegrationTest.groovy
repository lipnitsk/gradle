/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.java.compile.incremental

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.CompilationOutputsFixture
import org.gradle.language.fixtures.AnnotationProcessorFixture

class SimpleIncrementalAnnotationProcessingIntegrationTest extends AbstractIntegrationSpec {

    CompilationOutputsFixture outputs

    def setup() {
        executer.requireOwnGradleUserHomeDir()

        outputs = new CompilationOutputsFixture(file("build/classes"))

        def annotationProcessorProjectDir = testDirectory.file("annotation-processor").createDir()

        settingsFile << """
            include "annotation-processor"
        """
        buildFile << """
            apply plugin: 'java'
            
            configurations {
                annotationProcessor
            }
            
            dependencies {
                compileOnly project(":annotation-processor")
                annotationProcessor project(":annotation-processor")
            }
            
            compileJava {
                compileJava.options.incremental = true
                options.fork = true
                options.annotationProcessorPath = configurations.annotationProcessor
            }
        """

        annotationProcessorProjectDir.file("build.gradle") << """
            apply plugin: "java"
        """

        def fixture = new AnnotationProcessorFixture()
        fixture.incremental = true
        fixture.writeSupportLibraryTo(annotationProcessorProjectDir)
        fixture.writeApiTo(annotationProcessorProjectDir)
        fixture.writeAnnotationProcessorTo(annotationProcessorProjectDir)
    }

    private File java(String... classBodies) {
        File out
        for (String body : classBodies) {
            def className = (body =~ /(?s).*?class (\w+) .*/)[0][1]
            assert className: "unable to find class name"
            def f = file("src/main/java/${className}.java")
            f.createFile()
            f.text = body
            out = f
        }
        out
    }

    def "generated classes are recompiled when source changes"() {
        def a = java "@Helper class A {}"

        outputs.snapshot { run "compileJava" }

        when:
        a.text = "@Helper class A { public void foo() {} }"
        run "compileJava"

        then:
        outputs.recompiledClasses("A", "AHelper")
    }

    def "unrelated classes are not recompiled when an annotated class changes"() {
        def a = java "@Helper class A {}"
        java "class B {}"

        outputs.snapshot { run "compileJava" }

        when:
        a.text = "@Helper class A { public void foo() {} }"
        run "compileJava"

        then:
        outputs.recompiledClasses("A", "AHelper")
    }
}
