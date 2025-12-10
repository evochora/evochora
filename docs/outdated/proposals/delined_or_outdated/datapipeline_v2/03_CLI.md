## Goal
To create a powerful and user-friendly Command Line Interface (CLI) that serves as the main entry point for the application. The CLI will be responsible for parsing command-line arguments, loading and applying the pipeline configuration (including logging), starting the ServiceManager, and providing an interactive shell for controlling the pipeline at runtime.

## 1. File Location and Structure
* **File:** CommandLineInterface.java
* **Package:** org.evochora.datapipeline.core

## 2. Recommended Libraries
* **Argument Parsing:** **PicoCLI** (info.picocli:picocli)
* **Interactive Shell:** **JLine** (org.jline:jline)
* **Logging:** **SLF4J API** (org.slf4j:slf4j-api) with a **Logback** implementation (ch.qos.logback:logback-classic).
* **Testing:** **JUnit 5** and a mocking framework like **Mockito**.

## 3. Core Responsibilities & Logic
### 3.1. Main Entry Point
* The class must contain the public static void main(String[] args) method.
* It must use PicoCLI to parse the command-line arguments, including support for subcommands (run and compile).

### 3.2. Command-Line Arguments
The CLI must support the following structure with subcommands:
```text
Usage: evochora [-h] [COMMAND]
The command center for the Evochora Data Pipeline.

Commands:
  run        Runs the data pipeline (default command).
  compile    Compiles a single assembly file and outputs the artifact as JSON.
  help       Displays help information.
```

**run Subcommand Options:**
```text
Usage: evochora run [-h] [-c=<filePath>] [--headless] [--service=<serviceName>]
Runs the data pipeline.

Options:
  -h, --help                Displays this help text.
  -c, --config=<filePath>   Path to the HOCON configuration file.
                            Default: ./evochora.conf
      --headless            Starts the application in non-interactive (headless) mode.
      --service=<serviceName> When in headless mode, starts only this specific service.
                            This is a convenience for containerized deployments.
```
Usage: evochora run [-h] [-c=&lt;filePath>] [--headless] [--service=&lt;serviceName>] \
Runs the data pipeline. \

**compile Subcommand Options:**
```text
Usage: evochora compile [-h] [-e=<envString>] <file>
Compiles a single assembly file.

Parameters:
  <file>                    The path to the assembly file to compile.
Options:
  -h, --help                Displays this help text.
  -e, --env=<envString>     Environment properties (e.g., "1000x1000:toroidal").
```

### 3.3. Configuration and Logging Setup (for run command)
1. **Parse Arguments:** First, parse the command-line arguments using PicoCLI.
2. Load Configuration: Load the HOCON configuration. The loading process must be layered: \
   a. Fallback Configuration: Load a default reference.conf from the application's classpath. \
   b. External File: Look for an external configuration file (default ./evochora.conf, override with --config). \
   c. System Properties & Environment Variables: These have the highest precedence.
3. **Initialize Logging:** Configure the logging system based on the logging block in the loaded configuration.

**Example logging block in evochora.conf:**
```hocon
logging {
  format = "PLAIN" # Can be "PLAIN" or "JSON". Defaults to PLAIN for interactive, JSON for headless.
  default-level = "INFO"
  levels {
    "org.evochora.datapipeline.services.DummyService" = "DEBUG"
  }
}
```

### 3.4. ServiceManager Initialization (for run command)
* Instantiate the ServiceManager with the final Config object.

## 4. Operational Modes
### 4.1. Interactive Mode (Default for run command)
* Active if the run command is used without the --headless flag.
* **Action:** Starts the pipeline (serviceManager.startAll()) and then starts an interactive JLine console loop.

### 4.2. Headless Mode (for run command)
* Active if the run command is used with the --headless flag.
* **Action:** Starts either a single service or all services and adds a JVM shutdown hook for graceful termination.

### 4.3. Compile-Only Mode (for compile command)
* Active if the compile command is used.
* **Action:**
    1. The CLI will instantiate the Compiler directly.
    2. It will compile the specified file.
    3. The resulting ProgramArtifact will be serialized to a JSON string.
    4. The JSON string will be printed to standard output.
    5. The application will exit with code 0 on success or a non-zero code on failure.
    6. The ServiceManager is **not** created or used in this mode.

## 5. Interactive Commands (for run command)
* **status**: Renders the pipeline status as a formatted table. The table must gracefully handle non-monitorable channels.
    * **Example Layout:** \
      ======================================================================================== \
      SERVICE / CHANNEL   | STATE   | I/O    | QUEUE       | ACTIVITY \
      ======================================================================================== \
      data-merger        | RUNNING |        |             | Merging Tick: 450 \
      ├─ raw-tick-stream | ACTIVE  | Input  | 123 / 1000  | (150.5/s) \
      ├─ user-event-strm | WAITING | Input  | N/A         | \
      └─ merged-data-strm| ACTIVE  | Output | 78 / 1000   | (149.9/s) \
      ---------------------------------------------------------------------------------------- \
      simulation         | RUNNING |        |             | Processed Tick: 800 \
      └─ raw-tick-stream | ACTIVE  | Output | 123 / 1000  | (120.0/s) \
      ======================================================================================== \

* **pause [serviceName]**: Pauses one or all services.
* **resume [serviceName]**: Resumes one or all services.
* **stop [serviceName]**: Stops one or all services.
* **help**: Displays a list of available interactive commands.
* **exit** or **quit**: Alias for stop.

## 6. Testing Requirements
The CLI's logic must be verified with unit tests, mocking the ServiceManager and Compiler.
* **Test run command:** Verify headless/interactive modes and single/all service startup.
* **Test compile command:** Verify that the compiler is called with the correct file and environment, and that its result is printed.