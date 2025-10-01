package org.evochora.datapipeline.api.services;

/**
 * A factory for creating IService instances on demand.
 * This is a functional interface whose instances can be created with lambdas.
 */
@FunctionalInterface
public interface IServiceFactory {
    /**
     * Creates a new instance of an IService.
     * @return A fresh, non-started IService instance.
     */
    IService create();
}