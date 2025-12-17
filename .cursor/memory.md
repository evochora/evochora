Zusammenfassung der Änderungen an der Molekülkodierung:
- Protobuf: 3 Felder -> 1 Feld (`molecule_data`)
- Backend: Packen/Entpacken implementiert in Reader, Writer, Plugins, CLI
- Tests: `CellStateTestHelper` eingeführt, 10 Test-Dateien angepasst
- Frontend: Keine Änderung nötig (nutzt entkoppelte DTOs)
- Status: Kompiliert erfolgreich (Produktion & Tests)
