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

import org.apache.sshd.server.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * SSHD command which mimics unix unzip.
 */
public class UnzipCommand extends AbstractCommand
{
    private final Path fileToUnzip;
    private final Path target;

    public UnzipCommand( Path fileToUnzip, Path target )
    {
        this.fileToUnzip = fileToUnzip;
        this.target = target;
    }

    @Override
    public void start( Environment environment )
    {
        try
        {
            try ( ZipInputStream zis = new ZipInputStream( new FileInputStream( fileToUnzip.toFile() ) ) )
            {
                ZipEntry zipEntry;
                while ( (zipEntry = zis.getNextEntry()) != null )
                {
                    final File newFile = target.resolve( zipEntry.getName() ).toFile();

                    if ( zipEntry.isDirectory() )
                    {
                        ensureDirectoryExists( newFile );
                    }
                    else
                    {
                        ensureDirectoryExists( newFile.getParentFile() );
                        try (FileOutputStream fos = new FileOutputStream( newFile ))
                        {
                            final byte[] buffer = new byte[1024];
                            int bytesRead;
                            while ( ( bytesRead = zis.read( buffer ) ) > 0)
                            {
                                fos.write( buffer, 0, bytesRead );
                            }
                        }
                    }
                    zis.closeEntry();
                }
            }
            exitCallback.onExit( 0 );
        }
        catch (IOException e)
        {
            // 3 being "severe error"
            exitCallback.onExit( 3, e.getMessage() );
        }
    }

    private void ensureDirectoryExists( File directory )
    {
        if ( !directory.exists() )
        {
            if ( !directory.mkdirs() )
            {
                throw new RuntimeException("Couldn't make directories " + directory);
            }
        }
    }

    @Override
    public void destroy()
    {

    }
}
