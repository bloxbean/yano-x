package org.yanoproject.x.stdlib;

import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppCapabilityManifest;
import org.yanoproject.api.appchain.AppChainInfo;
import org.yanoproject.api.appchain.AppQueryContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.api.appchain.proof.ProofSubjectProvider;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;

import java.util.List;
import java.util.Optional;

/**
 * Catalog-selected, component-local authenticated map for declarative composites.
 *
 * <p>The ordinary {@code authenticated-map} selector remains the existing actors/approvals/map preset.
 * This leaf owns only map entries, receipts, and consumption records. Its workflow supplies explicitly
 * declared actor and approval readers; it never embeds or constructs those participants itself.
 * The ordinary component lifecycle still initializes/verifies map genesis on empty blocks.
 */
public final class AuthenticatedMapLeafStateMachine implements AppStateMachine {
    public static final String ID = "authenticated-map-component";
    private final AuthenticatedMapStateMachine map;
    private final AuthenticatedMapTransitionKernel kernel;
    private static final ProofSubjectProvider PROOF_SUBJECT = StdlibProofSubjectProviders.authenticatedMap();

    /** Creates a leaf around the shared map transition implementation and committed participant ids. */
    AuthenticatedMapLeafStateMachine(AuthenticatedMapStateMachine map, String actors, String approvals) {
        this.map = map;
        this.kernel = new AuthenticatedMapTransitionKernel(map, actors, approvals);
    }

    @Override public String id() { return ID; }
    @Override public void init(AppStateReader state, AppChainInfo info) { map.init(state, info); }
    @Override public Optional<TransitionKernel<?, ?>> transitionKernel() { return Optional.of(kernel); }

    @Override public AppCapabilityManifest capabilityManifest() {
        return StdlibCapabilityManifests.component(ID, List.of(), List.of(
                        AuthenticatedMapContract.POINT_QUERY_PATH, AuthenticatedMapContract.RECEIPT_QUERY_PATH))
                .proofSubject(StdlibProofSubjectProviders.manifest(PROOF_SUBJECT, "authenticated-map-key-v1"))
                .build();
    }

    @Override public List<ProofSubjectProvider> proofSubjectProviders() { return List.of(PROOF_SUBJECT); }

    /**
     * Initializes the map before workflow execution. Ingress belongs to the declarative workflow, not
     * this leaf: routing messages here would omit its explicitly required authorization participants.
     */
    @Override public void apply(AppBlockExecutionContext execution, AppStateWriter state, AppEffectEmitter effects) {
        if (!execution.messages().isEmpty()) {
            throw new IllegalStateException("authenticated-map component commands require a declared workflow");
        }
        map.apply(execution, state, effects);
    }

    @Override public byte[] query(String path, byte[] params, AppQueryContext state) {
        return map.query(path, params, state);
    }
}
