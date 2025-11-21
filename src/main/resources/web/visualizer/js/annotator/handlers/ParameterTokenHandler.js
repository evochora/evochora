/**
 * Handles the annotation of tokens that are procedure parameter names.
 * Shows the bound register and its value: [%DRx=Value] or [%PRx=Value].
 * Works anywhere in procedure bodies, not just on .PROC directive lines.
 * Resolves parameter bindings by walking the call stack to find actual DR/PR registers.
 */
class ParameterTokenHandler {
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
     * Analyzes the parameter token to create an annotation with its bound register and value.
     * It resolves the binding chain through the call stack to find the actual register,
     * then fetches the value from the organism state.
     *
     * @param {string} tokenText The text of the token (the parameter name).
     * @param {object} tokenInfo Metadata about the token.
     * @param {object} organismState The current state of the organism, containing the call stack.
     * @param {object} artifact The program artifact containing `procNameToParamNames`.
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

        // Resolve the binding chain through the call stack using artifact bindings
        // resolveBindingChainWithPath returns the complete path of register IDs
        const bindingPath = AnnotationUtils.resolveBindingChainWithPath(paramIndex, organismState.callStack, artifact, organismState);

        if (bindingPath.length === 0) {
            throw new Error(`Cannot annotate parameter "${tokenText}": binding path is empty.`);
        }

        // Get the final register ID (last in path)
        const finalRegId = bindingPath[bindingPath.length - 1];

        // Get the register value
        // getRegisterValueById now throws Error directly if register not found or invalid input
        const value = AnnotationUtils.getRegisterValueById(finalRegId, organismState);

        // Format the binding path: %FPR0→%FPR1→%DR0
        const pathNames = bindingPath.map(regId => AnnotationUtils.formatRegisterName(regId));
        const pathDisplay = pathNames.join('→');

        // Format the final value
        const formattedValue = ValueFormatter.format(value);

        // If path has more than one element, show the complete chain, otherwise just the final register
        const registerDisplay = pathNames.length > 1 ? pathDisplay : pathNames[0];

        return {
            annotationText: `[${registerDisplay}=${formattedValue}]`,
            kind: 'param'
        };
    }
}

window.ParameterTokenHandler = ParameterTokenHandler;

