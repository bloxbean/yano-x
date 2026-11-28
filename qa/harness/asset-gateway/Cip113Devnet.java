import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.TransactionSigner;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.clientlib.eval.JulcTransactionEvaluator;
import org.julclang.clientlib.eval.SlotConfig;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.yanoproject.x.assets.cardano.support.settlement.GatewayPlutus;
import org.yanoproject.x.assets.cardano.support.settlement.PayoutTxBuilder;
import org.yanoproject.x.assets.cardano.support.settlement.ProgrammableBackend;
import org.yanoproject.x.assets.cardano.support.settlement.ProgrammableDepositBuilder;
import org.yanoproject.x.assets.cardano.support.settlement.ProgrammableVaultTxAssembler;
import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.cardano.support.settlement.ShardBackend;
import org.yanoproject.x.assets.cardano.support.settlement.VaultPins;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.Cip113Requirements;
import org.yanoproject.x.assets.contracts.Cip113Wiring;
import org.yanoproject.x.assets.contracts.Cip113WiringClaimV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.ProgrammableDepositDatumV1;

import java.io.Reader;
import java.io.Writer;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ADR-061 §10 P7 drill steps on Yaci DevKit: a fixture CIP-113 deployment of the pinned v0.0.1 blueprint whose
 * one-shot policies (protocol params, registry nodes, the token) are native scripts the drill controls, the julc
 * custody scripts h and e and the shard variant as reference scripts, then a user deposit through the D6 deposit
 * builder, a co-signed Accept and a co-signed Settle, all assembled by ProgrammableVaultTxAssembler and validated by
 * the node. Commands: fund | deploy | deposit | accept | settle | hashes | all. State: $CIP113_WORK/state.properties.
 *
 * <p>Build with the M4 tool's classpath and run from the repository root:
 * <pre>
 * ./gradlew -q -I qa/harness/asset-gateway/classpath.gradle -DcpOut=$OUT/cp.txt \
 *     :ledgers:asset-payments:ledger:gatewayDevnetClasspath :capabilities:asset-gateway-cardano-support:jar
 * javac -d $OUT/classes -cp "$(cat $OUT/cp.txt)" qa/harness/asset-gateway/Cip113Devnet.java
 * CIP113_WORK=$OUT/work java -cp "$OUT/classes:$(cat $OUT/cp.txt)" Cip113Devnet all
 * </pre>
 * {@code CIP113_L1} (default {@code http://localhost:8080/api/v1/}) is Yaci DevKit's store API and
 * {@code CIP113_ADMIN} its admin API. The validity window is kept short (120 slots) to stay inside the devnet's
 * time-translation horizon.
 */
public final class Cip113Devnet {
    static final HexFormat HEX = HexFormat.of();
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Path WORK = Path.of(System.getenv().getOrDefault("CIP113_WORK", "cip113-drill"));
    static final String L1 = System.getenv().getOrDefault("CIP113_L1", "http://localhost:8080/api/v1/");
    static final String ADMIN = System.getenv().getOrDefault("CIP113_ADMIN", "http://localhost:10000/local-cluster/api/");
    static final Path BLUEPRINT = Path.of("capabilities/asset-gateway-onchain/src/test/resources/cip113/plutus.json");
    static final Properties STATE = new Properties();
    static final int BOUND = 1024;
    static final String[] WALLETS = {"issuer", "alice", "bob", "m1", "m2", "m3", "fee", "payer"};

    public static void main(String[] args) throws Exception {
        Files.createDirectories(WORK);
        Path stateFile = WORK.resolve("state.properties");
        if (Files.exists(stateFile)) try (Reader in = Files.newBufferedReader(stateFile)) { STATE.load(in); }
        try {
            switch (args.length == 0 ? "all" : args[0]) {
                case "fund" -> fund();
                case "hashes" -> {
                    World w = world();
                    System.out.println("dispatcher " + HEX.formatHex(w.dispatcher()) + "\ntransfer "
                            + HEX.formatHex(w.transfer()) + "\nhook " + HEX.formatHex(w.hook()) + "\nshard "
                            + HEX.formatHex(w.shard().scriptHash()) + "\ne " + HEX.formatHex(w.custody().exitDelegateHash()));
                    for (var entry : SCRIPTS.entrySet()) {
                        System.out.println("script " + entry.getKey() + " -> " + HEX.formatHex(entry.getValue()
                                .getScriptHash()));
                    }
                }
                case "deploy" -> deploy();
                case "deposit" -> deposit();
                case "accept" -> accept();
                case "settle" -> settle();
                case "all" -> { fund(); deploy(); deposit(); accept(); settle(); }
                default -> throw new IllegalArgumentException("fund | deploy | deposit | accept | settle | all");
            }
        } finally {
            try (Writer out = Files.newBufferedWriter(stateFile)) { STATE.store(out, "ADR-061 devnet drill"); }
        }
    }

    // ------------------------------------------------------------------ wallets

    static byte[] seed(String name) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) (0x70 + Arrays.asList(WALLETS).indexOf(name)));
        return seed;
    }

    static byte[] keyHash(String name) {
        return Blake2bUtil.blake2bHash224(KeyGenUtil.getPublicKeyFromPrivateKey(seed(name)));
    }

    static String wallet(String name) {
        return AddressProvider.getEntAddress(Credential.fromKey(keyHash(name)), Networks.testnet()).getAddress();
    }

    static SecretKey secret(String name) throws Exception {
        return SecretKey.create(seed(name));
    }

    static void fund() throws Exception {
        for (String name : WALLETS) {
            String body = "{\"address\":\"" + wallet(name) + "\",\"adaAmount\":20000}";
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(ADMIN + "addresses/topup"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            System.out.println("fund " + name + " " + wallet(name) + " -> " + response.statusCode());
        }
        Thread.sleep(3000);
    }

    // ------------------------------------------------------------------ the fixture deployment

    static ScriptPubkey policy(String purpose) {
        // One native policy per purpose: the issuer's key under a distinct ScriptAll wrapper is not needed; the
        // drill uses distinct wallets' keys so each policy id differs.
        return new ScriptPubkey(HEX.formatHex(keyHash(purpose.equals("token") ? "issuer" : purpose.equals("params")
                ? "m1" : purpose.equals("registry") ? "m2" : purpose.equals("anchor") ? "m3" : purpose.equals("exit")
                ? "bob" : "alice")));
    }

    static byte[] policyId(String purpose) throws Exception {
        return HEX.parseHex(policy(purpose).getPolicyId());
    }

    static final Map<String, PlutusScript> SCRIPTS = new HashMap<>();

    record World(byte[] plb, byte[] dispatcher, byte[] transfer, byte[] thirdParty, byte[] hook,
                 ProgrammableBackend.Custody custody, ShardBackend.ShardScript shard,
                 ProgrammableVaultTxAssembler.Deployment deployment, Cip113Wiring.ApprovedTuple tuple,
                 AssetId token, Hash32 vaultId, Hash32 domain) {
    }

    static World world() throws Exception {
        String text = blueprint();
        byte[] params = policyId("params");
        byte[] registry = policyId("registry");
        Program plb = applied(text, "programmable_logic_base.programmable_logic_base.spend", PlutusData.bytes(params));
        byte[] plbHash = register(plb);
        PlutusData cred = PlutusData.constr(1, PlutusData.bytes(plbHash));
        byte[] transfer = register(applied(text, "transfer.transfer.withdraw", cred, PlutusData.bytes(registry),
                PlutusData.integer(BOUND)));
        byte[] thirdParty = register(applied(text, "third_party.third_party.withdraw", cred, PlutusData.bytes(registry),
                PlutusData.integer(BOUND)));
        byte[] dispatcher = register(applied(text, "programmable_logic_global.programmable_logic_global.withdraw",
                PlutusData.bytes(transfer), PlutusData.bytes(thirdParty), PlutusData.bytes(new byte[28])));
        byte[] hook = register(Program.plutusV3(Term.lam("salt", Term.lam("ctx", Term.const_(Constant.unit()))))
                .applyParams(PlutusData.bytes(new byte[] {0x61})));
        AssetId token = AssetId.of(policyId("token"), "USDX".getBytes(StandardCharsets.US_ASCII));
        Hash32 genesis = Hash32.of(filled(32, 0x33));
        Hash32 domain = GatewayIds.domainId(0, 42, "cip113-drill", genesis, "asset-payments",
                "anchored-claim-vault-v1", 1);
        VaultPins.Anchor anchor = new VaultPins.Anchor(policyId("anchor"), "drill".getBytes(), filled(28, 0x52),
                genesis.bytes(), "asset-payments".getBytes(), "mpf-blake2b256-v1".getBytes(), filled(32, 0x66));
        ProgrammableBackend.Parameters parameters = new ProgrammableBackend.Parameters(domain, token, 0, 0, anchor,
                policyId("shard"), policyId("exit"), filled(28, 0x73), "ap/claim/".getBytes(), "ap/deposit/".getBytes(),
                "ap/x/".getBytes(), "ap/domain-status".getBytes(), 16, 8, BigInteger.valueOf(2_000_000),
                6 * 3_600_000L, 30 * 86_400_000L, 7 * 86_400_000L, plbHash, BOUND);
        ProgrammableBackend.Custody custody = ProgrammableBackend.JULC.derive(parameters);
        SCRIPTS.put(HEX.formatHex(custody.custodyHash()), script(custody.custodyFlat()));
        SCRIPTS.put(HEX.formatHex(custody.exitDelegateHash()), script(custody.exitDelegateFlat()));
        ShardBackend.ShardScript shard = ShardBackend.JULC.deriveProgrammable(policyId("shard"), custody.custodyHash(), 0);
        SCRIPTS.put(HEX.formatHex(shard.scriptHash()), script(shard.flat()));
        byte[] issuance = filled(28, 0xd1);
        Cip113WiringClaimV1.Fields fields = new Cip113WiringClaimV1.Fields(dispatcher, issuance, transfer, thirdParty);
        return new World(plbHash, dispatcher, transfer, thirdParty, hook, custody, shard,
                new ProgrammableVaultTxAssembler.Deployment(plbHash, params, registry, 0, BOUND),
                new Cip113Wiring.ApprovedTuple(fields, transfer, thirdParty, new byte[28]), token,
                GatewayIds.vaultId(domain, token, 0), domain);
    }

    static void deploy() throws Exception {
        World w = world();
        System.out.println("PLB " + HEX.formatHex(w.plb()) + "\nh " + HEX.formatHex(w.custody().custodyHash())
                + "\ne " + HEX.formatHex(w.custody().exitDelegateHash()) + "\nvault " + new Address(w.custody()
                .smartWallet()).toBech32());
        // 1. Reference scripts, each in its own transaction (h alone is 15,200 bytes).
        Map<String, byte[]> refs = new TreeMap<>();
        refs.put("plb", w.plb());
        refs.put("dispatcher", w.dispatcher());
        refs.put("transfer", w.transfer());
        refs.put("hook", w.hook());
        refs.put("h", w.custody().custodyHash());
        refs.put("e", w.custody().exitDelegateHash());
        refs.put("shard", w.shard().scriptHash());
        for (Map.Entry<String, byte[]> ref : refs.entrySet()) {
            if (STATE.containsKey("ref." + ref.getKey())) continue;
            PlutusScript script = SCRIPTS.get(HEX.formatHex(ref.getValue()));
            int bytes = HEX.parseHex(script.getCborHex()).length;
            // Paid to an address no key spends, so no later coin selection consumes a reference script.
            String sink = AddressProvider.getEntAddress(Credential.fromScript(filled(28, 0x5f)), Networks.testnet())
                    .getAddress();
            Tx tx = new Tx().payToAddress(sink, List.of(Amount.lovelace(BigInteger.valueOf(
                    (bytes + 400L) * 4_310L + 1_000_000L))), script).from(wallet("payer"));
            // One extra placeholder witness: a little fee headroom over the builder's estimate.
            STATE.setProperty("ref." + ref.getKey(), submit("ref-" + ref.getKey(), builder().compose(tx)
                    .additionalSignersCount(1).withSigner(SignerProviders.signerFrom(secret("payer")))) + "#0");
        }
        // 2. Register the stake credentials of the dispatcher, the transfer delegate, the hook, h and e.
        if (!STATE.containsKey("registered")) {
            Tx tx = new Tx().from(wallet("payer"));
            List<byte[]> credentials = new ArrayList<>(List.of(w.custody().custodyHash(),
                    w.custody().exitDelegateHash()));
            // The deployment's own credentials are registered once, whatever custody scripts follow.
            if (!STATE.containsKey("registered.base")) credentials.addAll(List.of(w.dispatcher(), w.transfer(), w.hook()));
            for (byte[] hash : credentials) {
                tx.registerStakeAddress(AddressProvider.getRewardAddress(Credential.fromScript(hash), Networks.testnet())
                        .toBech32());
            }
            STATE.setProperty("registered", submit("register", builder().compose(tx).additionalSignersCount(1)
                    .withSigner(SignerProviders.signerFrom(secret("payer")))));
            STATE.setProperty("registered.base", "yes");
        }
        // 3. The protocol-params UTxO, the registry (origin, the token's node), the anchor, the exit-mode thread, the
        //    shard thread, and the token minted to Alice's smart wallet.
        if (!STATE.containsKey("fixtures")) {
            byte[] tokenPolicy = w.token().policyId();
            ConstrPlutusData none = ConstrPlutusData.of(0, BytesPlutusData.of(new byte[0]));
            ConstrPlutusData paramsDatum = ConstrPlutusData.of(0, credential(w.dispatcher()),
                    credential(w.tuple().fields().issuanceLogic()), credential(w.transfer()), credential(w.thirdParty()),
                    credential(filled(28, 0xc9)), ConstrPlutusData.of(1));
            ConstrPlutusData origin = ConstrPlutusData.of(0, BytesPlutusData.of(new byte[0]), BytesPlutusData.of(tokenPolicy),
                    none, none, none, none, BytesPlutusData.of(new byte[0]));
            ConstrPlutusData node = ConstrPlutusData.of(0, BytesPlutusData.of(tokenPolicy),
                    BytesPlutusData.of(filled(30, 0xff)), none, credential(w.hook()), none, none,
                    BytesPlutusData.of(new byte[0]));
            List<com.bloxbean.cardano.client.plutus.spec.PlutusData> members = new ArrayList<>();
            List<byte[]> keys = new ArrayList<>();
            for (String m : List.of("m1", "m2", "m3")) keys.add(KeyGenUtil.getPublicKeyFromPrivateKey(seed(m)));
            keys.sort(Arrays::compareUnsigned);
            keys.forEach(k -> members.add(BytesPlutusData.of(k)));
            ConstrPlutusData anchorDatum = ConstrPlutusData.of(0, BigIntPlutusData.of(2),
                    BytesPlutusData.of("drill".getBytes()), BytesPlutusData.of(filled(32, 0x33)),
                    BytesPlutusData.of("asset-payments".getBytes()), BytesPlutusData.of("mpf-blake2b256-v1".getBytes()),
                    BytesPlutusData.of(filled(32, 0x66)), BigIntPlutusData.of(1), BytesPlutusData.of(filled(32, 0x67)),
                    BytesPlutusData.of(filled(32, 0x44)), listOf(members), BigIntPlutusData.of(2), BigIntPlutusData.of(1));
            String sink = AddressProvider.getEntAddress(Credential.fromScript(filled(28, 0x52)), Networks.testnet())
                    .getAddress();
            String exitAddress = AddressProvider.getEntAddress(Credential.fromScript(filled(28, 0x73)),
                    Networks.testnet()).getAddress();
            Tx tx = new Tx()
                    .mintAssets(policy("params"), asset("ProtocolParams".getBytes(), 1))
                    .mintAssets(policy("registry"), List.of(asset(new byte[0], 1), asset(tokenPolicy, 1)))
                    .mintAssets(policy("anchor"), asset("drill".getBytes(), 1))
                    .mintAssets(policy("exit"), asset(new byte[0], 1))
                    .mintAssets(policy("shard"), asset(new byte[] {4}, 1))
                    .mintAssets(policy("token"), asset("USDX".getBytes(), 10_000))
                    .payToContract(wallet("issuer"), List.of(Amount.ada(3), unit(policyId("params"), "ProtocolParams".getBytes(),
                            1)), paramsDatum)
                    .payToContract(wallet("issuer"), List.of(Amount.ada(3), unit(policyId("registry"), new byte[0], 1)), origin)
                    .payToContract(wallet("issuer"), List.of(Amount.ada(3), unit(policyId("registry"), tokenPolicy, 1)), node)
                    .payToContract(sink, List.of(Amount.ada(3), unit(policyId("anchor"), "drill".getBytes(), 1)), anchorDatum)
                    .payToContract(exitAddress, List.of(Amount.ada(3), unit(policyId("exit"), new byte[0], 1)),
                            ConstrPlutusData.of(0))
                    .payToContract(new Address(w.shard().address()).toBech32(), List.of(Amount.ada(3),
                            unit(policyId("shard"), new byte[] {4}, 1)), shardDatum(4, new byte[32]))
                    .payToAddress(new Address(ProgrammableBackend.keySmartWallet(w.plb(), keyHash("alice"), 0)).toBech32(),
                            List.of(Amount.ada(5), unit(w.token().policyId(), "USDX".getBytes(), 10_000)))
                    .from(wallet("payer"));
            String hash = submit("fixtures", builder().compose(tx).mergeOutputs(false).additionalSignersCount(6)
                    .withSigner(SignerProviders.signerFrom(secret("payer")))
                    .withSigner(SignerProviders.signerFrom(secret("m1"))).withSigner(SignerProviders.signerFrom(secret("m2")))
                    .withSigner(SignerProviders.signerFrom(secret("m3"))).withSigner(SignerProviders.signerFrom(secret("bob")))
                    .withSigner(SignerProviders.signerFrom(secret("alice")))
                    .withSigner(SignerProviders.signerFrom(secret("issuer"))));
            STATE.setProperty("fixtures", hash);
        }
    }

    // ------------------------------------------------------------------ the drill steps

    static void deposit() throws Exception {
        World w = world();
        String fixtures = STATE.getProperty("fixtures");
        ResolvedOutput alice = resolved(fixtures, 6);
        ProgrammableDepositBuilder.Request request = new ProgrammableDepositBuilder.Request(List.of(alice),
                keyHash("alice"), w.custody().smartWallet(), w.token(), BigInteger.valueOf(600),
                BigInteger.valueOf(2_500_000), filled(28, 0xa1), keyHash("alice"), funding(), refs(), slot(),
                slot() + 120);
        ProgrammableDepositBuilder.Built built = ProgrammableDepositBuilder.build(w.deployment(), wiring(w), l1(w),
                adapter(), hash -> BigInteger.ZERO, w.vaultId(), request, pp(), evaluator());
        Transaction tx = sign(built.assembled().transaction(), "payer", "alice");
        STATE.setProperty("deposit", submitSigned("deposit", tx));
        STATE.setProperty("deposit.id", built.depositId().hex());
        System.out.println("deposit-id " + built.depositId().hex() + " origin " + built.datum().originTxId().hex());
    }

    static void accept() throws Exception {
        World w = world();
        ResolvedOutput deposit = resolved(STATE.getProperty("deposit"), 0);
        List<TransactionOutput> outputs = List.of(output(w.custody().smartWallet(), deposit.lovelace(), deposit.assets(),
                GatewayPlutus.continuation(List.of())));
        ProgrammableVaultTxAssembler.Plan plan = new ProgrammableVaultTxAssembler.Plan(List.of(deposit), List.of(),
                funding(), outputs, withAnchor(), List.of(new ProgrammableVaultTxAssembler.CustodyWithdrawal(
                w.custody().custodyHash(), GatewayPlutus.acceptCosigned())), List.of(keyHash("m1"), keyHash("m2")),
                slot(), slot() + 120);
        ProgrammableVaultTxAssembler.Assembled assembled = ProgrammableVaultTxAssembler.assemble(w.deployment(),
                wiring(w), plan, l1(w), adapter(), hash -> BigInteger.ZERO, pp(), evaluator());
        STATE.setProperty("accept", submitSigned("accept", sign(assembled.transaction(), "payer", "m1", "m2")));
    }

    static void settle() throws Exception {
        World w = world();
        ResolvedOutput vault = resolved(STATE.getProperty("accept"), 0);
        ResolvedOutput shardIn = resolved(STATE.getProperty("fixtures"), 5);
        // A floor top-up (FloorTopUpV1, lovelace only): anyone may add one, and Settle's continuation keeps the floor.
        if (!STATE.containsKey("topup")) {
            Tx topUp = new Tx().payToContract(new Address(w.custody().smartWallet()).toBech32(), List.of(Amount.ada(5)),
                    ConstrPlutusData.of(2)).from(wallet("payer"));
            STATE.setProperty("topup", submit("topup", builder().compose(topUp).additionalSignersCount(1)
                    .withSigner(SignerProviders.signerFrom(secret("payer")))));
        }
        ResolvedOutput topUp = resolved(STATE.getProperty("topup"), 0);
        byte[] id = Blake2bUtil.blake2bHash256("drill-claim-1".getBytes());
        id[31] = (byte) ((id[31] & 0xf0) | 4);
        byte[] bobWallet = ProgrammableBackend.keySmartWallet(w.plb(), keyHash("bob"), 0);
        org.yanoproject.x.assets.contracts.ClaimLeafV1 claim = new org.yanoproject.x.assets.contracts.ClaimLeafV1(
                Hash32.of(id), w.vaultId(), org.yanoproject.x.assets.contracts.ClaimKind.USER_WITHDRAWAL,
                org.yanoproject.x.assets.contracts.AssetValue.single(w.token(), BigInteger.valueOf(250)),
                BigInteger.valueOf(1_400_000), bobWallet, 10, 1);
        org.yanoproject.x.assets.cardano.support.settlement.ShardMirror mirror =
                new org.yanoproject.x.assets.cardano.support.settlement.ShardMirror(4);
        var plan = mirror.plan(List.of(claim.claimId()));
        BigInteger quantity = vault.quantityOf(w.token());
        List<TransactionOutput> outputs = List.of(
                output(bobWallet, BigInteger.valueOf(1_400_000), Map.of(w.token(), BigInteger.valueOf(250)), null),
                output(w.custody().smartWallet(), vault.lovelace().add(topUp.lovelace())
                                .subtract(BigInteger.valueOf(1_400_000)),
                        Map.of(w.token(), quantity.subtract(BigInteger.valueOf(250))),
                        GatewayPlutus.continuation(List.of(claim.claimId()))),
                output(w.shard().address(), shardIn.lovelace(), shardIn.assets(), GatewayPlutus.shardDatum(4,
                        plan.after())));
        ProgrammableVaultTxAssembler.Plan txPlan = new ProgrammableVaultTxAssembler.Plan(List.of(vault, topUp),
                List.of(new ProgrammableVaultTxAssembler.ScriptSpend(shardIn, GatewayPlutus.shardRedeemer(
                        plan.inserts()))), funding(), outputs, withAnchor(),
                List.of(new ProgrammableVaultTxAssembler.CustodyWithdrawal(w.custody().custodyHash(),
                        GatewayPlutus.settle(List.of(claim), w.token(), 4))), List.of(keyHash("m1"), keyHash("m2")),
                slot(), slot() + 120);
        ProgrammableVaultTxAssembler.Assembled assembled = ProgrammableVaultTxAssembler.assemble(w.deployment(),
                wiring(w), txPlan, l1(w), adapter(), hash -> BigInteger.ZERO, pp(), evaluator());
        STATE.setProperty("settle", submitSigned("settle", sign(assembled.transaction(), "payer", "m1", "m2")));
    }

    // ------------------------------------------------------------------ the builder's L1 view

    static ProgrammableVaultTxAssembler.LiveWiring wiring(World w) throws Exception {
        return ProgrammableVaultTxAssembler.LiveWiring.of(w.deployment(), resolved(STATE.getProperty("fixtures"), 0),
                List.of(w.tuple()));
    }

    static ProgrammableVaultTxAssembler.L1 l1(World w) throws Exception {
        String fixtures = STATE.getProperty("fixtures");
        ResolvedOutput origin = resolved(fixtures, 1);
        ResolvedOutput node = resolved(fixtures, 2);
        Cip113Requirements.RegistryNode tokenNode = new Cip113Requirements.RegistryNode(
                new ProgrammableDepositDatumV1.Outpoint(node.outpoint().txId(), node.outpoint().index()),
                w.token().policyId(), filled(30, 0xff), new Cip113Requirements.Credential(true, w.hook()), new byte[0]);
        Cip113Requirements.RegistryNode originNode = new Cip113Requirements.RegistryNode(
                new ProgrammableDepositDatumV1.Outpoint(origin.outpoint().txId(), origin.outpoint().index()),
                new byte[0], w.token().policyId(), new Cip113Requirements.Credential(false, new byte[28]), new byte[0]);
        Map<String, ResolvedOutput> known = new HashMap<>();
        known.put(origin.outpoint().toString(), origin);
        known.put(node.outpoint().toString(), node);
        return new ProgrammableVaultTxAssembler.L1() {
            @Override
            public Optional<Cip113Requirements.RegistryNode> node(byte[] policy) {
                return Arrays.equals(policy, w.token().policyId()) ? Optional.of(tokenNode) : Optional.empty();
            }

            @Override
            public Optional<Cip113Requirements.RegistryNode> covering(byte[] policy) {
                return originNode.covers(policy) ? Optional.of(originNode) : Optional.empty();
            }

            @Override
            public Optional<ResolvedOutput> resolve(ProgrammableDepositDatumV1.Outpoint outpoint) {
                return Optional.ofNullable(known.get(new ResolvedOutput.Outpoint(outpoint.txId(),
                        (int) outpoint.index()).toString()));
            }
        };
    }

    static Cip113Requirements.Adapter adapter() {
        return (policy, node) -> Optional.of(new Cip113Requirements.HookCall(new byte[] {(byte) 0xd8, 0x79, (byte) 0x80},
                List.of()));
    }

    static List<ResolvedOutput> refs() throws Exception {
        List<ResolvedOutput> refs = new ArrayList<>();
        for (String name : List.of("plb", "dispatcher", "transfer", "hook", "h", "e", "shard")) {
            String[] at = STATE.getProperty("ref." + name).split("#");
            refs.add(resolved(at[0], Integer.parseInt(at[1])));
        }
        return refs;
    }

    static List<ResolvedOutput> withAnchor() throws Exception {
        List<ResolvedOutput> refs = new ArrayList<>(refs());
        refs.add(resolved(STATE.getProperty("fixtures"), 3));
        refs.add(resolved(STATE.getProperty("fixtures"), 4));
        return refs;
    }

    static PayoutTxBuilder.Funding funding() throws Exception {
        JsonNode utxos = JSON.readTree(get(L1 + "addresses/" + wallet("payer") + "/utxos?count=100"));
        ResolvedOutput best = null;
        for (JsonNode u : utxos) {
            if (u.path("amount").size() != 1 || !u.path("reference_script_hash").isNull()
                    && !u.path("reference_script_hash").asText("").isEmpty()) continue;
            ResolvedOutput out = resolved(u.path("tx_hash").asText(), u.path("output_index").asInt());
            if (out.inlineDatum().isPresent()) continue;
            if (best == null || out.lovelace().compareTo(best.lovelace()) > 0) best = out;
        }
        return new PayoutTxBuilder.Funding(List.of(best), new Address(wallet("payer")).getBytes());
    }

    static ResolvedOutput resolved(String txHash, int index) throws Exception {
        JsonNode node = JSON.readTree(get(L1 + "txs/" + txHash + "/utxos"));
        JsonNode out = null;
        for (JsonNode o : node.path("outputs")) if (o.path("output_index").asInt() == index) out = o;
        if (out == null) throw new IllegalStateException("no output " + txHash + "#" + index);
        TreeMap<AssetId, BigInteger> assets = new TreeMap<>();
        BigInteger lovelace = BigInteger.ZERO;
        for (JsonNode amount : out.path("amount")) {
            String unit = amount.path("unit").asText();
            BigInteger quantity = new BigInteger(amount.path("quantity").asText());
            if (unit.equals("lovelace")) lovelace = quantity;
            else assets.put(AssetId.of(HEX.parseHex(unit.substring(0, 56)), HEX.parseHex(unit.substring(56))), quantity);
        }
        String datum = out.path("inline_datum").asText("");
        String refHash = out.path("reference_script_hash").asText("");
        Optional<ResolvedOutput.ReferenceScript> ref = refHash.isEmpty() || refHash.equals("null") ? Optional.empty()
                : Optional.of(new ResolvedOutput.ReferenceScript(HEX.parseHex(refHash),
                HEX.parseHex(SCRIPTS.get(refHash).getCborHex()).length, Optional.empty()));
        return new ResolvedOutput(new ResolvedOutput.Outpoint(Hash32.ofHex(txHash), index),
                new Address(out.path("address").asText()).getBytes(), lovelace, assets,
                datum.isEmpty() || datum.equals("null") ? Optional.empty() : Optional.of(HEX.parseHex(datum)), ref);
    }

    static long slot() throws Exception {
        return JSON.readTree(get(L1 + "blocks/latest")).path("slot").asLong();
    }

    static ProtocolParams pp() throws Exception {
        return new BFBackendService(L1, "drill").getEpochService().getProtocolParameters().getValue();
    }

    static TransactionEvaluator evaluator() throws Exception {
        JsonNode devnet = JSON.readTree(get(ADMIN + "admin/devnet"));
        SlotConfig slots = new SlotConfig(devnet.path("startTime").asLong() * 1000, 0, 1000);
        UtxoSupplier supplier = new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer count, Integer page, OrderEnum order) {
                return List.of();
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int index) {
                try {
                    return Optional.of(PayoutTxBuilder.utxo(resolved(txHash, index)));
                } catch (Exception missing) {
                    return Optional.empty();
                }
            }
        };
        ProtocolParams params = pp();
        return new JulcTransactionEvaluator(supplier, () -> params, hash -> Optional.ofNullable(SCRIPTS.get(hash)),
                slots);
    }

    // ------------------------------------------------------------------ submission

    static QuickTxBuilder builder() {
        return new QuickTxBuilder(new BFBackendService(L1, "drill"));
    }

    static String submit(String key, QuickTxBuilder.TxContext context) throws Exception {
        return submitSigned(key, context.buildAndSign());
    }

    static Transaction sign(Transaction tx, String... signers) throws Exception {
        Transaction signed = tx;
        for (String name : signers) signed = TransactionSigner.INSTANCE.sign(signed, secret(name));
        return signed;
    }

    static String submitSigned(String key, Transaction tx) throws Exception {
        byte[] cbor = tx.serialize();
        String hash = TransactionUtil.getTxHash(cbor);
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(L1 + "tx/submit"))
                .header("Content-Type", "application/cbor").POST(HttpRequest.BodyPublishers.ofByteArray(cbor)).build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.println(key + " -> " + response.statusCode() + " " + response.body() + " bytes=" + cbor.length);
        if (response.statusCode() / 100 != 2) throw new IllegalStateException(key + " rejected: " + response.body());
        for (int i = 0; i < 90; i++) {
            if (HTTP.send(HttpRequest.newBuilder(URI.create(L1 + "txs/" + hash)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                STATE.setProperty("size." + key, Integer.toString(cbor.length));
                return hash;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException(key + " not confirmed: " + hash);
    }

    static String get(String url) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException(url + " -> " + response.statusCode());
        return response.body();
    }

    // ------------------------------------------------------------------ helpers

    static String blueprint() throws Exception {
        byte[] bytes = Files.readAllBytes(BLUEPRINT);
        String sha = HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!sha.equals("b6c8cb096a15e02f1b9c719fb8c617b624c1aa7719f2258d45faa3e8f144e7b9")) {
            throw new IllegalStateException("not the pinned CIP-113 blueprint");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static Program applied(String text, String title, PlutusData... params) {
        int at = text.indexOf("\"title\": \"" + title + "\"");
        Matcher code = Pattern.compile("\"compiledCode\"\\s*:\\s*\"([0-9a-f]+)\"").matcher(text);
        if (at < 0 || !code.find(at)) throw new IllegalArgumentException(title);
        byte[] single = HEX.parseHex(code.group(1));
        byte[] head = single.length < 256 ? new byte[] {0x58, (byte) single.length}
                : new byte[] {0x59, (byte) (single.length >> 8), (byte) single.length};
        return JulcScriptAdapter.toProgram(HEX.formatHex(head) + code.group(1)).applyParams(params);
    }

    static byte[] register(Program program) {
        byte[] hash = HEX.parseHex(JulcScriptAdapter.scriptHash(program));
        SCRIPTS.put(HEX.formatHex(hash), JulcScriptAdapter.fromProgram(program));
        return hash;
    }

    /** The exact flat program as a reference script, without re-encoding it. */
    static PlutusV3Script script(byte[] flat) {
        return PlutusV3Script.builder().type("PlutusScriptV3").cborHex(HEX.formatHex(cborBytes(cborBytes(flat))))
                .build();
    }

    static byte[] cborBytes(byte[] value) {
        int n = value.length;
        byte[] head = n < 24 ? new byte[] {(byte) (0x40 | n)} : n < 256 ? new byte[] {0x58, (byte) n}
                : new byte[] {0x59, (byte) (n >> 8), (byte) n};
        byte[] out = Arrays.copyOf(head, head.length + n);
        System.arraycopy(value, 0, out, head.length, n);
        return out;
    }

    static ConstrPlutusData credential(byte[] hash) {
        return ConstrPlutusData.of(1, BytesPlutusData.of(hash));
    }

    static com.bloxbean.cardano.client.plutus.spec.ListPlutusData listOf(
            List<com.bloxbean.cardano.client.plutus.spec.PlutusData> items) {
        com.bloxbean.cardano.client.plutus.spec.ListPlutusData list =
                new com.bloxbean.cardano.client.plutus.spec.ListPlutusData();
        items.forEach(list::add);
        return list;
    }

    static ConstrPlutusData shardDatum(int shard, byte[] root) {
        return ConstrPlutusData.of(0, BigIntPlutusData.of(1), BigIntPlutusData.of(shard), BytesPlutusData.of(root));
    }

    static Asset asset(byte[] name, long quantity) {
        return Asset.builder().name("0x" + HEX.formatHex(name)).value(BigInteger.valueOf(quantity)).build();
    }

    static Amount unit(byte[] policy, byte[] name, long quantity) {
        return Amount.asset(HEX.formatHex(policy) + HEX.formatHex(name), BigInteger.valueOf(quantity));
    }

    static TransactionOutput output(byte[] address, BigInteger lovelace, Map<AssetId, BigInteger> assets,
                                    com.bloxbean.cardano.client.plutus.spec.PlutusData datum) {
        TransactionOutput out = TransactionOutput.builder().address(new Address(address).toBech32())
                .value(com.bloxbean.cardano.client.transaction.spec.Value.builder().coin(lovelace).build()).build();
        List<com.bloxbean.cardano.client.transaction.spec.MultiAsset> multi = new ArrayList<>();
        Map<String, List<Asset>> byPolicy = new TreeMap<>();
        new TreeMap<>(assets).forEach((asset, quantity) -> byPolicy.computeIfAbsent(HEX.formatHex(asset.policyId()),
                ignored -> new ArrayList<>()).add(new Asset("0x" + HEX.formatHex(asset.assetName()), quantity)));
        byPolicy.forEach((policy, list) -> multi.add(new com.bloxbean.cardano.client.transaction.spec.MultiAsset(policy,
                list)));
        out.getValue().setMultiAssets(multi);
        if (datum != null) out.setInlineDatum(datum);
        return out;
    }

    static byte[] filled(int length, int value) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        return out;
    }
}
