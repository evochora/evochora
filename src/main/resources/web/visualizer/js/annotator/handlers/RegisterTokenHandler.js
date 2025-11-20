class RegisterTokenHandler {
    canHandle(token, tokenInfo) {
        const type = tokenInfo.tokenType;
        return type === 'ALIAS' || (type === 'VARIABLE' && token.startsWith('%'));
    }

    analyze(token, tokenInfo, state, artifact) {
        if (!token.startsWith('%')) return null;

        const canonicalReg = RegisterUtils.resolveToCanonicalRegister(token, artifact);
        const lookupName = canonicalReg || token;
        
        const value = RegisterUtils.getRegisterValue(lookupName, state);

        if (value === null || value === undefined) return null;

        const formattedValue = ValueFormatter.format(value);

        if (canonicalReg) {
            return {
                annotationText: `[${canonicalReg}=${formattedValue}]`,
                kind: 'reg'
            };
        } else {
            return {
                annotationText: `[=${formattedValue}]`,
                kind: 'reg'
            };
        }
    }
}
