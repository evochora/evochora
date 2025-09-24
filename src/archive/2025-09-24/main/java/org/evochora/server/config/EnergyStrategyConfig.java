package org.evochora.server.config;

import java.util.Map;

/**
 * Repräsentiert die Konfiguration für eine einzelne Energie-Strategie aus der JSON-Datei.
 * Dieses Record ist ein reiner Datencontainer (POJO) für die Deserialisierung.
 */
public record EnergyStrategyConfig(String type, Map<String, Object> params) {}