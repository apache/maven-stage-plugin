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

import org.apache.sshd.server.Command;
import org.apache.sshd.server.CommandFactory;
import org.apache.sshd.server.scp.UnknownCommand;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Command factory that mimics a handful of unix commands.
 */
class FakeUnixCommandFactory implements CommandFactory
{
    private static final Pattern UNZIP_PATTERN = Pattern.compile("^unzip -o -qq -d (.+?) (.+)");
    private static final Pattern RM_PATTERN = Pattern.compile("^rm -f (.+?)");
    private static final Pattern RENAME_SCRIPT_PATTERN = Pattern.compile("^cd /; sh (.+?)");

    private final Path fakeFileSystemRoot;

    public FakeUnixCommandFactory( Path fakeFileSystemRoot )
    {
        this.fakeFileSystemRoot = fakeFileSystemRoot;
    }

    @Override
    public Command createCommand(String command)
    {
        final Matcher unzipMatcher = UNZIP_PATTERN.matcher( command.trim() );
        final Matcher rmMatcher = RM_PATTERN.matcher( command.trim() );
        final Matcher renameScriptMatcher = RENAME_SCRIPT_PATTERN.matcher( command.trim() );

        if ( unzipMatcher.matches() )
        {
            final Path targetDir = getLocalPath( unzipMatcher.group( 1 ) );
            final Path zipFile = getLocalPath( unzipMatcher.group( 2 ) );
            return new UnzipCommand( zipFile, targetDir );
        }
        else if ( rmMatcher.matches() )
        {
            return new RmCommand( getLocalPath( rmMatcher.group( 1 ) ) );
        }
        else if ( renameScriptMatcher.matches() )
        {
            return new RenameScriptCommand( fakeFileSystemRoot, renameScriptMatcher.group( 1 ) );
        }
        return new UnknownCommand(command);
    }

    // Take a path e.g. /foo/bar which is absolute in terms of our fake file system root, and return the real absolute path
    private Path getLocalPath( String pathStr )
    {
        // the input has two leading slashes which Path interprets as a UNC path. It's not supposed to be, so fix it
        final Path path = Paths.get( pathStr.replaceAll( "/+", "/" ) );
        final Path root = Paths.get( "/" );
        return fakeFileSystemRoot.resolve( root.relativize( path ) );
    }
}
