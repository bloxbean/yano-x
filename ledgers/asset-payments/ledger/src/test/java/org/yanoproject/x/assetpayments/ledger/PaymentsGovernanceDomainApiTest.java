package org.yanoproject.x.assetpayments.ledger;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1.Command;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.AssetStatus;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.framework.api.appchain.AppQueryResult;
import org.yanoproject.x.framework.api.appchain.AppStateMachine;
import org.yanoproject.x.framework.api.plugin.domain.DomainApi;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiAccess;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiContext;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiException;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiRequest;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiResponse;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiRouteSet;
import org.yanoproject.x.framework.api.plugin.domain.DomainHttpMethod;
import org.yanoproject.x.framework.api.plugin.domain.DomainQueryService;
import org.yanoproject.x.framework.api.plugin.domain.LocalReadModelQueryService;
import org.yanoproject.x.framework.api.plugin.domain.PrivilegedSystemMessageService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.x.assetpayments.ledger.PaymentsHarness.*;

/**
 * ADR-059 D14/D15: the members' privileged route for governed commands. A {@code PAUSE} posted to each member's node
 * passes the ledger's privileged admission, is submitted on the reserved governance topic, and, once a threshold of
 * members' submissions is sequenced, moves the domain status leaf to {@code PAUSED}.
 */
class PaymentsGovernanceDomainApiTest {
    final PaymentsHarness ledger = new PaymentsHarness(AssetPaymentsPayoutTest.settings());

    /** What one member's node submitted through the host's privileged seam, as the runtime would sign it. */
    record Submitted(byte[] sender, String topic, byte[] body) {
    }

    final List<Submitted> submitted = new ArrayList<>();

    /** One member node's domain API: the host's query facade and privileged seam over this ledger. */
    DomainApi api(int member) {
        AppStateMachine machine = ledger.machine;
        DomainQueryService queries = new DomainQueryService() {
            @Override public List<String> chainIds() { return List.of(CHAIN); }
            @Override public AppQueryResult query(String chainId, String path, byte[] params) {
                return new AppQueryResult(chainId, AssetPaymentsContract.STATE_MACHINE_ID, ledger.height,
                        new byte[32], ledger.query(path, params));
            }
        };
        PrivilegedSystemMessageService privileged = new PrivilegedSystemMessageService() {
            @Override public void validate(String chainId, String topic, byte[] body) {
                // AppLedgerRuntime.validatePrivilegedSystemMessage: governance topics only, then the machine's check.
                if (!topic.startsWith("~governance/")) throw new IllegalArgumentException("governance topics only");
                AppStateMachine.AdmissionResult result = machine.validatePrivilegedSystemSubmission(topic, body);
                if (!result.isAccepted()) throw new IllegalArgumentException(result.reason());
            }

            @Override public String submit(String chainId, String topic, byte[] body) {
                validate(chainId, topic, body);
                submitted.add(new Submitted(MEMBERS.get(member), topic, body.clone()));
                return "ab".repeat(32);
            }
        };
        return new AssetPaymentsDomainApiProvider().create(new DomainApiContext(Map.of(), queries,
                LocalReadModelQueryService.unavailable(), privileged));
    }

    static DomainApiRequest post(byte[] body, Map<String, List<String>> query) {
        return new DomainApiRequest(PaymentsGovernanceDomainApi.COMMAND, DomainHttpMethod.POST,
                "chains/" + CHAIN + "/payments-governance/commands", Map.of("chain_id", CHAIN), query, body);
    }

    static DomainApiRequest status() {
        return new DomainApiRequest(PaymentsGovernanceDomainApi.STATUS, DomainHttpMethod.GET,
                "chains/" + CHAIN + "/payments-governance", Map.of("chain_id", CHAIN), Map.of(), new byte[0]);
    }

    static String text(DomainApiResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /** Sequences every submitted message in one block, each signed by the member whose node submitted it. */
    void sequence(long timestamp) {
        Message[] messages = submitted.stream().map(sent -> new Message(sent.topic(), sent.body(), sent.sender()))
                .toArray(Message[]::new);
        submitted.clear();
        ledger.block(timestamp, messages);
    }

    @Test
    void theCommandRouteIsPrivilegedAndTheRoutesValidate() {
        DomainApi api = api(0);
        // The bundle's one domain API carries the payments and the governance routes; they validate together.
        assertThat(DomainApiRouteSet.validateAndOrder(api.routes())).hasSameSizeAs(api.routes());
        assertThat(api.routes()).extracting(route -> route.routeId())
                .contains(PaymentsGovernanceDomainApi.COMMAND, PaymentsGovernanceDomainApi.STATUS);
        assertThat(api.routes()).filteredOn(route -> route.routeId().equals(PaymentsGovernanceDomainApi.COMMAND))
                .singleElement().satisfies(route -> assertThat(route.access()).isEqualTo(DomainApiAccess.PRIVILEGED));
        assertThat(api.routes()).filteredOn(route -> route.routeId().equals(PaymentsGovernanceDomainApi.STATUS))
                .singleElement().satisfies(route -> assertThat(route.access()).isEqualTo(DomainApiAccess.READ));
    }

    @Test
    void aPauseSubmittedThroughTheRouteIsAcceptedAndDecidedAtThreshold() throws Exception {
        ledger.block(T0);
        // A member reads the current sequence and the ready-made PAUSE for it.
        String view = text(api(0).handle(status()));
        byte[] pause = new Command(0, new PaymentsGovernanceV1.Pause()).encode();
        assertThat(view).contains("\"sequence\":0").contains("\"pauseCommandHex\":\"" + HexFormat.of().formatHex(pause));

        // A dry run checks without submitting.
        assertThat(text(api(0).handle(post(pause, Map.of("dry-run", List.of("true")))))).contains("\"validated\":true");
        assertThat(submitted).isEmpty();

        // The first member's submission is accepted, sequenced on the reserved topic, and left pending.
        assertThat(text(api(0).handle(post(pause, Map.of())))).contains("\"messageId\":\"" + "ab".repeat(32));
        assertThat(submitted).singleElement().satisfies(sent -> {
            assertThat(sent.topic()).isEqualTo(PaymentsGovernanceV1.TOPIC);
            assertThat(sent.body()).isEqualTo(pause);
        });
        sequence(T0 + 1_000);
        assertThat(domain().status()).isEqualTo(DomainStatusV1.Status.ACTIVE);
        String pending = text(api(2).handle(status()));
        assertThat(pending).contains("\"commandHex\":\"" + HexFormat.of().formatHex(pause) + "\"")
                .contains("\"proposer\":\"" + HexFormat.of().formatHex(MEMBERS.get(0)) + "\"");

        // A second member posts the same bytes: the threshold of two decides the PAUSE.
        api(2).handle(post(pause, Map.of()));
        sequence(T0 + 2_000);
        assertThat(domain()).isEqualTo(new DomainStatusV1(DomainStatusV1.Status.PAUSED, ledger.height, T0 + 2_000,
                86_400_000L));
        assertThat(text(api(1).handle(status()))).contains("\"sequence\":1").contains("\"openProposals\":[]");
    }

    @Test
    void malformedOrInadmissibleCommandsAreRefusedBeforeSubmission() {
        ledger.block(T0);
        DomainApi api = api(0);
        assertThatThrownBy(() -> api.handle(post(new byte[] {1, 2, 3}, Map.of())))
                .isInstanceOf(DomainApiException.class);
        byte[] unknownVault = new Command(0, new PaymentsGovernanceV1.AssetStatusChange(Hash32.of(filled(32, 9)),
                AssetStatus.WIND_DOWN)).encode();
        assertThatThrownBy(() -> api.handle(post(unknownVault, Map.of()))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("UNKNOWN_VAULT");
        assertThatThrownBy(() -> api.handle(post(new Command(0, new PaymentsGovernanceV1.Pause()).encode(),
                Map.of("other", List.of("x"))))).isInstanceOf(DomainApiException.class);
        assertThat(submitted).isEmpty();
    }

    DomainStatusV1 domain() {
        return ledger.get(AssetPaymentsContract.domainStatusKey()).map(DomainStatusV1::decode).orElseThrow();
    }
}
