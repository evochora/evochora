package org.evochora.server;

import org.evochora.server.config.ConfigLoader;
import org.evochora.server.config.SimulationConfiguration;
import org.evochora.server.queue.InMemoryTickQueue;
import org.evochora.server.queue.ITickMessageQueue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Simple CLI to control the Evochora simulation pipeline.
 */
public final class CommandLineInterface {

    private ITickMessageQueue queue;
    private SimulationConfiguration cfg;
    private ServiceManager serviceManager;

    public static void main(String[] args) {
        // Suppress SLF4J replay warnings
        System.setProperty("slf4j.replay.warn", "false");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        
        try {
            CommandLineInterface cli = new CommandLineInterface();
            cli.run();
        } catch (Exception e) {
            System.err.println("CLI error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    public void run() throws Exception {
        this.queue = new InMemoryTickQueue();
        this.cfg = ConfigLoader.loadDefault();
        this.serviceManager = new ServiceManager(queue, cfg);

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Evochora CLI ready. Commands: start | pause | resume | status | exit");
        System.out.println("  start [service] - start all services or specific service");
        System.out.println("  pause [service] - pause all services or specific service");
        System.out.println("  resume [service] - resume all services or specific service");
        System.out.println("  status - show status of all services");
        System.out.println("  exit - shutdown and exit");

        while (true) {
            System.err.print(">>> ");
            String line = reader.readLine();
            if (line == null) break;
            String cmd = line.trim();
            
            if (cmd.equalsIgnoreCase("start")) {
                System.out.println("Starting all services...");
                serviceManager.startAll();
                System.out.println("All services started successfully");
                
            } else if (cmd.startsWith("start ")) {
                String serviceName = cmd.substring(6).trim().toLowerCase();
                System.out.println("Starting " + serviceName + "...");
                serviceManager.startService(serviceName);
                
            } else if (cmd.equalsIgnoreCase("pause")) {
                System.out.println("Pausing all services...");
                serviceManager.pauseAll();
                System.out.println("All services paused");
                
            } else if (cmd.startsWith("pause ")) {
                String serviceName = cmd.substring(6).trim().toLowerCase();
                System.out.println("Pausing " + serviceName + "...");
                serviceManager.pauseService(serviceName);
                
            } else if (cmd.equalsIgnoreCase("resume")) {
                System.out.println("Resuming all services...");
                serviceManager.resumeAll();
                System.out.println("All services resumed");
                
            } else if (cmd.startsWith("resume ")) {
                String serviceName = cmd.substring(6).trim().toLowerCase();
                System.out.println("Resuming " + serviceName + "...");
                serviceManager.resumeService(serviceName);
                
            } else if (cmd.equalsIgnoreCase("status")) {
                System.out.println("Service Status:");
                System.out.println(serviceManager.getStatus());
                
            } else if (cmd.equalsIgnoreCase("exit") || cmd.equalsIgnoreCase("quit")) {
                break;
                
            } else if (cmd.isEmpty()) {
                continue;
            } else {
                System.out.println("Unknown command. Available: start [service] | pause [service] | resume [service] | status | exit");
            }
        }

        // Cleanup
        System.out.println("Shutting down services...");
        serviceManager.stopAll();
        System.out.println("CLI shutdown complete");
    }
}