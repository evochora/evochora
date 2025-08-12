// src/main/java/org/evochora/Main.java
package org.evochora.app;

import javafx.application.Application;
import javafx.stage.Stage;
import org.evochora.app.setup.Config;
import org.evochora.app.setup.Setup;
import org.evochora.app.Simulation;
import org.evochora.compiler.internal.legacy.AssemblerException;
import org.evochora.runtime.isa.Instruction;
import org.evochora.app.ui.AppView;
import org.evochora.runtime.model.World;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) {
        try {
            Instruction.init();

            World world = new World(Config.WORLD_SHAPE, Config.IS_TOROIDAL);
            Simulation simulation = new Simulation(world);

            Setup.run(simulation);

            AppView appView = new AppView(primaryStage, simulation);

        } catch (AssemblerException e) {
            // KORRIGIERT: Gibt jetzt BEIDES aus - zuerst die saubere Nachricht, dann den vollen Stack-Trace.
            System.err.println("Ein Fehler ist beim Assemblieren aufgetreten:");
            System.err.println(e.getMessage()); // Verwende die Standard-Exception-Nachricht
            System.err.println("\n--- VOLLSTÄNDIGER STACK TRACE ---");
            e.printStackTrace(); // Der Standard-Java-Stack-Trace für detailliertes Debugging
        } catch (Exception e) {
            // Der allgemeine Catch-Block behält das UI-Fenster für unerwartete Fehler bei.
            e.printStackTrace();
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
            alert.setTitle("Ein unerwarteter Fehler ist aufgetreten");
            alert.setHeaderText("Die Anwendung muss beendet werden.");
            alert.setContentText("Fehler: " + e.getMessage());
            alert.showAndWait();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}