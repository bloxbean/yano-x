package org.yanoproject.x.eutxo.zk.client;

import org.yanoproject.x.eutxo.zk.contracts.EutxoZkBatchData;
import org.yanoproject.x.eutxo.zk.contracts.EutxoZkProofArtifact;
import org.yanoproject.x.eutxo.zk.contracts.EutxoZkStatement;
import org.yanoproject.x.eutxo.zk.contracts.EutxoZkVerificationKey;

import java.util.Optional;

/** Transport-neutral source for REST, file, relay, or embedded clients. */
public interface EutxoZkDataSource {
    Optional<EutxoZkStatement> statement(String statementDigest);

    Optional<EutxoZkBatchData> batchData(String statementDigest);

    Optional<EutxoZkProofArtifact> proof(String statementDigest);

    Optional<EutxoZkVerificationKey> verificationKey(
            String verificationKeyDigest);
}
