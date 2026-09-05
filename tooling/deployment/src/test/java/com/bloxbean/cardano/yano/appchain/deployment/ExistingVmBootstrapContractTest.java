package com.bloxbean.cardano.yano.appchain.deployment;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExistingVmBootstrapContractTest {
    @Test
    void bootstrapRequiresASecondVerifiedAdminPhaseBeforeSshHardening() throws Exception {
        Path root = Path.of(System.getProperty("yano.test.repo-root"));
        String playbook = Files.readString(root.resolve("deployment/ansible/bootstrap-existing-vms.yml"));

        assertThat(playbook)
                .contains("yano_harden_ssh: false")
                .contains("(ansible_user | default('')) == yano_admin_user")
                .contains("validate: /usr/sbin/visudo -cf %s")
                .contains("PasswordAuthentication no")
                .contains("KbdInteractiveAuthentication no")
                .contains("PermitRootLogin no")
                .contains("when: yano_harden_ssh | bool")
                .doesNotContain("ansible_password", "private_key:");
    }

    @Test
    void bootstrapInventoryContainsNoCredentials() throws Exception {
        Path root = Path.of(System.getProperty("yano.test.repo-root"));
        String inventory = Files.readString(
                root.resolve("deployment/ansible/bootstrap-inventory.example.yml"));

        assertThat(inventory)
                .contains("yano_bootstrap:", "ansible_host:", "ansible_port: 22")
                .doesNotContain("ansible_user:", "ansible_password:", "ansible_ssh_private_key_file:");
    }

}
