# Data Pipeline V3 - Protobuf Setup & Contracts

## Goal

Set up Protocol Buffers (Protobuf) infrastructure and create initial data contracts for the data pipeline. This establishes the foundation for efficient, type-safe serialization between services.

## Success Criteria

Upon completion:
1. Protobuf is integrated into the Gradle build system
2. Generated Java classes are available and compilable
3. A simple DummyMessage contract exists for testing
4. Basic serialization/deserialization works in a unit test


## Implementation Steps

### Step 1: Configure Gradle for Protobuf

#### 1.1. Add Protobuf Plugin and Dependencies
**File:** `build.gradle.kts`

**Required additions:**
- Add `com.google.protobuf` plugin
- Add protobuf dependencies (protobuf-java, protoc compiler)
- Configure protobuf source sets and generation

**Expected result:** Gradle can compile `.proto` files to Java classes

#### 1.2. Create Proto Source Directory
**Directory:** `src/main/proto/org/evochora/datapipeline/api/contracts/`

**Purpose:** Standard location for `.proto` definition files

### Step 2: Define Initial Data Contracts

#### 2.1. Create Basic Message Schema
**File:** `src/main/proto/org/evochora/datapipeline/api/contracts/pipeline_contracts.proto`

**Required content:**
- Use `proto3` syntax
- Define package matching Java package structure
- Create `DummyMessage` with basic fields for testing:
  - `int32 id` - Simple identifier
  - `string content` - Text content for validation
  - `int64 timestamp` - When message was created

**Expected output:** Generated `DummyMessage.java` class in `org.evochora.datapipeline.api.contracts`

### Step 3: Verify Integration

#### 3.1. Test Compilation
**Goal:** Ensure Gradle correctly generates Java classes from Proto definitions

**Actions:**
- Run `./gradlew compileJava`
- Verify generated classes exist in `build/generated/source/proto/main/java/`
- Verify generated classes are available to application code

#### 3.2. Create Basic Validation Test
**File:** `src/test/java/org/evochora/datapipeline/api/contracts/ProtobufIntegrationTest.java`

**Test requirements:**
- Create DummyMessage instance
- Serialize to byte array using `toByteArray()`
- Deserialize back using `parseFrom(byte[])`
- Verify all fields are preserved
- Should be tagged as @Tag("unit")

## Technical Requirements

### Protobuf Configuration
- **Version:** Use latest stable protobuf version (3.x)
- **Java Version Compatibility:** Must work with Java 21
- **Generated Code:** Should follow standard Java naming conventions
- **Source Control:** Only commit `.proto` files, not generated Java classes

### Generated Code Location
- **Source:** `src/main/proto/`
- **Generated:** `build/generated/source/proto/main/java/`
- **Package:** Must match `org.evochora.datapipeline.api.contracts`

### Build Integration
- **Automatic Generation:** Protobuf generation should be part of normal build process
- **IDE Integration:** Generated classes should be available to IDE for autocomplete
- **Clean Build:** `./gradlew clean build` should regenerate all protobuf classes

## Non-Requirements

- Do NOT create complex message schemas yet (keep it simple with DummyMessage)
- Do NOT implement actual services that use the contracts (that comes later)
- Do NOT optimize for performance yet (focus on getting it working)
- Do NOT add protobuf plugins for other languages (Java only for now)

## Validation

The implementation is correct if:
1. `./gradlew compileJava` succeeds without errors
2. Generated `DummyMessage.java` exists and is importable
3. Unit test can serialize/deserialize DummyMessage successfully
4. Generated classes have proper Javadoc from proto comments
5. Build is deterministic (repeated builds produce identical results)

This foundation will enable efficient data serialization for all subsequent service implementations.
