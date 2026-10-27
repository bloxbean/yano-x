package org.yanoproject.x.client;

import org.yanoproject.api.appchain.topics.TopicWire.RegistryCommand;
import org.yanoproject.api.appchain.topics.TopicWire.Signature;
import org.yanoproject.api.appchain.topics.TopicWireCodec;

import java.util.List;
import java.util.Objects;

/** Topic submission over the same bounded REST transport as ordinary app-chain messages. */
public final class TopicClient {
    private final AppChainClient client;

    public TopicClient(AppChainClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public AppChainClient.SubmitResult submit(TopicAuthoring.WriteRequest request, List<Signature> signatures) {
        return client.submit(request.statement().topic(),
                TopicWireCodec.encodeFrame(TopicAuthoring.completeWrite(request, signatures)));
    }

    public AppChainClient.SubmitResult signAndSubmit(TopicAuthoring.WriteRequest request,
                                                    List<? extends TopicAuthoring.Signer> signers) {
        return client.submit(request.statement().topic(),
                TopicWireCodec.encodeFrame(TopicAuthoring.signWrite(request, signers)));
    }

    public AppChainClient.SubmitResult submitRegistry(RegistryCommand command) {
        return client.submit("~topics/registry", TopicWireCodec.encodeRegistryCommand(
                TopicAuthoring.completeRegistry(command.statement(), command.signatures())));
    }
}
