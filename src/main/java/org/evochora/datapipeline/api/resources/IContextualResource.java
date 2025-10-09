/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.api.resources;

/**
 * An interface for resources that can provide wrapped, context-specific instances of themselves.
 * <p>
 * This allows a single shared resource (e.g., a database connection pool) to be used by
 * multiple consumers (e.g., different indexer services), each with its own isolated,
 * monitored wrapper.
 */
public interface IContextualResource extends IResource {

    /**
     * Returns a wrapped instance of the resource tailored to the provided context.
     *
     * @param context The {@link ResourceContext} defining the consumer and usage type.
     * @return An {@link IWrappedResource} instance.
     */
    IWrappedResource getWrappedResource(ResourceContext context);
}