package org.evochora.compiler.features.place;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.expression.symbols.LayoutArguments;
import org.evochora.compiler.features.place.placement.IPlacementComponent;
import org.evochora.compiler.features.place.placement.PlacementNode;
import org.evochora.compiler.features.place.placement.RangeValueComponent;
import org.evochora.compiler.features.place.placement.SingleValueComponent;
import org.evochora.compiler.features.place.placement.SteppedRangeValueComponent;
import org.evochora.compiler.features.place.placement.WildcardValueComponent;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.symbols.SymbolTable;

/**
 * Handles the semantic analysis of {@link PlaceNode}s: evaluates the molecule, which has to be a
 * typed value known now, and every operand of every placement with the names defined above the
 * line, and checks the kind of its value. A placement that is one
 * single value gives the whole place and has to be a vector, a number, which is a vector of one
 * component, or a position; every other component
 * is made of numbers, a value, the bounds of a range and its step. A value only the layout knows
 * passes with the kind it will have.
 */
public class PlaceAnalysisHandler implements IAnalysisHandler {

    /** The message for a molecule that is no typed value, as the parser words it for a written one. */
    private static final String TYPED_LITERAL = "Expected a typed literal (e.g. DATA:5) for .PLACE.";

    private final LayoutArguments arguments;

    /**
     * Creates the handler.
     *
     * @param operations The operations of computations, read when the phase runs.
     */
    public PlaceAnalysisHandler(OperationRegistry operations) {
        this.arguments = new LayoutArguments(operations);
    }

    @Override
    public void analyze(AstNode node, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        PlaceNode place = (PlaceNode) node;
        arguments.expectTyped(place.literal(), TYPED_LITERAL, symbolTable, diagnostics);
        for (PlacementNode placement : place.placements()) {
            if (placement.components().size() == 1 && placement.components().getFirst() instanceof SingleValueComponent single) {
                arguments.expectVector(single.value(), true, symbolTable, diagnostics);
                continue;
            }
            for (IPlacementComponent component : placement.components()) {
                checkNumbers(component, symbolTable, diagnostics);
            }
        }
    }

    private void checkNumbers(IPlacementComponent component, SymbolTable symbolTable, DiagnosticsEngine diagnostics) {
        switch (component) {
            case SingleValueComponent single -> arguments.expectNumber(single.value(), symbolTable, diagnostics);
            case RangeValueComponent range -> {
                arguments.expectNumber(range.start(), symbolTable, diagnostics);
                arguments.expectNumber(range.end(), symbolTable, diagnostics);
            }
            case SteppedRangeValueComponent stepped -> {
                arguments.expectNumber(stepped.start(), symbolTable, diagnostics);
                arguments.expectNumber(stepped.step(), symbolTable, diagnostics);
                arguments.expectNumber(stepped.end(), symbolTable, diagnostics);
            }
            case WildcardValueComponent wildcard -> {
                // Every cell along the axis; the layout knows how many
            }
        }
    }
}
