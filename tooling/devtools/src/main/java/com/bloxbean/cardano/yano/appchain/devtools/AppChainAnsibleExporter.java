package com.bloxbean.cardano.yano.appchain.devtools;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** Existing-VM deployment derivative of the shared application compiler. */
final class AppChainAnsibleExporter {
    private AppChainAnsibleExporter() { }

    static TreeMap<String, byte[]> files(AppChainProjectModel.Resolution resolution,
                                         AppChainProjectModel.Lock lock) {
        var spec = resolution.blueprint().spec();
        var topology = spec.chains().getFirst().topology();
        if (!"host".equals(spec.deployment().target()) || !"jvm".equals(spec.runtime().type())
                || topology.nodeHosts() == null || topology.nodeHosts().size() != topology.members()
                || resolution.bootstrapRequired()) {
            throw new IllegalArgumentException("Ansible export requires a JVM host project with one public key "
                    + "and hostname per member; private keys remain controller-local files");
        }
        var files = new TreeMap<String, byte[]>();
        String project = resolution.blueprint().metadata().name();
        StringBuilder inventory = new StringBuilder("all:\n  children:\n    yano_members:\n      hosts:\n");
        for (int node = 0; node < topology.members(); node++) {
            inventory.append("        node").append(node).append(":\n          ansible_host: ")
                    .append(topology.nodeHosts().get(node)).append("\n          yano_node_index: ").append(node)
                    .append("\n");
            files.put("files/node" + node + ".yaml", utf8(AppChainProjectRenderer.nodeYaml(resolution,
                    node, topology.members(), AppChainProjectRenderer.NodeLayout.HOST,
                    lock.resolvedConfigDigest(), lock.catalogDigests().get("releaseIndex"))));
        }
        files.put("inventory.yaml", utf8(inventory.toString()));
        files.put("verify.py", utf8("""
                import hashlib
                import json
                import pathlib
                root = pathlib.Path(__file__).resolve().parent
                lock = json.loads((root / 'gitops.lock').read_text())
                assert lock['kind'] == 'AppChainGitOpsLock' and lock['target'] == 'ansible'
                for name, expected in lock['generatedFiles'].items():
                    path = root / name
                    assert not path.is_symlink() and path.resolve().is_relative_to(root), 'Unsafe generated path'
                    assert hashlib.sha256(path.read_bytes()).hexdigest() == expected, 'Generated file changed: ' + name
                print('Verified generated deployment files')
                """));
        files.put("files/shared-consensus.yaml",
                utf8(AppChainProjectRenderer.yamlConfig(resolution.consensusProperties())));
        files.put("files/revision", utf8(lock.blueprintDigest() + "\n"));
        files.put("operator-vars.example.yaml", utf8("""
                # Copy outside this generated export and fill in your controller-local paths.
                ansible_user: admin
                yano_archive: /absolute/path/to/yano-x-jvm.zip
                yano_archive_sha256: REPLACE_WITH_64_HEX_RELEASE_CHECKSUM
                yano_archive_root: REPLACE_WITH_ARCHIVE_ROOT_DIRECTORY
                yano_java_home: /opt/java-25
                yano_secret_directory: /absolute/path/to/private/node-env-files
                # Each file is named node0.env, node1.env, ... with KEY=value lines.
                # Private values must never be added to inventory or this variable file.
                """));
        String unit = """
                [Unit]
                Description=Yano X %s node {{ yano_node_index }}
                After=network-online.target
                Wants=network-online.target

                [Service]
                Type=simple
                User=yano
                Group=yano
                WorkingDirectory=/etc/yano/%s
                Environment=JAVA_HOME={{ yano_java_home }}
                Environment=QUARKUS_LOG_FILE_ENABLE=false
                Environment=PATH={{ yano_java_home }}/bin:/usr/local/bin:/usr/bin:/bin
                Environment=YANO_APPCHAIN_PROJECT_ROOT=/etc/yano/%s
                Environment=YANO_APPCHAIN_DATA_ROOT=/var/lib/yano/%s
                Environment=QUARKUS_CONFIG_LOCATIONS=/etc/yano/%s/shared-consensus.yaml,/etc/yano/%s/node.yaml
                EnvironmentFile=/etc/yano/%s/node.env
                ExecStart=/opt/yano/releases/{{ yano_archive_sha256 }}/{{ yano_archive_root }}/yano.sh start:%s
                Restart=on-failure
                RestartSec=5
                TimeoutStopSec=120
                UMask=0077
                NoNewPrivileges=true
                PrivateTmp=true
                ProtectHome=true
                ProtectSystem=strict
                ReadWritePaths=/var/lib/yano/%s

                [Install]
                WantedBy=multi-user.target
                """.formatted(project, project, project, project, project, project, project, spec.network(), project);
        files.put("templates/yano.service.j2", utf8(unit));
        files.put("deploy.yaml", utf8(playbook(project,
                topology.httpPortBase() == null ? 8080 : topology.httpPortBase(),
                lock.blueprintDigest())));
        files.put("README.md", utf8("""
                # Deploy this application to existing VMs

                This export contains %d chains on %d member VMs. It has no mandatory anchor,
                settlement, Kafka, or object-store deployment. Complete the prerequisites of
                the selected recipes before expecting their business outcomes.

                Install Ansible on the controller and Java 25 at the same absolute path on
                each supported systemd Linux VM. Establish SSH/sudo access. Configure host
                firewalls before starting: allow the configured P2P port between members;
                restrict HTTP to your operator/client network. This playbook does not create
                public ingress or change your firewall policy.

                Copy operator-vars.example.yaml outside this generated directory. Pin the
                exact matching Yano X JVM archive, published SHA-256, archive root, Java path,
                and private node environment directory. Use the same distribution to run
                `yano.sh appchain doctor <source-project> --distribution <extracted-release>`.
                Every node environment must match the public member key in the source project.

                ```bash
                ansible-playbook -i inventory.yaml deploy.yaml -e @/path/to/operator-vars.yaml --syntax-check
                ansible-playbook -i inventory.yaml deploy.yaml -e @/path/to/operator-vars.yaml --check
                ansible-playbook -i inventory.yaml deploy.yaml -e @/path/to/operator-vars.yaml
                ```

                Check mode previews Ansible tasks; it is not evidence that remote nodes are
                ready. Repeating apply with the same application revision and archive is
                idempotent. An existing different revision is rejected before service or
                configuration mutation; coordinated remote upgrades require a separate plan.

                Services run as yano, with private EnvironmentFile delivery. State remains
                below /var/lib/yano/%s/nodeN, with chainstate, appchain-chainstate, and
                appchain-indexers as siblings. Inspect `systemctl status yano-%s` and
                `journalctl -u yano-%s` on each host. Run multi-chain appchain drift with
                all remote API URLs and verify a finalized command/proof before handover.
                HTTP readiness alone does not certify consensus or independent L1 anchoring.
                """.formatted(spec.chains().size(), topology.members(), project, project, project)));
        return files;
    }

    private static String playbook(String project, int httpPort, String revision) {
        return """
                # Generated from a validated application lock. Credentials are controller-local.
                - name: Validate inputs and existing deployment before mutation
                  hosts: yano_members
                  become: true
                  gather_facts: true
                  any_errors_fatal: true
                  tasks:
                    - name: Verify controller deployment files against their lock
                      ansible.builtin.command:
                        argv: [python3, '{{ playbook_dir }}/verify.py']
                      delegate_to: localhost
                      become: false
                      changed_when: false
                      check_mode: false
                    - name: Require systemd Linux and explicit immutable release inputs
                      ansible.builtin.assert:
                        that:
                          - ansible_facts.system == 'Linux'
                          - ansible_facts.service_mgr == 'systemd'
                          - yano_archive is match('^/')
                          - yano_archive_sha256 is match('^[0-9a-f]{64}$')
                          - yano_archive_root is match('^yano-x-jvm-[A-Za-z0-9._-]+$')
                          - yano_java_home is match('^/[A-Za-z0-9/._-]+$')
                          - yano_secret_directory is match('^/')
                    - name: Inspect controller archive checksum
                      ansible.builtin.stat:
                        path: '{{ yano_archive }}'
                        checksum_algorithm: sha256
                        get_checksum: true
                      delegate_to: localhost
                      become: false
                      register: archive
                    - name: Require the pinned archive
                      ansible.builtin.assert:
                        that:
                          - archive.stat.isreg | default(false)
                          - archive.stat.checksum == yano_archive_sha256
                    - name: Check Java 25
                      ansible.builtin.command: '{{ yano_java_home }}/bin/java -version'
                      register: java_version
                      changed_when: false
                      check_mode: false
                    - name: Require Java 25
                      ansible.builtin.assert:
                        that: java_version.stderr is search('version .25[^0-9]')
                    - name: Read retained deployment marker
                      ansible.builtin.stat:
                        path: /etc/yano/@PROJECT@/deployment.identity
                      register: identity
                    - name: Inspect retained storage
                      ansible.builtin.stat:
                        path: /var/lib/yano/@PROJECT@
                      register: storage
                    - name: Refuse adoption of unmarked retained stores
                      ansible.builtin.assert:
                        that: identity.stat.exists or not storage.stat.exists
                        fail_msg: Retained state has no deployment marker; review recovery before deployment.
                    - name: Inspect existing identity
                      ansible.builtin.slurp:
                        src: /etc/yano/@PROJECT@/deployment.identity
                      register: retained
                      when: identity.stat.exists
                    - name: Reject unplanned revision or runtime replacement
                      ansible.builtin.assert:
                        that:
                          - (retained.content | b64decode | trim) == '@REVISION@' ~ ':' ~ yano_archive_sha256
                        fail_msg: Existing deployment differs. Review a coordinated upgrade; never reset its stores.
                      when: identity.stat.exists
                    - name: Inspect private node environment
                      ansible.builtin.stat:
                        path: '{{ yano_secret_directory }}/node{{ yano_node_index }}.env'
                      delegate_to: localhost
                      become: false
                      register: node_secret
                      no_log: true
                    - name: Require an owner-only private node environment
                      ansible.builtin.assert:
                        that:
                          - node_secret.stat.isreg | default(false)
                          - node_secret.stat.mode in ['0600', '0400']
                      no_log: true

                - name: Install the pinned application
                  hosts: yano_members
                  become: true
                  gather_facts: false
                  any_errors_fatal: true
                  tasks:
                    - name: Create runtime user
                      ansible.builtin.user:
                        name: yano
                        system: true
                        create_home: false
                        shell: /usr/sbin/nologin
                    - name: Create configuration and release directories
                      ansible.builtin.file:
                        path: '{{ item }}'
                        state: directory
                        owner: root
                        mode: '0755'
                      loop:
                        - /etc/yano/@PROJECT@
                        - /opt/yano/releases/{{ yano_archive_sha256 }}
                    - name: Record exact application and runtime identity
                      ansible.builtin.copy:
                        content: '@REVISION@:{{ yano_archive_sha256 }}'
                        dest: /etc/yano/@PROJECT@/deployment.identity
                        mode: '0444'
                    - name: Create retained storage root
                      ansible.builtin.file:
                        path: /var/lib/yano/@PROJECT@
                        state: directory
                        owner: yano
                        group: yano
                        mode: '0700'
                    - name: Copy the verified archive
                      ansible.builtin.copy:
                        src: '{{ yano_archive }}'
                        dest: /opt/yano/releases/{{ yano_archive_sha256 }}/release.zip
                        mode: '0644'
                    - name: Extract the immutable release
                      ansible.builtin.unarchive:
                        src: /opt/yano/releases/{{ yano_archive_sha256 }}/release.zip
                        dest: /opt/yano/releases/{{ yano_archive_sha256 }}
                        remote_src: true
                        creates: /opt/yano/releases/{{ yano_archive_sha256 }}/{{ yano_archive_root }}/yano.sh
                    - name: Install shared configuration
                      ansible.builtin.copy:
                        src: files/shared-consensus.yaml
                        dest: /etc/yano/@PROJECT@/shared-consensus.yaml
                        mode: '0644'
                    - name: Install member overlay
                      ansible.builtin.copy:
                        src: files/node{{ yano_node_index }}.yaml
                        dest: /etc/yano/@PROJECT@/node.yaml
                        mode: '0644'
                    - name: Install private member environment
                      ansible.builtin.copy:
                        src: '{{ yano_secret_directory }}/node{{ yano_node_index }}.env'
                        dest: /etc/yano/@PROJECT@/node.env
                        owner: root
                        mode: '0600'
                      no_log: true
                      diff: false
                    - name: Install service
                      ansible.builtin.template:
                        src: templates/yano.service.j2
                        dest: /etc/systemd/system/yano-@PROJECT@.service
                        mode: '0644'
                    - name: Start application service
                      ansible.builtin.systemd_service:
                        name: yano-@PROJECT@
                        enabled: true
                        daemon_reload: true
                        state: started
                    - name: Wait for HTTP readiness
                      ansible.builtin.uri:
                        url: http://127.0.0.1:@HTTP_PORT@/q/health/ready
                      register: readiness
                      until: readiness.status | default(0) == 200
                      retries: 90
                      delay: 2
                      when: not ansible_check_mode
                """.replace("@PROJECT@", project).replace("@REVISION@", revision)
                .replace("@HTTP_PORT@", Integer.toString(httpPort));
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
