package org.evochora.datapipeline.api.resources.database.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * A single entry in an organism's ancestry chain.
 *
 * @param organismId The ancestor's organism ID.
 * @param genomeHash The ancestor's genome hash at birth. Serialized as string to preserve 64-bit precision in JSON.
 */
public record LineageEntry(int organismId, @JsonSerialize(using = ToStringSerializer.class) long genomeHash) {}
