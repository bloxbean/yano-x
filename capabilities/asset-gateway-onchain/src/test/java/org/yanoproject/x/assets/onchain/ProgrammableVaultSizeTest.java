package org.yanoproject.x.assets.onchain;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.core.Program;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-061 D9: the applied custody script {@code h}, its exit delegate {@code e} and the shard binding variant, of
 * both backends, stay under the 15,500-byte guard that the native vault uses ({@code GatewayBudgetTest.scriptSizes}),
 * so each fits in one reference-script output. {@code SIZE|} lines report the CBOR-wrapped script bytes.
 */
class ProgrammableVaultSizeTest extends ProgrammableTestBase {
    static final int GUARD = 15_500;

    @Test
    void theCustodyScriptsFitTheGuard() {
        Map<String, Program> templates = new LinkedHashMap<>();
        templates.put("ProgrammableVaultValidator", program(ProgrammableVaultValidator.class));
        templates.put("ProgrammableVaultExitDelegate", program(ProgrammableVaultExitDelegate.class));
        templates.put("ProgrammableShardValidator", program(ProgrammableShardValidator.class));
        Map<String, Program> applied = new LinkedHashMap<>();
        applied.put("ProgrammableVaultValidator", custody());
        applied.put("ProgrammableVaultExitDelegate", exitDelegate());
        applied.put("ProgrammableShardValidator", programmableShard());
        // The Aiken backend (ADR-061 D3), applied with the julc scripts' parameters.
        templates.put("ProgrammableVaultValidatorAiken", aikenTemplate(AIKEN_CUSTODY_ARTIFACT));
        templates.put("ProgrammableVaultExitDelegateAiken", aikenTemplate(AIKEN_EXIT_DELEGATE_ARTIFACT));
        templates.put("ProgrammableShardValidatorAiken", aikenTemplate(AIKEN_PROGRAMMABLE_SHARD_ARTIFACT));
        applied.put("ProgrammableVaultValidatorAiken", aikenCustody());
        applied.put("ProgrammableVaultExitDelegateAiken", aikenExitDelegate());
        applied.put("ProgrammableShardValidatorAiken", aikenProgrammableShard());
        for (String name : templates.keySet()) {
            int template = size(templates.get(name));
            int full = size(applied.get(name));
            System.out.printf("SIZE|%s|%d|%d%n", name, template, full);
            assertThat(full).as("%s applied", name).isLessThan(GUARD);
        }
    }

    static int size(Program program) {
        return JulcScriptAdapter.fromProgram(program).getCborHex().length() / 2;
    }
}
