package org.yanoproject.x.assetpayments.ledger;

import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1;
import org.yanoproject.x.framework.api.appchain.AppQueryException;
import org.yanoproject.x.framework.api.plugin.domain.DomainApi;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiAccess;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiContext;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiException;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiMediaType;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiRequest;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiResponse;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiRoute;
import org.yanoproject.x.framework.api.plugin.domain.DomainHttpMethod;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The members' operator route for the ledger's governed commands (ADR-059 D14 {@code ASSET_*}, D8a and D15
 * {@code PAUSE} / {@code RESUME}), the pattern of the composite profile governance API: commands on
 * {@link PaymentsGovernanceV1#TOPIC} are reserved, so the ordinary messages endpoint refuses them, and this
 * {@link DomainApiAccess#PRIVILEGED} route is how a member submits one. The host authenticates the route with its
 * admin/API-key auth and signs the message as this node's member key; the ledger decides the command in
 * {@code apply()} once the membership epoch's threshold of members submitted the exact same bytes.
 *
 * <ul>
 *   <li>{@code GET chains/{chain_id}/payments-governance} ({@code READ}): the committed governance sequence, the open
 *       proposals with their exact command bytes (so another member can approve one by posting the same bytes) and
 *       the {@code PAUSE} / {@code RESUME} commands for the current sequence, as JSON.</li>
 *   <li>{@code POST chains/{chain_id}/payments-governance/commands[?dry-run=true]} ({@code PRIVILEGED}): the body is
 *       one {@link PaymentsGovernanceV1.Command} in its CBOR encoding. It is decoded here, checked by the ledger's
 *       privileged admission ({@code validatePrivilegedSystemSubmission}), and submitted, or with {@code dry-run} only
 *       checked.</li>
 * </ul>
 *
 * <p>The routes live under their own {@code payments-governance} path and route ids, so they combine with the
 * bundle's other domain routes through {@link DomainApiGroups}.
 */
final class PaymentsGovernanceDomainApi implements DomainApi {
    static final String STATUS = "payments-governance.status";
    static final String COMMAND = "payments-governance.command";
    static final String BASE = "chains/{chain_id}/payments-governance";
    static final List<DomainApiRoute> ROUTES = List.of(
            new DomainApiRoute(STATUS, DomainHttpMethod.GET, BASE, DomainApiAccess.READ),
            new DomainApiRoute(COMMAND, DomainHttpMethod.POST, BASE + "/commands", DomainApiAccess.PRIVILEGED));

    private final DomainApiContext context;

    PaymentsGovernanceDomainApi(DomainApiContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public List<DomainApiRoute> routes() {
        return ROUTES;
    }

    @Override
    public DomainApiResponse handle(DomainApiRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.pathParameters().keySet().equals(Set.of("chain_id"))) throw invalid();
        String chainId = request.pathParameters().get("chain_id");
        if (!context.queryService().chainIds().contains(chainId)) {
            return json(404, "{\"error\":\"chain-not-found\"}");
        }
        return switch (request.routeId()) {
            case STATUS -> status(request, chainId);
            case COMMAND -> command(request, chainId);
            default -> throw invalid();
        };
    }

    private DomainApiResponse status(DomainApiRequest request, String chainId) {
        if (request.method() != DomainHttpMethod.GET || !request.queryParameters().isEmpty()) throw invalid();
        PaymentsGovernanceV1.Status status;
        try {
            status = PaymentsGovernanceV1.Status.decode(context.queryService()
                    .query(chainId, PaymentsGovernanceV1.STATUS_QUERY, new byte[0]).payload());
        } catch (AppQueryException unavailable) {
            if (unavailable.code() == AppQueryException.Code.UNSUPPORTED) {
                return json(404, "{\"error\":\"capability-unavailable\"}");
            }
            throw new DomainApiException(DomainApiException.Code.FAILED, "Payments governance query failed",
                    unavailable);
        } catch (IllegalArgumentException malformed) {
            throw new DomainApiException(DomainApiException.Code.FAILED, "Payments governance query failed",
                    malformed);
        }
        HexFormat hex = HexFormat.of();
        StringBuilder out = new StringBuilder("{\"topic\":\"").append(PaymentsGovernanceV1.TOPIC)
                .append("\",\"sequence\":").append(status.sequence()).append(",\"openProposals\":[");
        for (int i = 0; i < status.open().size(); i++) {
            PaymentsGovernanceV1.Proposal proposal = status.open().get(i);
            if (i > 0) out.append(',');
            out.append("{\"commandHex\":\"").append(hex.formatHex(proposal.command()))
                    .append("\",\"proposer\":\"").append(hex.formatHex(proposal.proposer()))
                    .append("\",\"firstHeight\":").append(proposal.firstHeight())
                    .append(",\"approvers\":[");
            for (int j = 0; j < proposal.approvers().size(); j++) {
                if (j > 0) out.append(',');
                out.append('"').append(hex.formatHex(proposal.approvers().get(j))).append('"');
            }
            out.append("]}");
        }
        out.append("],\"pauseCommandHex\":\"")
                .append(hex.formatHex(new PaymentsGovernanceV1.Command(status.sequence(),
                        new PaymentsGovernanceV1.Pause()).encode()))
                .append("\",\"resumeCommandHex\":\"")
                .append(hex.formatHex(new PaymentsGovernanceV1.Command(status.sequence(),
                        new PaymentsGovernanceV1.Resume()).encode()))
                .append("\"}");
        return json(200, out.toString());
    }

    private DomainApiResponse command(DomainApiRequest request, String chainId) {
        if (request.method() != DomainHttpMethod.POST
                || !request.queryParameters().keySet().stream().allMatch("dry-run"::equals)) {
            throw invalid();
        }
        byte[] command = request.body();
        if (command.length == 0 || command.length > PaymentsGovernanceV1.MAX_COMMAND_BYTES) throw invalid();
        try {
            PaymentsGovernanceV1.Command.decode(command);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
        String commandHex = HexFormat.of().formatHex(command);
        if (booleanParameter(request.queryParameters(), "dry-run")) {
            context.privilegedSystemMessages().validate(chainId, PaymentsGovernanceV1.TOPIC, command);
            return json(200, "{\"validated\":true,\"commandHex\":\"" + commandHex + "\"}");
        }
        String messageId = context.privilegedSystemMessages().submit(chainId, PaymentsGovernanceV1.TOPIC, command);
        return json(200, "{\"messageId\":\"" + messageId + "\",\"commandHex\":\"" + commandHex + "\"}");
    }

    private static boolean booleanParameter(Map<String, List<String>> parameters, String name) {
        List<String> values = parameters.get(name);
        if (values == null) return false;
        if (values.size() != 1 || !(values.getFirst().equals("true") || values.getFirst().equals("false"))) {
            throw invalid();
        }
        return Boolean.parseBoolean(values.getFirst());
    }

    private static DomainApiException invalid() {
        return new DomainApiException(DomainApiException.Code.INVALID_REQUEST, "Invalid payments governance request");
    }

    private static DomainApiResponse json(int status, String value) {
        return new DomainApiResponse(status, DomainApiMediaType.JSON, value.getBytes(StandardCharsets.UTF_8));
    }
}
