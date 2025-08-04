// src/main/java/org/evochora/Main.java
package org.evochora;

import javafx.application.Application;
import javafx.stage.Stage;
// GEÄNDERT: import org.evochora.organism.InstructionSet; -> wird zu:
import org.evochora.organism.Instruction; // Neuer Import
import org.evochora.ui.AppView;
import org.evochora.world.World;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) {
        try {
            // GEÄNDERT: Ruft die neue, saubere init-Methode auf
            Instruction.init();

            World world = new World(Config.WORLD_SHAPE, Config.IS_TOROIDAL);
            Simulation simulation = new Simulation(world);

            Setup.run(simulation);

            AppView appView = new AppView(primaryStage, simulation);

        } catch (Exception e) {
            e.printStackTrace();
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
            alert.setTitle("Ein Fehler ist aufgetreten");
            alert.setHeaderText("Die Anwendung muss beendet werden.");
            alert.setContentText("Fehler: " + e.getMessage());
            alert.showAndWait();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}