package org.evochora.runtime.internal.services;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eine zentrale, VM-weite Registry zur Verwaltung von Parameterbindungen für CALL-Instruktionen.
 * <p>
 * Diese Klasse entkoppelt die Speicherung der Bindungen von den Instruktions-Klassen.
 * Sie verwendet zwei Mechanismen zur Auflösung:
 * 1. Eine primäre Map, die auf der linearen Adresse der CALL-Instruktion basiert (effizient und eindeutig).
 * 2. Eine Fallback-Map, die auf der absoluten Weltkoordinate basiert (für dynamisch generierten Code).
 * <p>
 * Diese Registry ist als Singleton implementiert und thread-sicher.
 */
public final class CallBindingRegistry {

    private static final CallBindingRegistry INSTANCE = new CallBindingRegistry();

    private final Map<Integer, int[]> bindingsByLinearAddress = new ConcurrentHashMap<>();
    private final Map<List<Integer>, int[]> bindingsByAbsoluteCoord = new ConcurrentHashMap<>();

    private CallBindingRegistry() {
        // Privater Konstruktor, um Instanziierung zu verhindern.
    }

    public static CallBindingRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registriert eine Parameterbindung für eine CALL-Instruktion an einer bestimmten linearen Adresse.
     *
     * @param linearAddress Die lineare Adresse der CALL-Instruktion.
     * @param drIds         Ein Array von Register-IDs, die gebunden werden sollen.
     */
    public void registerBindingForLinearAddress(int linearAddress, int[] drIds) {
        bindingsByLinearAddress.put(linearAddress, Arrays.copyOf(drIds, drIds.length));
    }

    /**
     * Registriert eine Parameterbindung für eine CALL-Instruktion an einer bestimmten absoluten Koordinate.
     *
     * @param absoluteCoord Die absolute Weltkoordinate der CALL-Instruktion.
     * @param drIds         Ein Array von Register-IDs, die gebunden werden sollen.
     */
    public void registerBindingForAbsoluteCoord(int[] absoluteCoord, int[] drIds) {
        List<Integer> key = Arrays.stream(absoluteCoord).boxed().toList();
        bindingsByAbsoluteCoord.put(key, Arrays.copyOf(drIds, drIds.length));
    }

    /**
     * Ruft die Parameterbindung für eine gegebene lineare Adresse ab.
     *
     * @param linearAddress Die lineare Adresse.
     * @return Das Array der gebundenen Register-IDs oder null, wenn keine Bindung gefunden wurde.
     */
    public int[] getBindingForLinearAddress(int linearAddress) {
        return bindingsByLinearAddress.get(linearAddress);
    }

    /**
     * Ruft die Parameterbindung für eine gegebene absolute Koordinate ab.
     *
     * @param absoluteCoord Die absolute Koordinate.
     * @return Das Array der gebundenen Register-IDs oder null, wenn keine Bindung gefunden wurde.
     */
    public int[] getBindingForAbsoluteCoord(int[] absoluteCoord) {
        List<Integer> key = Arrays.stream(absoluteCoord).boxed().toList();
        return bindingsByAbsoluteCoord.get(key);
    }
}