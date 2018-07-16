/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.build

import groovy.transform.CompileStatic
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

@CompileStatic
class GradleDistributionWithSamples extends GradleDistribution {

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    ConfigurableFileTree samples

    GradleDistributionWithSamples(Project project, DirectoryProperty gradleHomeDir) {
        super(project, gradleHomeDir)
        samples = project.fileTree(gradleHomeDir.dir('samples'))
    }
}