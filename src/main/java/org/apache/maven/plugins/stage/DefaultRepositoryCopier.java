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

import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.manager.WagonManager;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.apache.maven.wagon.CommandExecutor;
import org.apache.maven.wagon.CommandExecutionException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.WagonException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.repository.Repository;
import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * @author Jason van Zyl
 * @plexus.component
 */
public class DefaultRepositoryCopier
    implements LogEnabled, RepositoryCopier
{
    private MetadataXpp3Reader metadataReader = new MetadataXpp3Reader();

    private MetadataXpp3Writer metadataWriter = new MetadataXpp3Writer();

    /** @plexus.requirement */
    private WagonManager wagonManager;

    private Logger logger;

    public void copy( Repository sourceRepository, Repository targetRepository, String version )
        throws WagonException, IOException
    {
        String prefix = "staging-plugin";

        String fileName = prefix + "-" + version + ".zip";

        String tempdir = System.getProperty( "java.io.tmpdir" );

        logger.debug( "Writing all output to " + tempdir );

        // Create the renameScript script

        String renameScriptName = prefix + "-" + version + "-rename.sh";

        File renameScript = new File( tempdir, renameScriptName );

        // Work directory

        File basedir = new File( tempdir, prefix + "-" + version );

        FileUtils.deleteDirectory( basedir );
        Files.createDirectories( basedir.toPath() );

        Wagon sourceWagon = wagonManager.getWagon( sourceRepository );
        AuthenticationInfo sourceAuth = wagonManager.getAuthenticationInfo( sourceRepository.getId() );

        sourceWagon.connect( sourceRepository, sourceAuth );

        logger.info( "Looking for files in the source repository." );

        List<String> files = new ArrayList<>();

        scan( sourceWagon, "", files );

        logger.info( "Downloading files from the source repository to: " + basedir );

        for ( String s : files )
        {

            if ( s.contains( ".svn" ) )
            {
                continue;
            }

            File f = new File( basedir, s );

            logger.info( "Downloading file from the source repository: " + s );

            sourceWagon.get( s, f );
        }

        // ----------------------------------------------------------------------------
        // Now all the files are present locally and now we are going to grab the
        // metadata files from the targetRepositoryUrl and pull those down locally
        // so that we can merge the metadata.
        // ----------------------------------------------------------------------------

        logger.info( "Downloading metadata from the target repository." );

        Wagon targetWagon = wagonManager.getWagon( targetRepository );

        if ( ! ( targetWagon instanceof CommandExecutor ) )
        {
            throw new CommandExecutionException( "Wagon class '" + targetWagon.getClass().getName()
                + "' in use for target repository is not a CommandExecutor" );
        }

        AuthenticationInfo targetAuth = wagonManager.getAuthenticationInfo( targetRepository.getId() );

        targetWagon.connect( targetRepository, targetAuth );

        File archive = new File( tempdir, fileName );

        for ( String s : files )
        {

            if ( s.startsWith( "/" ) )
            {
                s = s.substring( 1 );
            }

            if ( s.endsWith( MAVEN_METADATA ) )
            {
                File emf = new File( basedir, s + IN_PROCESS_MARKER );

                try
                {
                    targetWagon.get( s, emf );
                }
                catch ( ResourceDoesNotExistException e )
                {
                    // We don't have an equivalent on the targetRepositoryUrl side because we have something
                    // new on the sourceRepositoryUrl side so just skip the metadata merging.

                    continue;
                }

                try
                {
                    mergeMetadata( emf );
                }
                catch ( XmlPullParserException e )
                {
                    throw new IOException( "Metadata file is corrupt " + s + " Reason: " + e.getMessage() );
                }
            }
        }

        Set<String> moveCommands = new TreeSet<>();
        
        try ( Writer rw = Files.newBufferedWriter( renameScript.toPath(), StandardCharsets.UTF_8 ) )
        {
    
            // ----------------------------------------------------------------------------
            // Create the Zip file that we will deploy to the targetRepositoryUrl stage
            // ----------------------------------------------------------------------------
    
            logger.info( "Creating zip file." );
        
            try ( ZipOutputStream zos = new ZipOutputStream( new FileOutputStream( archive ) ) )
            {
                scanDirectory( basedir, basedir, zos, version, moveCommands );
        
                // ----------------------------------------------------------------------------
                // Create the renameScript script. This is as atomic as we can
                // ----------------------------------------------------------------------------
        
                logger.info( "Creating rename script." );
        
                for ( String moveCommand : moveCommands )
                {
                    rw.write( moveCommand + "\n" );
                }
                ZipEntry e = new ZipEntry( renameScript.getName() );
    
                zos.putNextEntry( e );
    
                Files.copy( renameScript.toPath(), zos );
            }

            sourceWagon.disconnect();
        }

        // Push the Zip to the target system
        logger.info( "Uploading zip file to the target repository." );

        targetWagon.put( archive, fileName );

        logger.info( "Unpacking zip file on the target machine." );

        String targetRepoBaseDirectory = targetRepository.getBasedir();

        // We use the super quiet option here as all the noise seems to kill/stall the connection
        String unzipCommand =
            "unzip -o -qq -d " + targetRepoBaseDirectory + " " + targetRepoBaseDirectory + "/" + fileName;

        CommandExecutor commandExecutor = (CommandExecutor) targetWagon;
        commandExecutor.executeCommand( unzipCommand );

        logger.info( "Deleting zip file from the target repository." );

        String rmCommand = "rm -f " + targetRepoBaseDirectory + "/" + fileName;

        commandExecutor.executeCommand( rmCommand );

        logger.info( "Running rename script on the target machine." );

        String renameCommand = "cd " + targetRepoBaseDirectory + "; sh " + renameScriptName;

        commandExecutor.executeCommand( renameCommand );

        logger.info( "Deleting rename script from the target repository." );

        String deleteCommand = "rm -f " + targetRepoBaseDirectory + "/" + renameScriptName;

        commandExecutor.executeCommand( deleteCommand );

        targetWagon.disconnect();
    }

    private void scanDirectory( File basedir, File dir, ZipOutputStream zos, String version, Set<String> moveCommands )
        throws IOException
    {
        if ( dir == null )
        {
            return;
        }

        File[] files = dir.listFiles();

        for ( File f : files )
        {
            if ( f.isDirectory() )
            {
                if ( f.getName().equals( ".svn" ) )
                {
                    continue;
                }

                if ( f.getName().endsWith( version ) )
                {
                    String s = f.getAbsolutePath().substring( basedir.getAbsolutePath().length() + 1 );
                    s = s.replace( "\\", "/" );

                    moveCommands.add( "mv " + s + IN_PROCESS_MARKER + " " + s );
                }

                scanDirectory( basedir, f, zos, version, moveCommands );
            }
            else
            {
                String s = f.getAbsolutePath().substring( basedir.getAbsolutePath().length() + 1 );
                s = s.replace( "\\", "/" );

                // We are marking any version directories with the in-process flag so that
                // anything being unpacked on the target side will not be recognized by Maven
                // and so users cannot download partially uploaded files.

                String vtag = "/" + version;

                s = s.replace( vtag + "/", vtag + IN_PROCESS_MARKER + "/" );

                ZipEntry e = new ZipEntry( s );

                zos.putNextEntry( e );

                Files.copy( f.toPath(), zos );

                int idx = s.indexOf( IN_PROCESS_MARKER );

                if ( idx > 0 )
                {
                    String d = s.substring( 0, idx );

                    moveCommands.add( "mv " + d + IN_PROCESS_MARKER + " " + d );
                }
            }
        }
    }

    private void mergeMetadata( File existingMetadata )
        throws IOException, XmlPullParserException
    {
        // Existing Metadata in target stage
        try ( Reader existingMetadataReader = Files.newBufferedReader( 
                existingMetadata.toPath(), StandardCharsets.UTF_8 ) )
        {
            Metadata existing = metadataReader.read( existingMetadataReader );
    
            // Staged Metadata  
            File stagedMetadataFile = new File( existingMetadata.getParentFile(), MAVEN_METADATA );
    
            try ( Reader stagedMetadataReader = 
                  Files.newBufferedReader( stagedMetadataFile.toPath(), StandardCharsets.UTF_8 ) )
            {
                Metadata staged = metadataReader.read( stagedMetadataReader );
                existing.merge( staged );
            }
            
            try ( Writer writer = Files.newBufferedWriter( existingMetadata.toPath(), StandardCharsets.UTF_8 ) )
            {
                metadataWriter.write( writer, existing );
            }
            
            // Mark all metadata as in-process and regenerate the checksums as they will be different
            // after the merger
            try
            {
                File newMd5 = new File( existingMetadata.getParentFile(), MAVEN_METADATA + ".md5" + IN_PROCESS_MARKER );
    
                FileUtils.write( newMd5, checksum( existingMetadata, MD5 ), StandardCharsets.UTF_8 );
    
                File oldMd5 = new File( existingMetadata.getParentFile(), MAVEN_METADATA + ".md5" );
    
                oldMd5.delete();
    
                File newSha1 = new File( 
                    existingMetadata.getParentFile(), MAVEN_METADATA + ".sha1" + IN_PROCESS_MARKER );
    
                FileUtils.write( newSha1, checksum( existingMetadata, SHA1 ), StandardCharsets.UTF_8 );
    
                File oldSha1 = new File( existingMetadata.getParentFile(), MAVEN_METADATA + ".sha1" );
    
                oldSha1.delete();
            }
            catch ( NoSuchAlgorithmException e )
            {
                throw new RuntimeException( e );
            }
    
            // We have the new merged copy so we're good
            stagedMetadataFile.delete();
        }
    }

    private String checksum( File file, String type )
        throws IOException, NoSuchAlgorithmException
    {
        MessageDigest md5 = MessageDigest.getInstance( type );
        // CHECKSTYLE_OFF: MagicNumber
        byte[] buf = new byte[8192];
        // CHECKSTYLE_ON: MagicNumber

        try ( InputStream is = new FileInputStream( file ) )
        {
            int i;
            while ( ( i = is.read( buf ) ) >= 0 )
            {
                md5.update( buf, 0, i );
            }
        }

        return encode( md5.digest() );
    }

    protected String encode( byte[] binaryData )
    {
        // CHECKSTYLE_OFF: MagicNumber
        if ( binaryData.length != 16 && binaryData.length != 20 )
        {
            int bitLength = binaryData.length * 8;
            throw new IllegalArgumentException( "Unrecognised length for binary data: " + bitLength + " bits" );
        }
        // CHECKSTYLE_ON: MagicNumber

        String retValue = "";

        for ( byte aBinaryData : binaryData )
        {
            // CHECKSTYLE_OFF: MagicNumber
            String t = Integer.toHexString( aBinaryData & 0xff );
            // CHECKSTYLE_ON: MagicNumber

            if ( t.length() == 1 )
            {
                retValue += ( "0" + t );
            }
            else
            {
                retValue += t;
            }
        }

        return retValue.trim();
    }

    private void scan( Wagon wagon, String basePath, List<String> collected )
        throws TransferFailedException, AuthorizationException
    {
        try
        {
            List<String> files = wagon.getFileList( basePath );

            if ( files.isEmpty() )
            {
                collected.add( basePath );
            }
            else
            {
                basePath = basePath + "/";
                for ( String file : files )
                {
                    logger.info( "Found file in the source repository: " + file );
                    scan( wagon, basePath + file, collected );
                }
            }
        }
        catch ( ResourceDoesNotExistException e )
        {
            // thrown when calling getFileList on a file
            collected.add( basePath );
        }
    }

    protected List<String> scanForArtifactPaths( ArtifactRepository repository )
        throws WagonException
    {
        Wagon wagon = wagonManager.getWagon( repository.getProtocol() );
        Repository artifactRepository = new Repository( repository.getId(), repository.getUrl() );
        wagon.connect( artifactRepository );
        List<String> collected = new ArrayList<>();
        scan( wagon, "/", collected );
        wagon.disconnect();

        return collected;
    }

    public void enableLogging( Logger logger )
    {
        this.logger = logger;
    }
}
