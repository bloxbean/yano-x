package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;

import java.util.Optional;

/**
 * ADR-059 D8g R1/R2: the forced-withdrawal inbox, one multi-purpose script. A request token is minted only with its
 * outpoint-derived id, an honest {@code postedAtMs} and the requester's signature, never moves, and is burned only
 * with the spend that proves the request processed (or in exit mode).
 */
class ForcedInboxConformanceTest extends GatewayTestBase {
    private static final byte[] REQUESTER = filled(0xa3, 28);
    private static final TxOutRef FIRST = ref(0x20, 1);
    private static final byte[] REQUEST_ID = GatewayIds.forcedRequestId(Hash32.of(filled(0x20, 32)), 1).bytes();

    private static PlutusData request(long postedAtMs, PlutusData amount, byte[] recipient) {
        return PlutusData.constr(0, PlutusData.bytes(filled(0xa1, 28)), PlutusData.bytes(TOKEN_VAULT.bytes()),
                amount, PlutusData.bytes(recipient), PlutusData.bytes(REQUESTER), PlutusData.integer(postedAtMs));
    }

    private static PlutusData honest() {
        return request(NOW + 600_000, PlutusData.constr(0, PlutusData.integer(1_000)),
                GatewayFixtures.enterpriseKeyAddress(filled(0xa2, 28)));
    }

    /** The request transaction: spends {@code FIRST} (smallest) and another input, mints the token to the inbox. */
    private static Tx post(byte[] name, PlutusData datum, Value carried) {
        Tx tx = new Tx();
        tx.spend(keyInput(FIRST, 10_000_000L), null);
        tx.spend(keyInput(ref(0x30, 0), 10_000_000L), null);
        tx.mint = token(INBOX_HASH, name, 1);
        tx.output(out(GatewayFixtures.scriptAddress(INBOX_HASH), carried, datum));
        tx.signer(new PubKeyHash(REQUESTER));
        tx.range = range(NOW, NOW + 600_000);
        return tx;
    }

    private static Value carried(byte[] name) {
        return lovelace(2_000_000).merge(token(INBOX_HASH, name, 1));
    }

    private static PlutusData requestMint() {
        return PlutusData.constr(0);
    }

    @Test
    void aRequestIsMintedWithItsOutpointIdHonestTimeAndRequesterSignature() {
        ok(inbox(), post(REQUEST_ID, honest(), carried(REQUEST_ID)).minting(INBOX_HASH, requestMint()));
        PlutusData all = request(NOW + 600_000, PlutusData.constr(1),
                GatewayFixtures.enterpriseKeyAddress(filled(0xa2, 28)));
        ok(inbox(), post(REQUEST_ID, all, carried(REQUEST_ID)).minting(INBOX_HASH, requestMint()));
    }

    @Test
    void aBackdatedOrFutureDatedPostedAtMsFails() {
        PlutusData backdated = request(NOW, PlutusData.constr(0, PlutusData.integer(1_000)),
                GatewayFixtures.enterpriseKeyAddress(filled(0xa2, 28)));
        rejected(inbox(), post(REQUEST_ID, backdated, carried(REQUEST_ID)).minting(INBOX_HASH, requestMint()));
        Tx wide = post(REQUEST_ID, request(NOW + 600_001, PlutusData.constr(0, PlutusData.integer(1_000)),
                GatewayFixtures.enterpriseKeyAddress(filled(0xa2, 28))), carried(REQUEST_ID));
        wide.range = range(NOW, NOW + 600_001);
        rejected(inbox(), wide.minting(INBOX_HASH, requestMint()));
    }

    @Test
    void aWrongNameMissingSignatureOrMalformedDatumFails() {
        byte[] other = GatewayIds.forcedRequestId(Hash32.of(filled(0x30, 32)), 0).bytes();
        rejected(inbox(), post(other, honest(), carried(other)).minting(INBOX_HASH, requestMint()));

        Tx unsigned = post(REQUEST_ID, honest(), carried(REQUEST_ID));
        unsigned.signers.clear();
        rejected(inbox(), unsigned.minting(INBOX_HASH, requestMint()));

        PlutusData zero = request(NOW + 600_000, PlutusData.constr(0, PlutusData.integer(0)),
                GatewayFixtures.enterpriseKeyAddress(filled(0xa2, 28)));
        rejected(inbox(), post(REQUEST_ID, zero, carried(REQUEST_ID)).minting(INBOX_HASH, requestMint()));
        PlutusData noRecipient = request(NOW + 600_000, PlutusData.constr(1), new byte[0]);
        rejected(inbox(), post(REQUEST_ID, noRecipient, carried(REQUEST_ID)).minting(INBOX_HASH, requestMint()));
    }

    @Test
    void theTokenGoesToTheExactInboxAddressAlone() {
        Tx staked = post(REQUEST_ID, honest(), carried(REQUEST_ID));
        TxOut out = staked.outputs.get(0);
        staked.outputs.set(0, new TxOut(GatewayFixtures.scriptAddressWithStake(INBOX_HASH, filled(0x99, 28)),
                out.value(), out.datum(), Optional.empty()));
        rejected(inbox(), staked.minting(INBOX_HASH, requestMint()));

        Tx elsewhere = post(REQUEST_ID, honest(), carried(REQUEST_ID));
        elsewhere.outputs.set(0, payTo(GatewayFixtures.enterpriseKeyAddress(REQUESTER), carried(REQUEST_ID)));
        rejected(inbox(), elsewhere.minting(INBOX_HASH, requestMint()));

        Tx two = post(REQUEST_ID, honest(), carried(REQUEST_ID));
        two.mint = token(INBOX_HASH, REQUEST_ID, 2);
        two.outputs.set(0, out(GatewayFixtures.scriptAddress(INBOX_HASH),
                lovelace(2_000_000).merge(token(INBOX_HASH, REQUEST_ID, 2)), honest()));
        rejected(inbox(), two.minting(INBOX_HASH, requestMint()));
    }

    // ---- R2: burn with the spend

    private static TxInInfo inboxInput(byte[] name, long postedAtMs) {
        return new TxInInfo(ref(0x79, 0), new TxOut(GatewayFixtures.scriptAddress(INBOX_HASH), carried(name),
                new OutputDatum.OutputDatumInline(request(postedAtMs, PlutusData.constr(1),
                        GatewayFixtures.enterpriseKeyAddress(filled(0xa2, 28)))), Optional.empty()));
    }

    private static Tx burn(PlutusData spendRedeemer, PlutusData mode, byte[] root) {
        Tx tx = new Tx();
        tx.spend(inboxInput(REQUEST_ID, NOW - DAY), spendRedeemer);
        tx.spend(keyInput(FEE_REF, 5_000_000L), null);
        tx.mint = token(INBOX_HASH, REQUEST_ID, -1);
        tx.output(payTo(GatewayFixtures.enterpriseKeyAddress(REQUESTER), lovelace(2_000_000)));
        tx.reference(exitModeInput(mode));
        if (root != null) {
            tx.reference(anchorInput(anchorDatum(2, root, 130, MEMBERS, THRESHOLD, NOW)));
        }
        return tx;
    }

    private static PlutusData burnMint() {
        return PlutusData.constr(1);
    }

    @Test
    void aProcessedRequestIsBurnedWithItsRecordProof() {
        byte[] key = GatewayFixtures.concat(FORCED_PREFIX, REQUEST_ID);
        StateTrie state = new StateTrie().fill("ap/account/", 64).put(key, new byte[] {(byte) 0x82, 0, 0});
        Tx tx = burn(PlutusData.constr(0, state.proof(key)), normalMode(), state.root());
        ok(inbox(), tx.spending(ref(0x79, 0)));
        ok(inbox(), tx.minting(INBOX_HASH, burnMint()));
    }

    @Test
    void anUnprocessedRequestCannotBeBurnedInNormalMode() {
        byte[] key = GatewayFixtures.concat(FORCED_PREFIX, REQUEST_ID);
        StateTrie with = new StateTrie().fill("ap/account/", 64).put(key, new byte[] {(byte) 0x82, 0, 0});
        StateTrie without = new StateTrie().fill("ap/account/", 64);
        rejected(inbox(), burn(PlutusData.constr(0, with.proof(key)), normalMode(), without.root())
                .spending(ref(0x79, 0)));
        rejected(inbox(), burn(PlutusData.constr(1), normalMode(), null).spending(ref(0x79, 0)));
    }

    @Test
    void inExitModeAnyRequestIsBurned() {
        Tx tx = burn(PlutusData.constr(1), exitMode(filled(0x55, 32)), null);
        ok(inbox(), tx.spending(ref(0x79, 0)));
        ok(inbox(), tx.minting(INBOX_HASH, burnMint()));
    }

    @Test
    void theTokenNeverMovesAndTheMinAdaGoesBackToTheRequester() {
        Tx moved = burn(PlutusData.constr(1), exitMode(filled(0x55, 32)), null);
        moved.mint = Value.zero();
        moved.output(out(GatewayFixtures.scriptAddress(INBOX_HASH), carried(REQUEST_ID), honest()));
        rejected(inbox(), moved.spending(ref(0x79, 0)));

        Tx kept = burn(PlutusData.constr(1), exitMode(filled(0x55, 32)), null);
        kept.outputs.set(0, payTo(GatewayFixtures.enterpriseKeyAddress(filled(0xbb, 28)), lovelace(2_000_000)));
        rejected(inbox(), kept.spending(ref(0x79, 0)));
    }

    @Test
    void aBurnIsOnlyMinusOnePerSpentInboxTokenWithNoPositiveMint() {
        Tx unspent = burn(PlutusData.constr(1), exitMode(filled(0x55, 32)), null);
        unspent.inputs.remove(0);
        rejected(inbox(), unspent.minting(INBOX_HASH, burnMint()));

        Tx mixed = burn(PlutusData.constr(1), exitMode(filled(0x55, 32)), null);
        byte[] fresh = GatewayIds.forcedRequestId(Hash32.of(filled(0x60, 32)), 0).bytes();
        mixed.mint = mixed.mint.merge(token(INBOX_HASH, fresh, 1));
        rejected(inbox(), mixed.minting(INBOX_HASH, burnMint()));
        rejected(inbox(), mixed.minting(INBOX_HASH, requestMint()));
    }
}
