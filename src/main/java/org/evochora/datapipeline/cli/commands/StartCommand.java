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

@Command(name = "start", description = "Starts the data pipeline services.")
public class StartCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(StartCommand.class);

    @ParentCommand
    private CommandLineInterface parent;

    @Parameters(description = "The names of the services to start. If none are specified, all services are started.", arity = "0..*")
    private List<String> serviceNames;

    @Override
    public Integer call() {
        ServiceManager serviceManager = parent.getServiceManager();
        if (serviceNames == null || serviceNames.isEmpty()) {
            logger.info("Starting all services...");
            serviceManager.startAll();
        } else {
            for (String serviceName : serviceNames) {
                logger.info("Starting service: {}", serviceName);
                serviceManager.startService(serviceName);
            }
        }
        return 0;
    }
}