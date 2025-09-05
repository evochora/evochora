# Proposal: Extend .PLACE Directive with Range Syntax

This document outlines the implementation plan for extending the `.PLACE` directive to support a more expressive, n-dimensional range-based syntax.

## 1. Objective

The primary goal is to enhance the `.PLACE` directive to support both the existing single-vector syntax and a new range-based syntax. This will allow for more concise and powerful placement of structures in the world.

**Current Syntax:**

    .PLACE STRUCTURE:1 11|12

**Proposed New Syntax:**

    # A line from (1, 20) to (10, 20)
    .PLACE STRUCTURE:1 1..10|20

    # A rectangular area from (1, 20) to (10, 30)
    .PLACE STRUCTURE:1 1..10|20..30


## 2. Architectural Approach

The implementation will follow a clean, phased approach that respects the existing compiler architecture and the **Single Responsibility Principle**.

- **No new directive:** We will extend the existing `.PLACE` directive rather than creating a new one like `.FILL`. This maintains semantic consistency, as the core action remains "placing a structure".

- **Specialized Classes:** We will use a set of small, specialized classes and interfaces instead of a single, complex "all-in-one" class. This ensures **type safety**, **clarity of intent**, and makes the system **easier to maintain and extend**.

- **Decoupling:** The general-purpose `VectorLiteralNode` will **not** be modified. This ensures that other parts of the compiler that use it are not affected.


## 3. Implementation Details: Class Structure

The new functionality will be introduced by adding new data structures to the **AST (Abstract Syntax Tree)** and the **IR (Intermediate Representation)** stages of the compiler.


### Phase 1: Frontend - AST Data Structures

These new classes will live in the `frontend.parser.ast` stage.


#### `org.evochora.compiler.frontend.parser.ast.placement`

- `interface IPlacementComponent`

    - **Purpose:** A common interface for a component of a single dimension (either a single number or a range).

- `class SingleValueComponent implements IPlacementComponent`

    - **Purpose:** Represents a single integer value in a dimension (e.g., the `20` in `1..10|20`).

- `class RangeValueComponent implements IPlacementComponent`

    - **Purpose:** Represents a continuous range with a start and end value (e.g., `1..10`).

- `interface IPlacementArgumentNode`

    - **Purpose:** The central interface representing a complete argument for the `.PLACE` directive (either a single vector or a full range expression).

- `class VectorPlacementNode implements IPlacementArgumentNode`

    - **Purpose:** An adapter class that holds a `VectorLiteralNode`. It is used for the old syntax (`11|12`) to decouple `VectorLiteralNode` from the placement logic.

- `class RangeExpressionNode implements IPlacementArgumentNode`

    - **Purpose:** Represents the new, complex range syntax. It will hold a structure like `List<List<IPlacementComponent>>` to represent n-dimensional arguments.


#### `org.evochora.compiler.frontend.parser.features.place`

- `class PlaceNode` (Modified)

    - **Change:** Instead of holding a `List<VectorLiteralNode>`, this class will now store a `List<IPlacementArgumentNode>`. This allows it to hold a mix of old and new argument types.


### Phase 2: Intermediate Representation - IR Data Structures

These new classes mirror the AST structure and are used to pass the information from the frontend to the backend.


#### `org.evochora.compiler.ir.placement`

- `interface IPlacementArgument`

    - **Purpose:** The common interface for a `.PLACE` argument at the IR level.

- `class IrVectorPlacement implements IPlacementArgument`

    - **Purpose:** The IR representation of a single vector placement. It holds an `IrVec` object.

- `class IrRangeExpression implements IPlacementArgument`

    - **Purpose:** The IR representation of the new range syntax. It holds a clean, data-only structure representing the ranges for each dimension.


#### `org.evochora.compiler.ir`

- `class IrDirective` (Modified)

    - **Change:** For a `.PLACE` directive, this class will now hold a `List<IPlacementArgument>`.


## 4. Compiler Workflow Summary

1. **Lexer:** The `..` operator will be added as a new `TokenType`.

2. **Parser (`PlaceDirectiveHandler`):** This handler will be updated to recognize the new syntax. It will create either a `VectorPlacementNode` (for old syntax) or a `RangeExpressionNode` (for new syntax) and add it to the modified `PlaceNode`.

3. **IR Generator (`PlaceNodeConverter`):** This converter will translate the AST nodes (`VectorPlacementNode`, `RangeExpressionNode`) into their corresponding IR objects (`IrVectorPlacement`, `IrRangeExpression`).

4. **Layout Engine (`PlaceLayoutHandler`):** This is where the logic is executed. It will iterate through the list of `IPlacementArgument` objects, check their type (`instanceof`), and perform the final coordinate expansion and molecule placement.

This design ensures that the new syntax is cleanly integrated, type-safe, and that the logic is handled in the correct compiler stage.
