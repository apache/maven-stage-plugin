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

import java.nio.file.Path;

class RmCommand extends AbstractCommand
{
    private final Path fileToRemove;

    RmCommand( Path fileToRemove )
    {
        this.fileToRemove = fileToRemove;
    }

    @Override
    public void start(Environment env)
    {
        boolean delete = fileToRemove.toFile().delete();
        if ( !delete )
        {
            // 1 being coreutil's not permitted
            exitCallback.onExit( 1, "Couldn't delete " + fileToRemove);
        }
        exitCallback.onExit( 0 );
    }

    @Override
    public void destroy()
    {

    }
}
