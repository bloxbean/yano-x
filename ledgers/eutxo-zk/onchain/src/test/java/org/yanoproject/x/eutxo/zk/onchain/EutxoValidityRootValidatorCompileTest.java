package org.yanoproject.x.eutxo.zk.onchain;

import org.julclang.testkit.ContractTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoValidityRootValidatorCompileTest extends ContractTest {

    @Test
    void releasePinnedJulcCompilesContextBoundValidator() {
        assertThat(compileValidator(
                EutxoValidityRootValidator.class).program()).isNotNull();
    }
}
