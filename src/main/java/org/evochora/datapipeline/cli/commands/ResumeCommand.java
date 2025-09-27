package org.evochora.datapipeline.cli.commands;

import org.evochora.datapipeline.ServiceManager;
import org.evochora.datapipeline.cli.CommandLineInterface;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "resume", description = "Resumes the data pipeline services.")
public class ResumeCommand implements Callable<Integer> {

    @ParentCommand
    private CommandLineInterface parent;

    @Parameters(description = "The names of the services to resume. If none are specified, all services are resumed.", arity = "0..*")
    private List<String> serviceNames;

    @Override
    public Integer call() {
        ServiceManager serviceManager = parent.getServiceManager();
        if (serviceNames == null || serviceNames.isEmpty()) {
            System.out.println("Resuming all services...");
            serviceManager.resumeAll();
        } else {
            for (String serviceName : serviceNames) {
                System.out.println("Resuming service: " + serviceName);
                serviceManager.resumeService(serviceName);
            }
        }
        return 0;
    }
}