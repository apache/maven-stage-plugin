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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.manager.WagonManager;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.apache.maven.wagon.CommandExecutionException;
import org.apache.maven.wagon.CommandExecutor;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.UnsupportedProtocolException;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.WagonException;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.repository.Repository;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * @author Jason van Zyl
 */
@Component(role = RepositoryCopier.class)
public class DefaultRepositoryCopier implements LogEnabled, RepositoryCopier {
    private MetadataXpp3Reader reader = new MetadataXpp3Reader();

    private MetadataXpp3Writer writer = new MetadataXpp3Writer();

    @Requirement
    private WagonManager wagonManager;

    private Logger logger;

    public void copy(Repository sourceRepository, Repository targetRepository, String version)
            throws WagonException, IOException {
        String prefix = "staging-plugin";
        String fileName = prefix + "-" + version + ".zip";
        String tempDir = System.getProperty("java.io.tmpdir");
        logger.debug("Writing all output to " + tempDir);

        File renameScript = new File(tempDir, prefix + "-" + version + "-rename.sh");
        File basedir = prepareWorkspace(tempDir, prefix, version);

        Wagon sourceWagon = connectSourceRepository(sourceRepository);
        List<String> files = downloadSourceRepositoryFiles(sourceWagon, basedir);

        Wagon targetWagon = connectTargetRepository(targetRepository);
        downloadAndMergeMetadata(targetWagon, files, basedir);

        File archive = createZipFile(fileName, tempDir, basedir, renameScript, files, version);

        uploadToTargetRepository(targetWagon, archive, fileName);

        logger.info("Unpacking zip file on the target machine.");
    }

    private File prepareWorkspace(String tempDir, String prefix, String version) throws IOException {
        File basedir = new File(tempDir, prefix + "-" + version);
        FileUtils.deleteDirectory(basedir);
        basedir.mkdirs();
        logger.info("Downloading files from the source repository to: " + basedir);
        return basedir;
    }

    private Wagon connectSourceRepository(Repository sourceRepository) throws WagonException {
        Wagon sourceWagon = wagonManager.getWagon(sourceRepository);
        AuthenticationInfo sourceAuth = wagonManager.getAuthenticationInfo(sourceRepository.getId());
        sourceWagon.connect(sourceRepository, sourceAuth);
        logger.info("Looking for files in the source repository.");
        return sourceWagon;
    }

    private List<String> downloadSourceRepositoryFiles(Wagon sourceWagon, File basedir)
            throws WagonException, IOException {
        List<String> files = new ArrayList<>();
        scan(sourceWagon, "", files);

        for (String file : files) {
            if (file.contains(".svn")) {
                continue;
            }
            File localFile = new File(basedir, file);
            FileUtils.forceMkdirParent(localFile);
            logger.info("Downloading file from the source repository: " + file);
            sourceWagon.get(file, localFile);
        }
        sourceWagon.disconnect();
        return files;
    }

    private Wagon connectTargetRepository(Repository targetRepository) throws WagonException {
        Wagon targetWagon = wagonManager.getWagon(targetRepository);
        if (!(targetWagon instanceof CommandExecutor)) {
            throw new CommandExecutionException("Wagon class '"
                    + targetWagon.getClass().getName() + "' in use for target repository is not a CommandExecutor");
        }
        AuthenticationInfo targetAuth = wagonManager.getAuthenticationInfo(targetRepository.getId());
        targetWagon.connect(targetRepository, targetAuth);
        logger.info("Downloading metadata from the target repository.");
        return targetWagon;
    }

    private void downloadAndMergeMetadata(Wagon targetWagon, List<String> files, File basedir)
            throws IOException, WagonException {
        for (String file : files) {
            if (file.startsWith("/")) {
                file = file.substring(1);
            }
            if (file.endsWith(MAVEN_METADATA)) {
                File metadataFile = new File(basedir, file + IN_PROCESS_MARKER);
                try {
                    targetWagon.get(file, metadataFile);
                } catch (ResourceDoesNotExistException e) {
                    continue; // Skip metadata merging if it doesn't exist on the target side
                }
                try {
                    mergeMetadata(metadataFile);
                } catch (XmlPullParserException e) {
                    throw new IOException("Metadata file is corrupt " + file + " Reason: " + e.getMessage());
                }
            }
        }
    }

    private File createZipFile(
            String fileName, String tempDir, File basedir, File renameScript, List<String> files, String version)
            throws IOException {
        logger.info("Creating zip file.");
        File archive = new File(tempDir, fileName);
        Set<String> moveCommands = new TreeSet<>();

        try (OutputStream os = new FileOutputStream(archive);
                ZipOutputStream zos = new ZipOutputStream(os)) {
            scanDirectory(basedir, basedir, zos, version, moveCommands);

            logger.info("Creating rename script.");
            try (PrintWriter writer = new PrintWriter(new FileWriter(renameScript))) {
                for (Object moveCommandObj : moveCommands) {
                    String moveCommand = (String) moveCommandObj;
                    writer.print(moveCommand + "\n"); // Explicit Unix-style line ending
                }
            }

            ZipEntry entry = new ZipEntry(renameScript.getName());
            zos.putNextEntry(entry);

            try (InputStream is = new FileInputStream(renameScript)) {
                IOUtils.copy(is, zos);
            }
        }
        return archive;
    }

    private void uploadToTargetRepository(Wagon targetWagon, File archive, String fileName) throws WagonException {
        logger.info("Uploading zip file to the target repository.");
        targetWagon.put(archive, fileName);
        targetWagon.disconnect();
    }

    private void scanDirectory(File basedir, File dir, ZipOutputStream zos, String version, Set<String> moveCommands)
            throws IOException {
        if (dir == null) {
            return;
        }

        File[] files = dir.listFiles();

        for (File f : files) {
            if (f.isDirectory()) {
                if (f.getName().equals(".svn")) {
                    continue;
                }

                if (f.getName().endsWith(version)) {
                    String s = f.getAbsolutePath()
                            .substring(basedir.getAbsolutePath().length() + 1);
                    s = s.replace('\\', '/');

                    moveCommands.add("mv " + s + IN_PROCESS_MARKER + " " + s);
                }

                scanDirectory(basedir, f, zos, version, moveCommands);
            } else {
                try (InputStream is = new FileInputStream(f)) {
                    String s = f.getAbsolutePath()
                            .substring(basedir.getAbsolutePath().length() + 1);
                    s = s.replace('\\', '/');

                    // We are marking any version directories with the in-process flag so that
                    // anything being unpacked on the target side will not be recognized by Maven
                    // and so users cannot download partially uploaded files.

                    String vtag = "/" + version;

                    s = s.replace(vtag + "/", vtag + IN_PROCESS_MARKER + "/");

                    ZipEntry e = new ZipEntry(s);

                    zos.putNextEntry(e);

                    IOUtils.copy(is, zos);

                    int idx = s.indexOf(IN_PROCESS_MARKER);

                    if (idx > 0) {
                        String d = s.substring(0, idx);

                        moveCommands.add("mv " + d + IN_PROCESS_MARKER + " " + d);
                    }
                }
            }
        }
    }

    private void mergeMetadata(File existingMetadata) throws IOException, XmlPullParserException {
        // Existing Metadata in target stage

        Reader existingMetadataReader = new FileReader(existingMetadata);

        Metadata existing = reader.read(existingMetadataReader);

        // Staged Metadata

        File stagedMetadataFile = new File(existingMetadata.getParentFile(), MAVEN_METADATA);

        Reader stagedMetadataReader = new FileReader(stagedMetadataFile);

        Metadata staged = reader.read(stagedMetadataReader);

        // Merge

        existing.merge(staged);

        try (Writer writer = new FileWriter(existingMetadata)) {
            this.writer.write(writer, existing);
        }

        stagedMetadataReader.close();
        existingMetadataReader.close();

        // Mark all metadata as in-process and regenerate the checksums as they will be different
        // after the merger

        try {
            File newMd5 = new File(existingMetadata.getParentFile(), MAVEN_METADATA + ".md5" + IN_PROCESS_MARKER);

            FileUtils.writeStringToFile(newMd5, checksum(existingMetadata, MD5));

            File oldMd5 = new File(existingMetadata.getParentFile(), MAVEN_METADATA + ".md5");

            oldMd5.delete();

            File newSha1 = new File(existingMetadata.getParentFile(), MAVEN_METADATA + ".sha1" + IN_PROCESS_MARKER);

            FileUtils.writeStringToFile(newSha1, checksum(existingMetadata, SHA1));

            File oldSha1 = new File(existingMetadata.getParentFile(), MAVEN_METADATA + ".sha1");

            oldSha1.delete();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        // We have the new merged copy so we're good

        stagedMetadataFile.delete();
    }

    private String checksum(File file, String type) throws IOException, NoSuchAlgorithmException {
        MessageDigest md5 = MessageDigest.getInstance(type);

        try (InputStream is = new FileInputStream(file)) {
            // CHECKSTYLE_OFF: MagicNumber
            byte[] buf = new byte[8192];
            // CHECKSTYLE_ON: MagicNumber

            int i;

            while ((i = is.read(buf)) >= 0) {
                md5.update(buf, 0, i);
            }
        }

        return encode(md5.digest());
    }

    protected String encode(byte[] binaryData) {
        // CHECKSTYLE_OFF: MagicNumber
        if (binaryData.length != 16 && binaryData.length != 20) {
            int bitLength = binaryData.length * 8;
            throw new IllegalArgumentException("Unrecognised length for binary data: " + bitLength + " bits");
        }
        // CHECKSTYLE_ON: MagicNumber

        String retValue = "";

        for (byte aBinaryData : binaryData) {
            // CHECKSTYLE_OFF: MagicNumber
            String t = Integer.toHexString(aBinaryData & 0xff);
            // CHECKSTYLE_ON: MagicNumber

            if (t.length() == 1) {
                retValue += ("0" + t);
            } else {
                retValue += t;
            }
        }

        return retValue.trim();
    }

    private void scan(Wagon wagon, String basePath, List<String> collected) {
        try {
            List<String> files = wagon.getFileList(basePath);

            if (files.isEmpty()) {
                collected.add(basePath);
            } else {
                basePath = basePath + "/";
                for (String file : files) {
                    logger.info("Found file in the source repository: " + file);
                    scan(wagon, basePath + file, collected);
                }
            }
        } catch (TransferFailedException e) {
            throw new RuntimeException(e);
        } catch (ResourceDoesNotExistException e) {
            // is thrown when calling getFileList on a file
            collected.add(basePath);
        } catch (AuthorizationException e) {
            throw new RuntimeException(e);
        }
    }

    protected List<String> scanForArtifactPaths(ArtifactRepository repository) {
        try {
            Wagon wagon = wagonManager.getWagon(repository.getProtocol());
            Repository artifactRepository = new Repository(repository.getId(), repository.getUrl());
            wagon.connect(artifactRepository);
            List<String> collected = new ArrayList<String>();
            scan(wagon, "/", collected);
            wagon.disconnect();

            return collected;

        } catch (UnsupportedProtocolException e) {
            throw new RuntimeException(e);
        } catch (ConnectionException e) {
            throw new RuntimeException(e);
        } catch (AuthenticationException e) {
            throw new RuntimeException(e);
        }
    }

    public void enableLogging(Logger logger) {
        this.logger = logger;
    }
}
