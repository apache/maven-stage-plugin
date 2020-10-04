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

import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.wagon.repository.Repository;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.CommandFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.UserAuth;
import org.apache.sshd.server.auth.UserAuthNoneFactory;
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.scp.ScpCommandFactory;
import org.apache.sshd.server.scp.UnknownCommand;
import org.apache.sshd.server.subsystem.sftp.SftpSubsystemFactory;
import org.codehaus.plexus.PlexusTestCase;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** @author Jason van Zyl */
public class RepositoryCopierTest
    extends PlexusTestCase
{
    private final String version = "2.0.6";

    private final MetadataXpp3Reader reader = new MetadataXpp3Reader();

    private SshServer sshd;

    public void setUp() throws Exception
    {
        super.setUp();

        final Path serverFileSystemRoot = new File( getBasedir(), "src/test/target-repository" ).toPath();

        sshd = SshServer.setUpDefaultServer();
        sshd.setPort( 3542 );
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        sshd.setFileSystemFactory(new VirtualFileSystemFactory() {
            @Override
            public Path getDefaultHomeDir()
            {
                return serverFileSystemRoot;
            }
        });

        List<NamedFactory<UserAuth>> userAuthFactories = new ArrayList<>();
        userAuthFactories.add(new UserAuthNoneFactory());
        sshd.setUserAuthFactories(userAuthFactories);
        sshd.setPublickeyAuthenticator(AcceptAllPublickeyAuthenticator.INSTANCE);

        final ScpCommandFactory scpCommandFactory = new ScpCommandFactory();
        scpCommandFactory.setDelegateCommandFactory(
            new CommandFactory()
            {
                @Override
                public Command createCommand(String command)
                {
                    final Pattern pattern = Pattern.compile("^unzip -o -qq -d (.+?) (.+)");
                    final Matcher matcher = pattern.matcher( command.trim() );

                    if ( matcher.matches() )
                    {
                        System.out.println( " MB matches! " );

                        final String targetDir = matcher.group( 1 );
                        final String zipFile = matcher.group( 2 );
                        return new UnzipCommand( serverFileSystemRoot, targetDir, zipFile );
                    }
                    return new UnknownCommand(command);
                }
            }
        );
        sshd.setCommandFactory(scpCommandFactory);

        List<NamedFactory<Command>> namedFactoryList = new ArrayList<>();
        namedFactoryList.add(new SftpSubsystemFactory());
        sshd.setSubsystemFactories(namedFactoryList);
        try
        {
            sshd.start();
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void tearDown() throws Exception
    {
        super.tearDown();
        sshd.stop();
    }

    public void testCopy() throws Exception
    {
        DefaultRepositoryCopier copier = (DefaultRepositoryCopier) container.lookup( RepositoryCopier.ROLE );
        copier.overrideInteractiveUserInfo( new FakeInteractiveUserInfo() );

        File stagingRepo = new File( getBasedir(), "src/test/staging-repository" );

        Repository sourceRepository = new Repository( "source", "file://" + stagingRepo );
        Repository targetRepository = new Repository( "target", "sftp://127.0.0.1:" + 3542 );

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
            ///testMavenArtifact(targetRepo, artifact);
        }

        // leave something behind to clean it up.
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
        //assertTrue( versionDir.exists() );

        Reader r = new FileReader( new File( basedir, RepositoryCopier.MAVEN_METADATA) );
        Metadata metadata = reader.read( r );

        // Make sure our new versions has been setup as the release.
        //assertEquals( version, metadata.getVersioning().getRelease() );
        //assertEquals( "20070327020553", metadata.getVersioning().getLastUpdated() );

        // Make sure we didn't whack old versions.
        List versions = metadata.getVersioning().getVersions();
        //assertTrue( versions.contains( "2.0.1" ) );
        //assertTrue( versions.contains( "2.0.2" ) );
        //assertTrue( versions.contains( "2.0.3" ) );
        //assertTrue( versions.contains( "2.0.4" ) );
        //assertTrue( versions.contains( "2.0.5" ) );
        r.close();
    }
}
