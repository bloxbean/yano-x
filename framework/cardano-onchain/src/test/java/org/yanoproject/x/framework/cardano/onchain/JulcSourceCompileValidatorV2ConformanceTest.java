package org.yanoproject.x.framework.cardano.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Drift check: compiles {@link AnchorValidatorV2} FROM SOURCE with the locally
 * resolved julc toolchain and runs the full v2 conformance vectors. Local-only,
 * like the v1 drift check: the shipped artifact is the checked-in Aiken build.
 */
@EnabledIfSystemProperty(named = "julc.source-check", matches = "true",
        disabledReason = "opt-in: source-compile depends on the resolved julc version; the shipped "
                + "artifact is checked in — run with -Djulc.source-check=true when regenerating artifacts")
class JulcSourceCompileValidatorV2ConformanceTest extends AnchorValidatorV2ConformanceTest {

    private static Program sourceProgram;

    @Override
    Program program() {
        if (sourceProgram == null) {
            sourceProgram = compileValidator(AnchorValidatorV2.class)
                    .program()
                    .applyParams(PlutusData.bytes(THREAD_POLICY), PlutusData.integer(MAX_WINDOW_MS));
        }
        return sourceProgram;
    }
}
