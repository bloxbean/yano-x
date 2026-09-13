package org.yanoproject.x.eutxo.zk.prover;

import org.yanoproject.x.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import org.yanoproject.x.eutxo.zk.contracts.EutxoZkProofArtifact;
import org.yanoproject.x.eutxo.zk.contracts.EutxoZkStatement;
import org.yanoproject.x.eutxo.zk.contracts.EutxoZkVerificationKey;
import org.yanoproject.x.eutxo.zk.zeroj.EutxoBatchProofEngine;
import org.yanoproject.x.eutxo.zk.zeroj.EutxoCeremonyManifest;

import java.nio.file.Path;

/** ZeroJ adapter for the durable prover. */
public final class ZerojEutxoProofBackend implements EutxoProofBackend {
    private final EutxoBatchProofEngine engine;

    private ZerojEutxoProofBackend(EutxoBatchProofEngine engine) {
        this.engine = engine;
    }

    public static ZerojEutxoProofBackend singleParticipantDevelopmentSetup() {
        return new ZerojEutxoProofBackend(
                EutxoBatchProofEngine.singleParticipantDevelopmentSetup());
    }

    public static ZerojEutxoProofBackend singleParticipantDevelopmentSetup(
            Path keyDirectory
    ) {
        return new ZerojEutxoProofBackend(
                EutxoBatchProofEngine.singleParticipantDevelopmentSetup(
                        keyDirectory));
    }

    public static ZerojEutxoProofBackend loadCeremonyBundle(
            Path keyDirectory,
            EutxoCeremonyManifest manifest
    ) {
        return new ZerojEutxoProofBackend(
                EutxoBatchProofEngine.loadCeremonyBundle(
                        keyDirectory, manifest));
    }

    @Override
    public EutxoZkVerificationKey verificationKey() {
        return engine.verificationKey();
    }

    @Override
    public EutxoZkProofArtifact prove(
            EutxoZkStatement statement,
            EutxoKeyPaymentBatch witness,
            String proverId
    ) {
        return engine.prove(statement, witness, proverId);
    }

    @Override
    public boolean verify(EutxoZkProofArtifact artifact) {
        return engine.verify(artifact);
    }

    @Override
    public void close() {
        engine.close();
    }
}
