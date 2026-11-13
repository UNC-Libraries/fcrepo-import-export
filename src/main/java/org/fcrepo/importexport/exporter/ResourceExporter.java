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

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Thread-safe service for synchronously exporting one resource tree per call. This is a synchronous entry point
 * for integrating the exporter into other java applications.
 *
 * @author bbpennel
 */
public final class ResourceExporter {

    private final Config config;
    private final FcrepoClient client;

    /**
     * Creates a service with caller-supplied export settings. Settings are
     * copied so subsequent caller changes do not affect this service.
     *
     * @param config export configuration, including repository and credentials
     */
    public ResourceExporter(final Config config) {
        this(config, client(config));
    }

    ResourceExporter(final Config config, final FcrepoClient client) {
        this.config = new Config(Objects.requireNonNull(config, "config"));
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Exports the root resource and all descendants discovered through ldp:contains.
     * This method creates no threads and returns only after all descendants finish.
     *
     * @param rootResource resource at which traversal starts
     * @param outputDirectory destination for the existing Fedora export layout
     * @throws ExportException when any request or output operation fails
     */
    public void export(final URI rootResource, final Path outputDirectory) throws ExportException {
        Objects.requireNonNull(rootResource, "rootResource");
        Objects.requireNonNull(outputDirectory, "outputDirectory");

        final Config operationConfig = new Config(config);
        operationConfig.setBaseDirectory(outputDirectory.toString());

        try {
            new Exporter(operationConfig, client).exportSynchronously(rootResource);
        } catch (Exception e) {
            throw new ExportException("Failed to export resource " + rootResource, e);
        }
    }

    private static FcrepoClient client(final Config config) {
        final FcrepoClient.FcrepoClientBuilder builder = FcrepoClient.client();
        if (config.getUsername() != null) {
            builder.credentials(config.getUsername(), config.getPassword());
        }
        return builder.build();
    }
}
