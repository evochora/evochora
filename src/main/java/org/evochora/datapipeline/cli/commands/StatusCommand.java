package org.evochora.datapipeline.cli.commands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.evochora.datapipeline.ServiceManager;
import org.evochora.datapipeline.api.resources.IContextualResource;
import org.evochora.datapipeline.api.resources.IMonitorable;
import org.evochora.datapipeline.api.resources.IWrappedResource;
import org.evochora.datapipeline.api.services.ResourceBinding;
import org.evochora.datapipeline.api.services.ServiceStatus;
import org.evochora.datapipeline.cli.CommandLineInterface;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "status", description = "Shows the status of the data pipeline services.")
public class StatusCommand implements Callable<Integer> {

    @ParentCommand
    private CommandLineInterface parent;

    @Option(names = "--json", description = "Output the status in JSON format.")
    private boolean json;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        ServiceManager serviceManager = parent.getServiceManager();
        Map<String, ServiceStatus> serviceStatuses = serviceManager.getAllServiceStatus();
        PrintWriter out = spec.commandLine().getOut();

        if (json) {
            Gson gson = new GsonBuilder()
                    .setPrettyPrinting()
                    .registerTypeAdapter(Instant.class, new TypeAdapter<Instant>() {
                        @Override
                        public void write(JsonWriter out, Instant value) throws IOException {
                            out.value(value.toString());
                        }

                        @Override
                        public Instant read(JsonReader in) throws IOException {
                            return Instant.parse(in.nextString());
                        }
                    })
                    .create();
            out.println(gson.toJson(serviceStatuses));
        } else {
            printHumanReadableStatus(serviceStatuses, out);
        }

        return 0;
    }

    private void printHumanReadableStatus(Map<String, ServiceStatus> serviceStatuses, PrintWriter out) {
        if (serviceStatuses.isEmpty()) {
            out.println("No services are configured.");
            return;
        }

        for (Map.Entry<String, ServiceStatus> entry : serviceStatuses.entrySet()) {
            String serviceName = entry.getKey();
            ServiceStatus status = entry.getValue();

            out.printf("Service: %s, State: %s, Metrics: %s%n",
                    serviceName, status.state(), status.metrics());

            if (status.errors() != null && !status.errors().isEmpty()) {
                out.println("  Errors:");
                status.errors().forEach(error -> out.printf("    - %s%n", error.message()));
            }

            if (status.resourceBindings() != null && !status.resourceBindings().isEmpty()) {
                for (ResourceBinding binding : status.resourceBindings()) {
                    Map<String, Number> bindingMetrics = Collections.emptyMap();
                    if (binding.resource() instanceof IContextualResource) {
                        IWrappedResource wrappedResource = ((IContextualResource) binding.resource()).getWrappedResource(binding.context());
                        if (wrappedResource instanceof IMonitorable) {
                            bindingMetrics = ((IMonitorable) wrappedResource).getMetrics();
                        }
                    }

                    out.printf("  Binding: %s, Resource: %s, Usage: %s, State: %s, Metrics: %s%n",
                            binding.context().portName(),
                            binding.context().resourceName(),
                            binding.context().usageType(),
                            binding.resource().getUsageState(binding.context().usageType()),
                            bindingMetrics);
                }
            }
        }
    }
}