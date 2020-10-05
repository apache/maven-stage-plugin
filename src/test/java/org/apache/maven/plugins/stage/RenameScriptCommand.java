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

import org.apache.commons.io.IOUtils;
import org.apache.sshd.server.Environment;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class RenameScriptCommand extends AbstractCommand
{
    private static final Pattern MV_PATTERN = Pattern.compile("mv (.+?) (.+?)");

    private final Path currentDirectory;
    private final Path scriptLocation;

    RenameScriptCommand( Path currentDirectory, String scriptLocation )
    {
        this.currentDirectory = currentDirectory;
        this.scriptLocation = currentDirectory.resolve( scriptLocation );
    }

    @Override
    public void start(Environment env)
    {
        try ( InputStream inputStream = new FileInputStream( scriptLocation.toFile() ) )
        {
            List<String> lines = IOUtils.readLines(inputStream, StandardCharsets.UTF_8);
            for ( String line : lines )
            {
                Matcher matcher = MV_PATTERN.matcher(line.trim());
                if ( matcher.matches() )
                {
                    Path fromFile = currentDirectory.resolve( matcher.group( 1 ) );
                    Path toFile = currentDirectory.resolve( matcher.group( 2 ) );
                    // Original script does not care whether this succeeds or not, so neither do we
                    fromFile.toFile().renameTo(toFile.toFile());
                }
            }
            exitCallback.onExit( 0 );
        }
        catch (IOException e)
        {
            exitCallback.onExit( 1, "Couldn't rename files" + e.getMessage() );
        }
    }

    @Override
    public void destroy()
    {

    }
}
