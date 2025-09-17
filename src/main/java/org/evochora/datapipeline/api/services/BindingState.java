package org.evochora.datapipeline.api.services;

/**
 * Represents the state of a channel binding.
 */
public enum BindingState {
    /**
     * The binding is actively transferring data.
     */
    ACTIVE,

    /**
     * The binding is waiting for data to become available or for space to become available.
     */
    WAITING
}
