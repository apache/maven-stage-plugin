/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.stage;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.wagon.repository.Repository;
import org.codehaus.plexus.PlexusTestCase;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/** @author Jason van Zyl */
public class RepositoryCopierTest extends PlexusTestCase {
    private String version = "2.0.6";

    private MetadataXpp3Reader reader = new MetadataXpp3Reader();

    public void testCopy() throws Exception {
        RepositoryCopier copier = (RepositoryCopier) lookup(RepositoryCopier.ROLE);

        File targetRepoSource = new File(getBasedir(), "src/test/target-repository");

        File targetRepo = new File(getBasedir(), "target/target-repository");

        FileUtils.copyDirectory(targetRepoSource, targetRepo);

        File stagingRepo = new File(getBasedir(), "src/test/staging-repository");

        Repository sourceRepository = new Repository("source", "file://" + stagingRepo);
        Repository targetRepository = new Repository("target", "scp://localhost/" + targetRepo);

        copier.copy(sourceRepository, targetRepository, version);

        String s[] = {
            "maven",
            "maven-artifact",
            "maven-artifact-manager",
            "maven-artifact-test",
            "maven-core",
            "maven-error-diagnostics",
            "maven-model",
            "maven-monitor",
            "maven-plugin-api",
            "maven-plugin-descriptor",
            "maven-plugin-parameter-documenter",
            "maven-plugin-registry",
            "maven-profile",
            "maven-project",
            "maven-repository-metadata",
            "maven-script",
            "maven-script-ant",
            "maven-script-beanshell",
            "maven-settings"
        };

        for (String value : s) {
            testMavenArtifact(targetRepo, value);
        }

        // leave something behind to clean it up.

        // Test merging

        // Test MD5

        // Test SHA1

        // Test new artifacts are present
    }

    private void testMavenArtifact(File repo, String artifact) throws IOException, XmlPullParserException {
        File basedir = new File(repo, "org/apache/maven/" + artifact);

        File versionDir = new File(basedir, version);

        assertTrue(versionDir.exists());

        File file = new File(basedir, RepositoryCopier.MAVEN_METADATA);
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Metadata metadata = reader.read(r);

            // Make sure our new version has been setup as the release.
            assertEquals(version, metadata.getVersioning().getRelease());

            assertEquals("20070327020553", metadata.getVersioning().getLastUpdated());

            // Make sure we didn't whack old versions.
            List<String> versions = metadata.getVersioning().getVersions();

            assertTrue(versions.contains("2.0.1"));

            assertTrue(versions.contains("2.0.2"));

            assertTrue(versions.contains("2.0.3"));

            assertTrue(versions.contains("2.0.4"));

            assertTrue(versions.contains("2.0.5"));
        }
    }
}
