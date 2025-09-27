package org.evochora.datapipeline.cli.commands;

import org.evochora.datapipeline.ServiceManager;
import org.evochora.datapipeline.cli.CommandLineInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "restart", description = "Restarts the data pipeline services.")
public class RestartCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(RestartCommand.class);

    @ParentCommand
    private CommandLineInterface parent;

    @Parameters(description = "The names of the services to restart. If none are specified, all services are restarted.", arity = "0..*")
    private List<String> serviceNames;

    @Override
    public Integer call() {
        ServiceManager serviceManager = parent.getServiceManager();
        if (serviceNames == null || serviceNames.isEmpty()) {
            logger.info("Restarting all services...");
            serviceManager.restartAll();
        } else {
            for (String serviceName : serviceNames) {
                logger.info("Restarting service: {}", serviceName);
                serviceManager.restartService(serviceName);
            }
        }
        return 0;
    }
}