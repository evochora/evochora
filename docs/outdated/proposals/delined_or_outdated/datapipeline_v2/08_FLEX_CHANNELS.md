# Refactoring Plan: Type-Safe Channel Bindings

Objective:

Incrementally refactor the pipeline architecture to support a flexible, type-safe channel binding configuration. The goal is to allow services to be configured with multiple channels per logical port, while ensuring compile-time safety and providing a clear API for service developers. Each step should result in a compilable state.


### Step 1: Enhance AbstractChannelBinding Data Model

**Goal:** Prepare the binding classes to hold all necessary metadata without breaking existing code. This is a non-disruptive preparatory step.

**Instructions:**



1. **Modify datapipeline/core/AbstractChannelBinding.java:**
    * Add a protected final String portName; field.
    * Update the constructor to accept portName as a new parameter: protected AbstractChannelBinding(String serviceName, String portName, String channelName, Direction direction, Object underlyingChannel). Initialize the new field here.
    * Add a public getter public String getPortName().
2. **Modify datapipeline/core/InputChannelBinding.java:**
    * Update the constructor signature to accept portName: public InputChannelBinding(String serviceName, String portName, String channelName, IInputChannel&lt;T> delegate).
    * Pass the new portName parameter to the super() constructor call.
3. **Modify datapipeline/core/OutputChannelBinding.java:**
    * Update the constructor signature similarly: public OutputChannelBinding(String serviceName, String portName, String channelName, IOutputChannel&lt;T> delegate).
    * Pass the new portName parameter to the super() constructor call.
4. **Modify datapipeline/core/ServiceManager.java:**
    * Locate the buildPipeline method. In the "Wire Services and Channels" section where InputChannelBinding and OutputChannelBinding are instantiated, update the constructor calls.
    * Since the portName is not yet available from the configuration, **temporarily pass the channelName as the portName**. This is a placeholder to make the code compile.
    * Example for inputs: new InputChannelBinding&lt;>(serviceName, channelName, channelName, (IInputChannel&lt;?>) channel).

Verification:

After this step, the entire project must compile successfully. All existing tests should still pass as we have only added a new, currently unused data field.


### Step 2: Update the IService Interface Contract

**Goal:** Formally define the new, type-safe contract for how the ServiceManager will provide channel bindings to services.

**Instructions:**



1. **Modify datapipeline/api/services/IService.java:**
    * Delete the existing addInputChannel(String name, IInputChannel&lt;?> channel) method.
    * Delete the existing addOutputChannel(String name, IOutputChannel&lt;?> channel) method.
    * Add the following two new method signatures to the interface. These enforce that only the correct binding types can be passed. \
      /** \
* Adds an input channel binding to a specific logical port of the service. \
* @param portName The logical name of the input port (e.g., "tickData"). \
* @param binding The fully constructed, type-safe input channel binding. \
  */ \
  void addInputChannel(String portName, InputChannelBinding&lt;?> binding); \
  \
  /** \
* Adds an output channel binding to a specific logical port of the service. \
* @param portName The logical name of the output port. \
* @param binding The fully constructed, type-safe output channel binding. \
  */ \
  void addOutputChannel(String portName, OutputChannelBinding&lt;?> binding); \


Verification:

After this step, the project will intentionally not compile. The AbstractService class will show errors because it no longer correctly implements the IService interface. This is the expected state before proceeding to the next step.


### Step 3: Implement the New Contract in AbstractService

**Goal:** Rework AbstractService to correctly implement the new IService contract and provide the powerful helper methods for concrete service implementations.

**Instructions:**



1. **Modify datapipeline/services/AbstractService.java:**
    * **Remove Old State:** Delete the member fields protected final List&lt;ChannelBindingStatus> channelBindings; and private final Map&lt;String, Object> underlyingChannels;.
    * **Add New State:** Add the new, type-safe map declarations for storing the bindings: \
      private final Map&lt;String, List&lt;InputChannelBinding&lt;?>>> inputBindings = new ConcurrentHashMap&lt;>(); \
      private final Map&lt;String, List&lt;OutputChannelBinding&lt;?>>> outputBindings = new ConcurrentHashMap&lt;>(); \

    * **Implement New Methods:** Implement the new addInputChannel and addOutputChannel methods from the IService interface. The logic should add the received binding to the correct map, using the portName as the key.
    * **Add Helper Methods:** Add the four new protected helper methods for accessing channels: getRequiredInputChannel, getInputChannels, getRequiredOutputChannel, and getOutputChannels. Use the final, well-documented version that includes the single necessary @SuppressWarnings("unchecked") with an explanatory comment.
    * **Update getServiceStatus:** Modify the getServiceStatus method to iterate over the new inputBindings and outputBindings maps to build its list of ChannelBindingStatus objects.

Verification:

After this step, AbstractService.java and all classes that extend it should compile again. The project will still not compile because ServiceManager is still calling the old, now-deleted methods.


### Step 4: Update the ServiceManager Wiring Logic

**Goal:** Adapt the ServiceManager to read the new, port-based configuration format and use the new type-safe methods to wire the pipeline.

**Instructions:**



1. **Modify datapipeline/core/ServiceManager.java:**
    * Locate the buildPipeline method and go to the "Wire Services and Channels" section (step 3).
    * **Replace the entire wiring logic** for both inputs and outputs.
    * The new logic must:
        * Check for a configuration object (getConfig("inputs")) instead of a string list.
        * Iterate over the **keys** of this object, which represent the portName.
        * For each portName, get the associated value. It must handle both a single string (for one channel) and a list of strings (for multiple channels).
        * Inside the loop, instantiate the correct, concrete InputChannelBinding or OutputChannelBinding. Crucially, pass the correct portName to the constructor now.
        * Call the new, type-safe service.addInputChannel(portName, binding) or service.addOutputChannel(portName, binding).

Verification:

After this step, the entire project core (core, api, services) should compile successfully. Tests will likely fail because the concrete service implementations are still using the old way of accessing channels.


### Step 5: Adapt a Concrete Service to Use the New API

**Goal:** Finalize the refactoring by updating a concrete service to use the new, safe helper methods provided by AbstractService.

**Instructions:**



1. **Choose a service to update**, for example, datapipeline/services/indexer/EnvironmentStateIndexerService.java.
2. **Remove old channel member variables:** If the class has fields like private IInputChannel&lt;RawTickData> tickChannel;, delete them.
3. **Update the run() method:**
    * At the beginning of the run() method, retrieve the channel using the new helper method. This makes the channel a local variable within the run method's scope. \
      // Example for a service that expects one input on the "tickData" port \
      IInputChannel&lt;RawTickData> tickChannel = getRequiredInputChannel("tickData"); \

    * * Use this `tickChannel` variable for all subsequent read operations. 4. Repeat this process for all other services that need to be updated.
    * **Verification:** After adapting a service and its corresponding tests, the refactoring is complete for that component. The code is now fully migrated to the new, robust, and type-safe architecture.