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

public class UnzipCommand extends AbstractCommand
{
    private final Path fileToUnzip;
    private final Path target;

    public UnzipCommand( Path fileSystemRoot, String fileToUnzip, String targetDir )
    {
        this.fileToUnzip = fileSystemRoot.resolve( fileToUnzip );
        this.target = fileSystemRoot.resolve( targetDir );
    }

    @Override
    public void start( Environment environment )
    {
        System.out.println( "MB started unzipping" );

        try
        {
            try ( ZipInputStream zis = new ZipInputStream( new FileInputStream( fileToUnzip.toFile() ) ) )
            {
                ZipEntry zipEntry = zis.getNextEntry();
                while ( zipEntry != null )
                {
                    final File newFile = target.resolve( zipEntry.getName() ).toFile();
                    try (FileOutputStream fos = new FileOutputStream( newFile ))
                    {
                        final byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ( ( bytesRead = zis.read( buffer ) ) > 0)
                        {
                            fos.write( buffer, 0, bytesRead );
                        }
                    }
                    zipEntry = zis.getNextEntry();
                }
                zis.closeEntry();
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void destroy()
    {

    }
}
