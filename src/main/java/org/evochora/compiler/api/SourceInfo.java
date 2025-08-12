package org.evochora.compiler.api;

/**
 * Eine reine Datenklasse, die eine Position im Quellcode repräsentiert.
 * Sie ist Teil der öffentlichen Compiler-API und frei von Implementierungsdetails.
 *
 * @param fileName Die Datei, in der sich der Code befindet.
 * @param lineNumber Die Zeilennummer.
 * @param lineContent Der Inhalt der Zeile.
 */
public record SourceInfo(String fileName, int lineNumber, String lineContent) {}
