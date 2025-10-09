/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.api.resources;

import java.util.Map;

/**
 * Provides contextual information about how a resource is being used.
 * This is passed to a {@link IContextualResource} to create a tailored
 * {@link org.evochora.datapipeline.api.resources.IWrappedResource}.
 *
 * @param serviceName  The name of the service that is using the resource.
 * @param portName     The name of the resource port in the service's configuration.
 * @param usageType    A string defining how the resource is being used (e.g., "storage-read", "database-metadata").
 * @param resourceName The name of the underlying resource being accessed.
 * @param parameters   A map of additional parameters or configuration for the wrapper.
 */
public record ResourceContext(
        String serviceName,
        String portName,
        String usageType,
        String resourceName,
        Map<String, String> parameters
) {
}