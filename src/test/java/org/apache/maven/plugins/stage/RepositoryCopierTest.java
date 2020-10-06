package org.apache.maven.plugins.stage;

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

import com.jcraft.jsch.JSch;
import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.manager.WagonManager;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.wagon.repository.Repository;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.kex.KeyExchange;
import org.apache.sshd.common.keyprovider.AbstractFileKeyPairProvider;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.common.util.SecurityUtils;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.UserAuth;
import org.apache.sshd.server.auth.UserAuthNoneFactory;
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.scp.ScpCommandFactory;
import org.apache.sshd.server.subsystem.sftp.SftpSubsystemFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.codehaus.plexus.PlexusTestCase;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** @author Jason van Zyl */
public class RepositoryCopierTest
    extends PlexusTestCase
{
    private static final int PORT = 3543;
    private static final String STAGING_REPOSITORY = "src/test/staging-repository";
    private static final String TARGET_REPOSITORY = "src/test/target-repository";
    private static final String WORKING_TARGET_REPOSITORY = "src/test/working-target-repository";
    private static final String KEY = "src/test/key";
    private static final String version = "2.0.6";

    private final MetadataXpp3Reader reader = new MetadataXpp3Reader();

    private SshServer sshd;

    public void setUp() throws Exception
    {
        super.setUp();
        startFtpServer();
        makeWorkingRepository();
    }

    private void startFtpServer()
    {
        sshd = SshServer.setUpDefaultServer();
        sshd.setPort( PORT );

        Security.addProvider( new BouncyCastleProvider() );
        AbstractFileKeyPairProvider fileKeyPairProvider = SecurityUtils.createFileKeyPairProvider();
        fileKeyPairProvider.setFiles(
            Collections.singletonList( new File( getBasedir(), KEY) )
        );
        sshd.setKeyPairProvider( fileKeyPairProvider );

        sshd.setFileSystemFactory(new VirtualFileSystemFactory() {
            @Override
            public Path getDefaultHomeDir()
            {
                return serverFileSystemRoot();
            }
        });

        List<NamedFactory<UserAuth>> userAuthFactories = new ArrayList<>();
        userAuthFactories.add( new UserAuthNoneFactory() );
        sshd.setUserAuthFactories( userAuthFactories );
        sshd.setPublickeyAuthenticator( AcceptAllPublickeyAuthenticator.INSTANCE );

        final ScpCommandFactory scpCommandFactory = new ScpCommandFactory();
        scpCommandFactory.setDelegateCommandFactory( new FakeUnixCommandFactory( serverFileSystemRoot() ) );
        sshd.setCommandFactory(scpCommandFactory);

        List<NamedFactory<Command>> namedFactoryList = new ArrayList<>();
        namedFactoryList.add( new SftpSubsystemFactory() );
        sshd.setSubsystemFactories( namedFactoryList );
        try
        {
            sshd.start();
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    // To make clean-up easier, copy the entire target repository to a new working copy. That way, rather than worry about what
    // modifications we might have made, we can simply delete the whole thing when we're done.
    private void makeWorkingRepository()
    {
        final File prototype = new File( getBasedir(), TARGET_REPOSITORY );
        final File workingDir = new File( getBasedir(), WORKING_TARGET_REPOSITORY );
        try
        {
            FileUtils.copyDirectory( prototype, workingDir );
        }
        catch ( IOException e )
        {
            throw new RuntimeException( "Couldn't copy target repository directory", e );
        }
    }

    private void deleteWorkingRepository() throws IOException
    {
        FileUtils.deleteDirectory( new File( getBasedir(), WORKING_TARGET_REPOSITORY ) );
    }

    private Path serverFileSystemRoot()
    {
        return new File( getBasedir(), WORKING_TARGET_REPOSITORY ).toPath();
    }

    public void tearDown() throws Exception
    {
        super.tearDown();
        sshd.stop();
        deleteWorkingRepository();
    }

    public void testCopy() throws Exception
    {
        DefaultRepositoryCopier copier = (DefaultRepositoryCopier) container.lookup( RepositoryCopier.ROLE );
        copier.overrideInteractiveUserInfo( new FakeInteractiveUserInfo() );

        File stagingRepo = new File( getBasedir(), STAGING_REPOSITORY );

        Repository sourceRepository = new Repository( "source", "file://" + stagingRepo );
        Repository targetRepository = new Repository( "target", "sftp://localhost:" + PORT);

        copier.copy( sourceRepository, targetRepository, version );

        String[] artifacts = {
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
            "maven-settings" };

        for (String artifact : artifacts)
        {
            testMavenArtifact( serverFileSystemRoot().toFile(), artifact );
        }

        // Test merging
        // Test MD5
        // Test SHA1
        // Test new artifacts are present
    }

    private void testMavenArtifact( File repo, String artifact )
        throws Exception
    {
        File basedir = new File( repo, "org/apache/maven/" + artifact );
        File versionDir = new File( basedir, version );
        assertTrue( versionDir.exists() );

        try ( Reader r = new FileReader( new File( basedir, RepositoryCopier.MAVEN_METADATA ) ) )
        {
            Metadata metadata = reader.read( r );

            // Make sure our new versions has been setup as the release.
            assertEquals( version, metadata.getVersioning().getRelease() );
            assertEquals( "20070327020553", metadata.getVersioning().getLastUpdated() );

            // Make sure we didn't whack old versions.
            List<String> versions = metadata.getVersioning().getVersions();
            assertTrue( versions.contains( "2.0.1" ) );
            assertTrue( versions.contains( "2.0.2" ) );
            assertTrue( versions.contains( "2.0.3" ) );
            assertTrue( versions.contains( "2.0.4" ) );
            assertTrue( versions.contains( "2.0.5" ) );
        }
    }
}
