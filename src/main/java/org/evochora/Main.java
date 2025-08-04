// src/main/java/org/evochora/Main.java
package org.evochora;

import javafx.application.Application;
import javafx.stage.Stage;
import org.evochora.ui.AppView; // HINZUGEFÜGT: Import für AppView
import org.evochora.world.World; // HINZUGEFÜGT: World Import

public class Main extends Application {

    // GEÄNDERT: Instanzvariablen simulation, renderer, selectedOrganism, canvas entfernt
    // Sie werden nun von AppView verwaltet.

    @Override
    public void start(Stage primaryStage) {
        try {
            // Initialisierung der Welt und Simulation
            World world = new World(Config.WORLD_SHAPE, Config.IS_TOROIDAL);
            Simulation simulation = new Simulation(world);

            // Initiales Setup der Organismen in der Welt
            Setup.run(simulation);

            // Erstellen und Anzeigen der Hauptanwendung (UI)
            // Die AppView übernimmt nun die Verantwortung für die JavaFX-Szene und -Struktur.
            AppView appView = new AppView(primaryStage, simulation);

            // GEÄNDERT: Komplette UI-Logik hier entfernt, wird von AppView verwaltet
            // primaryStage.setTitle("Evochora");
            // primaryStage.setScene(scene);
            // primaryStage.show();

        } catch (Exception e) {
            e.printStackTrace();
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
            alert.setTitle("Ein Fehler ist aufgetreten");
            alert.setHeaderText("Die Anwendung muss beendet werden.");
            alert.setContentText("Fehler: " + e.getMessage());
            alert.showAndWait();
        }
    }

    // GEÄNDERT: restartSimulation Methode entfernt, wird nun von AppView oder HeaderController verwaltet
    // private void restartSimulation() { /* ... */ }

    public static void main(String[] args) {
        launch(args);
    }
}