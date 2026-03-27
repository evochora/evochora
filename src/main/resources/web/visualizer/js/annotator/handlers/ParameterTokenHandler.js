import { AnnotationUtils } from '../AnnotationUtils.js';
import { ValueFormatter } from '../../utils/ValueFormatter.js';

/**
 * Handles the annotation of tokens that are procedure parameter names.
 * Shows the complete binding chain from source register to current FDR and its value.
 * Format: [%DR0→%FDR1→%FDR0=Value] where the chain shows data flow from source (DR/PDR)
 * to intermediate bindings to the current parameter register (FDR), and Value is always
 * read from the current FDR (which may differ from the source if modified in the procedure).
 * Works anywhere in procedure bodies, not just on .PROC directive lines.
 * Resolves parameter bindings by walking the call stack to find the complete chain.
 */
export class ParameterTokenHandler {
    /**
     * Determines if this handler can process the given token.
     * It handles tokens identified as 'VARIABLE' type that are in a procedure scope (not global).
     *
     * @param {string} tokenText The text of the token.
     * @param {object} tokenInfo Metadata about the token from the compiler.
     * @returns {boolean} True if the token is a 'VARIABLE' type in a procedure scope, false otherwise.
     */
    canHandle(tokenText, tokenInfo) {
        if (!tokenInfo) {
            return false;
        }
        
        const isVariableType = tokenInfo.tokenType === 'VARIABLE';
        const isInProcedureScope = tokenInfo.scope && tokenInfo.scope.toUpperCase() !== 'GLOBAL';
        
        return isVariableType && isInProcedureScope;
    }

    /**
     * Analyzes the parameter token to create an annotation with its binding chain and current value.
     * Resolves the complete binding chain through the call stack (from source DR/PDR to current FDR),
     * then reads the value from the current FDR (not from the source, as FDR values may be modified).
     * The binding chain is displayed reversed: from source to target (e.g., %DR0→%FDR1→%FDR0).
     *
     * @param {string} tokenText The text of the token (the parameter name).
     * @param {object} tokenInfo Metadata about the token.
     * @param {object} organismState The current state of the organism, containing the call stack.
     * @param {object} artifact The program artifact containing `procNameToParamNames` and `callSiteBindings`.
     * @returns {object} An annotation object `{ annotationText, kind }`.
     * @throws {Error} If required data (procedure name, parameter list, call stack, binding chain) is missing or invalid.
     */
    analyze(tokenText, tokenInfo, organismState, artifact) {
        if (!organismState || !organismState.callStack || !Array.isArray(organismState.callStack)) {
            throw new Error(`Cannot annotate parameter "${tokenText}": organismState.callStack is missing or invalid.`);
        }

        if (!artifact || !artifact.procNameToParamNames || typeof artifact.procNameToParamNames !== 'object') {
            throw new Error(`Cannot annotate parameter "${tokenText}": artifact.procNameToParamNames is missing or invalid.`);
        }

        // Get procedure name from token scope
        const procName = tokenInfo.scope;
        if (!procName || procName.toUpperCase() === 'GLOBAL') {
            throw new Error(`Cannot annotate parameter "${tokenText}": token scope is not a procedure name (scope: "${procName}").`);
        }

        // Get the procedure's parameter information
        // Structure: procNameToParamNames["PROC1"] = { params: [{name: "PARAM1", type: "REF"}, {name: "PARAM2", type: "VAL"}] }
        const paramNamesEntry = artifact.procNameToParamNames[procName.toUpperCase()];
        if (!paramNamesEntry || typeof paramNamesEntry !== 'object') {
            throw new Error(`Cannot annotate parameter "${tokenText}": procedure "${procName}" not found in procNameToParamNames.`);
        }

        if (!paramNamesEntry.params || !Array.isArray(paramNamesEntry.params)) {
            throw new Error(`Cannot annotate parameter "${tokenText}": procedure "${procName}" has no params array in parameter entry.`);
        }

        // Extract parameter names from ParamInfo array
        const params = paramNamesEntry.params;
        const paramNames = params.map(p => p.name);

        // Find the parameter index
        let paramIndex = -1;
        for (let i = 0; i < paramNames.length; i++) {
            if (paramNames[i] && paramNames[i].toUpperCase() === tokenText.toUpperCase()) {
                paramIndex = i;
                break;
            }
        }

        if (paramIndex === -1) {
            throw new Error(`Cannot annotate parameter "${tokenText}": not found in procedure "${procName}" parameter list.`);
        }

        // Determine if this is a location parameter (LREF/LVAL) or data parameter (REF/VAL)
        const paramType = params[paramIndex].type ? params[paramIndex].type.toUpperCase() : '';
        const isLocationParam = paramType === 'LREF' || paramType === 'LVAL';

        // Calculate bank-specific index: count preceding params of the same bank type
        const bankSpecificIndex = params
            .slice(0, paramIndex)
            .filter(p => {
                const t = (p.type || '').toUpperCase();
                const isLocation = t === 'LREF' || t === 'LVAL';
                return isLocation === isLocationParam;
            }).length;

        // Resolve the binding chain through the call stack using artifact bindings
        const bindingPath = AnnotationUtils.resolveBindingChainWithPath(bankSpecificIndex, organismState.callStack, artifact, organismState, isLocationParam);

        if (bindingPath.length === 0) {
            throw new Error(`Cannot annotate parameter "${tokenText}": binding path is empty.`);
        }

        // Get the value from the LAST register in the chain (the currently valid FDR)
        // bindingPath ends with the parameter's FDR (e.g., [DR0, FDR1, FDR0])
        // This FDR holds the current value, which may differ from the source DR/PDR if modified
        const currentRegId = bindingPath[bindingPath.length - 1];

        // Get the register value from the current FDR
        // getRegisterValueById now throws Error directly if register not found or invalid input
        const value = AnnotationUtils.getRegisterValueById(currentRegId, organismState);

        // Format the binding path: %DR0→%FDR1→%FDR0
        // The path is already in display order (source to target)
        const pathNames = bindingPath.map(regId => AnnotationUtils.formatRegisterName(regId));
        const pathDisplay = pathNames.join('→');

        // Format the value
        const formattedValue = ValueFormatter.format(value);

        // Always show the complete chain (even if it has only one element)
        // Format: [%DR0→%FDR1→%FDR0=Value] where Value is from FDR0 (current, last element)
        return {
            annotationText: `[${pathDisplay}=${formattedValue}]`,
            kind: 'param'
        };
    }
}

