/*
 * Licensed to DuraSpace under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership.
 *
 * DuraSpace licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fcrepo.importexport.exporter;

import org.fcrepo.client.FcrepoClient;
import org.fcrepo.importexport.common.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.fcrepo.importexport.common.FcrepoConstants.BINARY_EXTENSION;
import static org.fcrepo.importexport.common.FcrepoConstants.CONTAINER;
import static org.fcrepo.importexport.common.FcrepoConstants.CONTAINS;
import static org.fcrepo.importexport.common.FcrepoConstants.NON_RDF_SOURCE;
import static org.fcrepo.importexport.test.util.ResponseMocker.mockGetResponse;
import static org.fcrepo.importexport.test.util.ResponseMocker.mockHeadResponse;
import static org.fcrepo.importexport.test.util.ResponseMocker.mockHeadResponseError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * @author bbpennel
 */
public class ResourceExporterTest {

    private static final URI REPOSITORY_ROOT = URI.create("http://localhost:8080/fcrepo/rest");
    private static final URI ROOT = REPOSITORY_ROOT.resolve("rest/root");
    private static final URI CHILD = URI.create(ROOT + "/child");
    private static final URI BINARY = URI.create(CHILD + "/file");

    @TempDir
    private Path tempDir;

    private FcrepoClient client;
    private ResourceExporter service;

    @BeforeEach
    public void setUp() {
        client = mock(FcrepoClient.class);
        service = new ResourceExporter(exportConfig(), client);
    }

    @Test
    public void exportsNestedContainerAndBinarySynchronously() throws Exception {
        mockContainer(ROOT, CHILD);
        mockContainer(CHILD, BINARY);
        mockBinary(BINARY, "binary content");

        service.export(ROOT, tempDir);

        assertTrue(Files.exists(tempDir.resolve("fcrepo/rest/root.nt")));
        assertTrue(Files.exists(tempDir.resolve("fcrepo/rest/root/child.nt")));
        assertEquals("binary content",
                Files.readString(tempDir.resolve("fcrepo/rest/root/child/file" + BINARY_EXTENSION)));
    }

    @Test
    public void propagatesDescendantRequestFailure() throws Exception {
        mockContainer(ROOT, CHILD);
        mockHeadResponseError(client, CHILD, 500);

        assertThrows(ExportException.class, () -> service.export(ROOT, tempDir));
    }

    @Test
    public void propagatesOutputFailure() throws Exception {
        mockContainer(ROOT, null);
        final Path outputFile = tempDir.resolve("not-a-directory");
        Files.writeString(outputFile, "content");

        assertThrows(ExportException.class, () -> service.export(ROOT, outputFile));
    }

    @Test
    public void supportsSimultaneousExportsWithoutCreatingWorkerThreads() throws Exception {
        final Set<String> requestThreads = ConcurrentHashMap.newKeySet();
        client = mock(FcrepoClient.class, withSettings().invocationListeners(report ->
                requestThreads.add(Thread.currentThread().getName())));
        service = new ResourceExporter(exportConfig(), client);
        final URI secondRoot = URI.create(REPOSITORY_ROOT + "/second");
        mockContainer(ROOT, null);
        mockContainer(secondRoot, null);
        clearInvocations(client);
        requestThreads.clear();

        try (var executor = Executors.newThreadPerTaskExecutor(
                Thread.ofPlatform().name("resource-export-test-", 0).factory())) {
            final var first = executor.submit(() -> {
                service.export(ROOT, tempDir.resolve("first"));
                return null;
            });
            final var second = executor.submit(() -> {
                service.export(secondRoot, tempDir.resolve("second"));
                return null;
            });
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        assertTrue(Files.exists(tempDir.resolve("first/fcrepo/rest/root.nt")));
        assertTrue(Files.exists(tempDir.resolve("second/fcrepo/rest/second.nt")));
        assertTrue(requestThreads.stream().allMatch(name -> name.startsWith("resource-export-test-")));
    }

    @Test
    public void synchronousExporterHasNoTaskManagerOrShutdownHook() {
        final Config config = new Config();
        config.setRepositoryRoot(REPOSITORY_ROOT);
        final Exporter exporter = new Exporter(config, client);

        assertTrue(exporter.isSynchronous());
    }

    private void mockContainer(final URI uri, final URI child) throws Exception {
        final List<URI> types = List.of(URI.create(CONTAINER.getURI()));
        final String body = child == null
                ? "<" + uri + "> <http://purl.org/dc/terms/title> \"title\" ."
                : "<" + uri + "> <" + CONTAINS.getURI() + "> <" + child + "> .";
        mockHeadResponse(client, uri, types, List.of(), null, null);
        mockGetResponse(client, uri, types, List.of(), null, null, body, "application/n-triples");
    }

    private Config exportConfig() {
        final Config config = new Config();
        config.setMode("export");
        config.setRepositoryRoot(REPOSITORY_ROOT);
        config.setUsername("user");
        config.setPassword("password");
        config.setIncludeBinaries(true);
        config.setStreaming(true);
        config.setRdfLanguage(Config.DEFAULT_STREAMING_RDF_LANG);
        config.setPredicates(new String[] { CONTAINS.getURI() });
        config.setAuditLog(true);
        return config;
    }

    private void mockBinary(final URI uri, final String body) throws Exception {
        final List<URI> types = List.of(URI.create(NON_RDF_SOURCE.getURI()));
        mockHeadResponse(client, uri, types, List.of(), null, null);
        mockGetResponse(client, uri, types, List.of(), null, null, body, "application/octet-stream");
    }
}
