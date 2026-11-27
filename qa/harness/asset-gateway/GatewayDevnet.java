import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.AbstractTx;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsBodies;
import org.yanoproject.x.assetpayments.contracts.PaymentsMessageV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords;
import org.yanoproject.x.assets.cardano.support.settlement.GatewayPlutus;
import org.yanoproject.x.assets.cardano.support.settlement.ShardBackend;
import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DepositDatumV1;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.ExitLeafV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SettlementQueries;
import org.yanoproject.x.walletauth.CardanoCredential;
import org.yanoproject.x.walletauth.IntentEnvelopeV1;
import org.yanoproject.x.walletauth.WalletAuthorizer;
import org.yanoproject.x.walletauth.internal.WalletEd25519;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ADR-059 M4 devnet drill tool (QA only, not shipped): derives the gateway's scripts and node settings from the
 * deployment's one-shot seeds, builds and submits the L1 transactions an operator or a user makes (funding,
 * reference scripts, shard and exit-mode bootstraps, floor top-ups, deposits), signs CIP-30-style wallet intents for
 * the asset-payments ledger, and reads the ledger's committed records over the node REST API.
 *
 * <p>Every key here is a public, fixed devnet key. State lives in {@code $GW_DIR/deploy.properties}. Environment:
 * {@code GW_DIR}, {@code GW_L1} (a node's {@code /api/v1/} base used for L1 queries and submission) and
 * {@code GW_APP} (a node's {@code /api/v1/app-chain/} base). Run {@code GatewayDevnet help} for the commands.
 */
public final class GatewayDevnet {
    static final HexFormat HEX = HexFormat.of();
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final String API_KEY = System.getenv().getOrDefault("GW_APIKEY", "regression-admin-key");
    static final long NETWORK_MAGIC = 42;
    static final int NETWORK_ID = 0;
    static final String APPLICATION = "asset-payments";
    static final String PROFILE_ID = "anchored-claim-vault-v1";
    static final String COMMITMENT_PROFILE = "mpf-blake2b256-v1";
    static final String FPRINT = "91ee14091200f1e24659112d640e877e9177779dcc81dd06117f013e9190082b";
    static final String GENID = "c2b9c92a865dfa7c218a1a6e49f1dd88163372e40466009876458c01609d0d70";
    static final long HOUR = 3_600_000L;
    static final long DAY = 24 * HOUR;
    static final long FLOOR = 2_000_000L;
    static final long EXIT_DELAY_FLOOR = 6 * HOUR;
    static final long EXIT_DELAY_CEILING = 30 * DAY;
    static final long MAX_PAUSE = 7 * DAY;
    static final long HEARTBEAT = 72 * HOUR;
    static final long FORCED_DEADLINE = 72 * HOUR;
    static final long MINT_WINDOW = 10 * 60_000L;
    static final long ANCHOR_MAX_WINDOW = 600_000L;
    static final String CLAIM_PREFIX = "ap/claim/";
    static final String DEPOSIT_PREFIX = "ap/deposit/";
    static final String EXIT_PREFIX = "ap/x/";
    static final String FORCED_PREFIX = "ap/forced/";
    static final String STATUS_KEY = "ap/domain-status";
    /** A 32-byte asset name: the longest a Cardano asset name can be (production-length vault parameter). */
    static final String TOKEN_NAME = "M4-DRILL-TOKEN-USDM-32-BYTE-NAME";

    /** Fixed public devnet wallets: name -> seed byte (32 x that byte). */
    static final Map<String, Integer> WALLETS = new LinkedHashMap<>();

    static {
        WALLETS.put("hub", 0x31);
        WALLETS.put("exitseed", 0x32);
        WALLETS.put("shardseed0", 0x33);
        WALLETS.put("shardseed1", 0x34);
        WALLETS.put("issuer", 0x35);
        WALLETS.put("refs", 0x36);
        WALLETS.put("ownerfee", 0x37);
        WALLETS.put("relayfee", 0x38);
        WALLETS.put("thirdparty", 0x39);
        WALLETS.put("floor", 0x3a);
        WALLETS.put("refstore", 0x3b);
        WALLETS.put("anchor", 0x03);
        WALLETS.put("memberA", 0x01);
        WALLETS.put("memberB", 0x02);
        WALLETS.put("memberC", 0x05);
        for (int i = 1; i <= 12; i++) WALLETS.put("user" + i, 0x40 + i);
    }

    static Path dir;
    static Properties state = new Properties();

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("help")) {
            System.out.println("""
                    commands: wallets | seed <wallet> | fund-plan | distribute <hubTx#idx>
                              derive <chainId> <genesisTimeMs> <julc|aiken> | args <node:a|b|c>
                              mint-token <qty> | send-token <qty> <user>... | publish-refs
                              boot-shards <vault> | boot-exit | floor-topup <vault> <lovelace>
                              deposit <user> <vault> <qty> [lovelace] | register <user>
                              withdraw <user> <vault> <amount> <vaultLovelace> [recipientWallet] [base]
                              transfer <fromUser> <toUser> <vault> <amount>
                              receipt <intentId> | claims | claim <claimId> | balance <user> <vault>
                              deposit-record <vault> <tx> <idx> | wait-tx <tx> [timeoutS] | utxos <address>
                              address <wallet|vault:N|shard:N|exit> | vault-sizes [publish]
                              third-party-exit <vault> [submit]   (a proof Exit built from public data only)""");
            return;
        }
        dir = Path.of(Objects.requireNonNull(System.getenv("GW_DIR"), "GW_DIR"));
        Files.createDirectories(dir);
        Path file = dir.resolve("deploy.properties");
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                state.load(reader);
            }
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        Properties before = (Properties) state.clone();
        try {
            run(args[0], rest);
        } finally {
            // Only a command that changed the deployment rewrites it, so concurrent readers never see a partial file.
            if (!state.equals(before)) {
                Path temporary = dir.resolve("deploy.properties.tmp");
                try (Writer writer = Files.newBufferedWriter(temporary)) {
                    state.store(writer, "ADR-059 M4 devnet deployment");
                }
                Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
        }
    }

    static void run(String command, String[] rest) throws Exception {
        switch (command) {
            case "wallets" -> wallets();
            case "seed" -> System.out.println(HEX.formatHex(seed(rest[0])));
            case "pub" -> System.out.println(HEX.formatHex(pub(rest[0])));
            case "address" -> System.out.println(address(rest[0]));
            case "distribute" -> distribute(rest[0]);
            case "derive" -> derive(rest[0], Long.parseLong(rest[1]), rest[2]);
            case "args" -> nodeArgs(rest[0]);
            case "mint-token" -> mintToken(Long.parseLong(rest[0]));
            case "send-token" -> sendToken(Long.parseLong(rest[0]), Arrays.copyOfRange(rest, 1, rest.length));
            case "publish-refs" -> publishRefs();
            case "boot-shards" -> bootShards(Integer.parseInt(rest[0]));
            case "boot-exit" -> bootExit();
            case "floor-topup" -> floorTopUp(Integer.parseInt(rest[0]), Long.parseLong(rest[1]));
            case "deposit" -> deposit(rest[0], Integer.parseInt(rest[1]), new BigInteger(rest[2]),
                    rest.length > 3 ? Long.parseLong(rest[3]) : 2_000_000L);
            case "register" -> System.out.println(submitIntent(rest[0], "REGISTER_ACCOUNT",
                    PaymentsBodies.REGISTER_ACCOUNT).toString());
            case "withdraw" -> withdraw(rest);
            case "withdraw-many" -> withdrawMany(rest);
            case "measure" -> measure(Long.parseLong(rest[0]), Integer.parseInt(rest[1]),
                    rest.length > 2 ? Integer.parseInt(rest[2]) : 1800);
            case "transfer" -> System.out.println(submitIntent(rest[0], AssetPaymentsContract.TRANSFER,
                    new PaymentsBodies.Transfer(asset(Integer.parseInt(rest[2])), accountId(rest[1]),
                            new BigInteger(rest[3])).encode()).toString());
            case "receipt" -> System.out.println(receipt(Hash32.ofHex(rest[0])));
            case "claims" -> System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(claims()));
            case "claim" -> System.out.println(claim(Hash32.ofHex(rest[0])));
            case "balance" -> System.out.println(balance(rest[0], Integer.parseInt(rest[1])));
            case "deposit-record" -> System.out.println(depositRecord(Integer.parseInt(rest[0]), rest[1],
                    Integer.parseInt(rest[2])));
            case "wait-tx" -> System.out.println(waitTx(rest[0], rest.length > 1 ? Integer.parseInt(rest[1]) : 60));
            case "utxos" -> System.out.println(get(l1() + "addresses/" + address(rest[0]) + "/utxos?count=100"));
            case "third-party-exit" -> thirdPartyExit(Integer.parseInt(rest[0]), rest.length > 1 && rest[1].equals("submit"));
            case "vault-sizes" -> vaultSizes(rest.length > 0 && rest[0].equals("publish"));
            default -> throw new IllegalArgumentException("unknown command " + command);
        }
    }

    // ------------------------------------------------------------------ wallets

    static byte[] seed(String name) {
        Integer fill = WALLETS.get(name);
        if (fill == null) throw new IllegalArgumentException("unknown wallet " + name);
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) (int) fill);
        return seed;
    }

    static byte[] pub(String name) {
        return KeyGenUtil.getPublicKeyFromPrivateKey(seed(name));
    }

    static byte[] keyHash(String name) {
        return Blake2bUtil.blake2bHash224(pub(name));
    }

    static String walletAddress(String name) {
        return AddressProvider.getEntAddress(Credential.fromKey(keyHash(name)), Networks.testnet()).getAddress();
    }

    static byte[] rawAddress(String name) {
        return new Address(walletAddress(name)).getBytes();
    }

    /** A base address of {@code name}'s payment key with the {@code floor} wallet's key as stake part (57 bytes). */
    static String baseAddress(String name) {
        return AddressProvider.getBaseAddress(Credential.fromKey(keyHash(name)), Credential.fromKey(keyHash("floor")),
                Networks.testnet()).getAddress();
    }

    static String address(String name) {
        if (name.startsWith("addr")) return name;
        if (name.startsWith("vault:")) return need("vault." + name.substring(6) + ".address");
        if (name.startsWith("shard:")) return need("vault." + name.substring(6) + ".shard-address");
        if (name.equals("exit")) return need("exit.address");
        return walletAddress(name);
    }

    static SecretKey secret(String name) throws Exception {
        return SecretKey.create(seed(name));
    }

    static byte[] accountId(String user) {
        return WalletAuthorizer.accountId(CardanoCredential.fromAddress(rawAddress(user)));
    }

    static void wallets() {
        for (String name : WALLETS.keySet()) {
            System.out.println(name + " " + walletAddress(name) + " keyhash=" + HEX.formatHex(keyHash(name)));
        }
    }

    // ------------------------------------------------------------------ L1 transactions

    static String l1() {
        String base = System.getenv().getOrDefault("GW_L1", "http://localhost:7371/api/v1/");
        return base.endsWith("/") ? base : base + "/";
    }

    static String app() {
        String base = System.getenv().getOrDefault("GW_APP", "http://localhost:7371/api/v1/app-chain/");
        return base.endsWith("/") ? base : base + "/";
    }

    static QuickTxBuilder builder() {
        return new QuickTxBuilder(new BFBackendService(l1(), "yano-devnet"));
    }

    /** Builds, signs and submits; returns the tx hash and records the serialized size under {@code key}. */
    static String submit(String key, QuickTxBuilder.TxContext context) throws Exception {
        Transaction tx = context.buildAndSign();
        byte[] cbor = tx.serialize();
        String hash = TransactionUtil.getTxHash(cbor);
        String response = null;
        for (int attempt = 0; response == null; attempt++) {
            try {
                response = post(l1() + "tx/submit", cbor, "application/cbor");
            } catch (IllegalStateException busy) {
                // A devnet mempool briefly answers 503 CATCHING_UP after a block; retry, anything else is fatal.
                if (attempt >= 20 || !busy.getMessage().contains("CATCHING_UP")) throw busy;
                Thread.sleep(1000);
            }
        }
        if (!response.replace("\"", "").trim().equalsIgnoreCase(hash)) {
            throw new IllegalStateException("submission of " + key + " failed: " + response);
        }
        state.setProperty("tx." + key, hash);
        state.setProperty("txsize." + key, Integer.toString(cbor.length));
        System.out.println(key + " tx=" + hash + " bytes=" + cbor.length);
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            state.setProperty("txout." + key + "." + i, outputs.get(i).getAddress());
        }
        if (!waitTx(hash, 90)) throw new IllegalStateException(key + " not confirmed: " + hash);
        return hash;
    }

    static boolean waitTx(String hash, int seconds) throws Exception {
        for (int i = 0; i < seconds; i++) {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(l1() + "txs/" + hash))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) return true;
            Thread.sleep(1000);
        }
        return false;
    }

    static Utxo utxo(String txHash, int index) throws Exception {
        JsonNode node = JSON.readTree(get(l1() + "utxos/" + txHash + "/" + index));
        Utxo utxo = new Utxo();
        utxo.setTxHash(txHash);
        utxo.setOutputIndex(index);
        utxo.setAddress(node.path("address").asText());
        List<Amount> amounts = new ArrayList<>();
        for (JsonNode amount : node.path("amount")) {
            amounts.add(new Amount(amount.path("unit").asText(), new BigInteger(amount.path("quantity").asText())));
        }
        utxo.setAmount(amounts);
        return utxo;
    }

    /** One real L1 transaction from the faucet-funded hub UTxO to every drill wallet, so every node sees them. */
    static void distribute(String hubOutpoint) throws Exception {
        String[] parts = hubOutpoint.split("#");
        Utxo hub = utxo(parts[0], Integer.parseInt(parts[1]));
        List<String[]> plan = new ArrayList<>();
        plan.add(new String[] {"anchor", "500"});
        plan.add(new String[] {"exitseed", "20"});
        plan.add(new String[] {"shardseed0", "60"});
        plan.add(new String[] {"shardseed1", "60"});
        plan.add(new String[] {"issuer", "100"});
        plan.add(new String[] {"refs", "250"});
        plan.add(new String[] {"refs", "250"});
        plan.add(new String[] {"floor", "50"});
        for (int i = 0; i < 12; i++) plan.add(new String[] {"ownerfee", "50"});
        for (int i = 0; i < 6; i++) plan.add(new String[] {"relayfee", "50"});
        for (int i = 0; i < 4; i++) plan.add(new String[] {"thirdparty", "50"});
        for (int u = 1; u <= 12; u++) {
            plan.add(new String[] {"user" + u, "300"});
            plan.add(new String[] {"user" + u, "100"});
        }
        Tx tx = new Tx().collectFrom(List.of(hub)).from(walletAddress("hub"));
        for (String[] entry : plan) tx.payToAddress(walletAddress(entry[0]), Amount.ada(Long.parseLong(entry[1])));
        String hash = submit("distribute", builder().compose(tx).mergeOutputs(false)
                .withSigner(SignerProviders.signerFrom(secret("hub"))));
        // The one-shot seeds: the first output of each seed wallet.
        for (String name : List.of("exitseed", "shardseed0", "shardseed1", "anchor")) {
            for (int i = 0; ; i++) {
                String out = state.getProperty("txout.distribute." + i);
                if (out == null) throw new IllegalStateException("no output for " + name);
                if (out.equals(walletAddress(name))) {
                    state.setProperty("seed." + name, hash + "#" + i);
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ derivation (ADR-059 D4 order)

    static String need(String key) {
        String value = state.getProperty(key);
        if (value == null) throw new IllegalStateException("deploy.properties has no " + key);
        return value;
    }

    static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    static Program template(String resource) throws IOException {
        try (InputStream in = Objects.requireNonNull(GatewayDevnet.class.getResourceAsStream(resource), resource)) {
            Matcher matcher = Pattern.compile("\"cborHex\"\\s*:\\s*\"([0-9a-fA-F]+)\"")
                    .matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!matcher.find()) throw new IllegalStateException("no cborHex in " + resource);
            return JulcScriptAdapter.toProgram(matcher.group(1));
        }
    }

    static Program gateway(String name, PlutusData... params) throws IOException {
        return template("/META-INF/plutus/" + name + ".plutus.json").applyParams(params);
    }

    static PlutusData seedTx(String outpoint) {
        return PlutusData.bytes(HEX.parseHex(outpoint.split("#")[0]));
    }

    static PlutusData seedIndex(String outpoint) {
        return PlutusData.integer(Long.parseLong(outpoint.split("#")[1]));
    }

    static String hash(Program program) {
        return JulcScriptAdapter.scriptHash(program);
    }

    static String scriptAddress(String scriptHash) {
        return AddressProvider.getEntAddress(Credential.fromScript(HEX.parseHex(scriptHash)), Networks.testnet())
                .getAddress();
    }

    static int scriptBytes(Program program) {
        return HEX.parseHex(JulcScriptAdapter.fromProgram(program).getCborHex()).length;
    }

    /**
     * The app ledger's effective state genesis id, which the anchor datum carries: the configured
     * {@code state.genesis-id} with the framework's application profiles mixed in (AppLedgerRuntime), as
     * {@code GET /app-chain/state/identity} reports it. The vault, exit-mode and inbox scripts pin it, and the
     * GatewayDomain uses it as the domain's genesis id.
     */
    static String chainGenesis() {
        return state.getProperty("chain-genesis-id", GENID);
    }

    static Hash32 domain() {
        return GatewayIds.domainId(NETWORK_ID, NETWORK_MAGIC, need("chain-id"), Hash32.ofHex(chainGenesis()), APPLICATION,
                PROFILE_ID, 1);
    }

    static AssetId asset(int vault) {
        return vault == 0 ? AssetId.ADA : AssetId.of(HEX.parseHex(need("token.policy")), ascii(TOKEN_NAME));
    }

    static Hash32 vaultId(int vault) {
        return GatewayIds.vaultId(domain(), asset(vault), 0);
    }

    static void derive(String chainId, long genesisTimeMs, String backend) throws Exception {
        if (chainId.getBytes(StandardCharsets.UTF_8).length > 32) throw new IllegalArgumentException("chain id > 32");
        state.setProperty("chain-id", chainId);
        state.setProperty("chain-genesis-id", System.getenv().getOrDefault("GW_CHAIN_GENESIS", GENID));
        state.setProperty("genesis-time-ms", Long.toString(genesisTimeMs));
        state.setProperty("shard-backend", backend);
        state.setProperty("stability-depth", System.getenv().getOrDefault("GW_DEPTH", "4"));
        byte[] anchorName = chainId.getBytes(StandardCharsets.UTF_8);
        // Anchor v2 identity from the anchor wallet's only UTxO (ScriptAnchorService picks the largest pure-ADA one).
        String anchorSeed = need("seed.anchor");
        Program anchorPolicy = template("/META-INF/plutus/AnchorThreadPolicy.plutus.json")
                .applyParams(seedTx(anchorSeed), seedIndex(anchorSeed));
        String anchorPolicyId = hash(anchorPolicy);
        Program anchorValidator = template("/META-INF/plutus/AnchorValidatorV2.plutus.json")
                .applyParams(PlutusData.bytes(HEX.parseHex(anchorPolicyId)), PlutusData.integer(ANCHOR_MAX_WINDOW));
        String anchorHash = hash(anchorValidator);
        state.setProperty("anchor.policy-id", anchorPolicyId);
        state.setProperty("anchor.script-hash", anchorHash);
        state.setProperty("anchor.asset-name", HEX.formatHex(anchorName));
        PlutusData[] anchor = {PlutusData.bytes(HEX.parseHex(anchorPolicyId)), PlutusData.bytes(anchorName),
                PlutusData.bytes(HEX.parseHex(anchorHash)), PlutusData.bytes(HEX.parseHex(chainGenesis())),
                PlutusData.bytes(ascii(APPLICATION)), PlutusData.bytes(ascii(COMMITMENT_PROFILE)),
                PlutusData.bytes(HEX.parseHex(FPRINT))};
        // Native token: a single-key native script of the issuer.
        ScriptPubkey policy = new ScriptPubkey(HEX.formatHex(keyHash("issuer")));
        state.setProperty("token.policy", policy.getPolicyId());
        state.setProperty("token.name-hex", HEX.formatHex(ascii(TOKEN_NAME)));
        Hash32 domain = domain();
        state.setProperty("domain-id", domain.hex());
        // D4 step 3: exit-mode policy, inbox, exit-mode validator.
        String exitSeed = need("seed.exitseed");
        Program exitPolicy = gateway("ExitModeThreadPolicy", seedTx(exitSeed), seedIndex(exitSeed));
        String exitPolicyId = hash(exitPolicy);
        List<PlutusData> inboxParams = new ArrayList<>(List.of(PlutusData.bytes(domain.bytes())));
        inboxParams.addAll(List.of(anchor));
        inboxParams.addAll(List.of(PlutusData.bytes(HEX.parseHex(exitPolicyId)), PlutusData.bytes(ascii(FORCED_PREFIX)),
                PlutusData.integer(MINT_WINDOW)));
        Program inbox = gateway("ForcedInboxValidator", inboxParams.toArray(PlutusData[]::new));
        String inboxPolicy = hash(inbox);
        List<PlutusData> exitParams = new ArrayList<>(List.of(PlutusData.bytes(HEX.parseHex(exitPolicyId))));
        exitParams.addAll(List.of(anchor));
        exitParams.addAll(List.of(PlutusData.bytes(HEX.parseHex(inboxPolicy)), PlutusData.integer(HEARTBEAT),
                PlutusData.integer(FORCED_DEADLINE), PlutusData.integer(MAX_PAUSE), PlutusData.bytes(ascii(FORCED_PREFIX)),
                PlutusData.bytes(ascii(STATUS_KEY))));
        Program exitValidator = gateway("ExitModeThreadValidator", exitParams.toArray(PlutusData[]::new));
        String exitHash = hash(exitValidator);
        state.setProperty("exit.policy-id", exitPolicyId);
        state.setProperty("exit.script-hash", exitHash);
        state.setProperty("exit.address", scriptAddress(exitHash));
        state.setProperty("inbox.policy-id", inboxPolicy);
        // D4 steps 4-7 per vault.
        ShardBackend shardBackend = ShardBackend.parse(backend);
        for (int v = 0; v < 2; v++) {
            AssetId asset = asset(v);
            String shardSeed = need("seed.shardseed" + v);
            Program shardPolicy = gateway("AssetShardThreadPolicy", seedTx(shardSeed), seedIndex(shardSeed));
            String shardPolicyId = hash(shardPolicy);
            Program vault = gateway("AssetVaultValidator", vaultParams(domain, asset, anchor, shardPolicyId, exitPolicyId,
                    exitHash));
            String vaultHash = hash(vault);
            Program shard = gateway(backend.equals("aiken") ? "AssetShardValidatorAiken" : "AssetShardValidator",
                    PlutusData.bytes(HEX.parseHex(shardPolicyId)), PlutusData.bytes(HEX.parseHex(vaultHash)));
            String shardHash = hash(shard);
            String derived = HEX.formatHex(shardBackend.derive(HEX.parseHex(shardPolicyId), HEX.parseHex(vaultHash),
                    NETWORK_ID).scriptHash());
            if (!derived.equals(shardHash)) throw new IllegalStateException("ShardBackend.derive disagrees with julc");
            String p = "vault." + v + ".";
            state.setProperty(p + "asset", asset.isAda() ? "lovelace" : asset.toString());
            state.setProperty(p + "vault-id", GatewayIds.vaultId(domain, asset, 0).hex());
            state.setProperty(p + "script-hash", vaultHash);
            state.setProperty(p + "address", scriptAddress(vaultHash));
            state.setProperty(p + "script-bytes", Integer.toString(scriptBytes(vault)));
            state.setProperty(p + "shard-policy-id", shardPolicyId);
            state.setProperty(p + "shard-script-hash", shardHash);
            state.setProperty(p + "shard-address", scriptAddress(shardHash));
            state.setProperty(p + "shard-script-bytes", Integer.toString(scriptBytes(shard)));
            System.out.println("vault " + v + " " + state.getProperty(p + "asset") + " hash=" + vaultHash + " bytes="
                    + scriptBytes(vault) + " shard=" + shardHash + " (" + backend + ", " + scriptBytes(shard) + " bytes)");
        }
        System.out.println("anchor policy=" + anchorPolicyId + " script=" + anchorHash);
        System.out.println("exit-mode policy=" + exitPolicyId + " script=" + exitHash + " inbox=" + inboxPolicy);
    }

    static PlutusData[] vaultParams(Hash32 domain, AssetId asset, PlutusData[] anchor, String shardPolicyId,
                                    String exitPolicyId, String exitHash) {
        List<PlutusData> params = new ArrayList<>(List.of(PlutusData.bytes(domain.bytes()),
                PlutusData.bytes(asset.policyId()), PlutusData.bytes(asset.assetName()), PlutusData.integer(0),
                PlutusData.integer(NETWORK_ID)));
        params.addAll(List.of(anchor));
        params.addAll(List.of(PlutusData.bytes(HEX.parseHex(shardPolicyId)), PlutusData.bytes(HEX.parseHex(exitPolicyId)),
                PlutusData.bytes(HEX.parseHex(exitHash)), PlutusData.bytes(ascii(CLAIM_PREFIX)),
                PlutusData.bytes(ascii(DEPOSIT_PREFIX)), PlutusData.bytes(ascii(EXIT_PREFIX)),
                PlutusData.bytes(ascii(STATUS_KEY)), PlutusData.integer(16), PlutusData.integer(8),
                PlutusData.integer(FLOOR), PlutusData.integer(EXIT_DELAY_FLOOR), PlutusData.integer(EXIT_DELAY_CEILING),
                PlutusData.integer(MAX_PAUSE)));
        return params.toArray(PlutusData[]::new);
    }

    static Program vaultProgram(int v) throws IOException {
        byte[] anchorName = HEX.parseHex(need("anchor.asset-name"));
        PlutusData[] anchor = {PlutusData.bytes(HEX.parseHex(need("anchor.policy-id"))), PlutusData.bytes(anchorName),
                PlutusData.bytes(HEX.parseHex(need("anchor.script-hash"))), PlutusData.bytes(HEX.parseHex(chainGenesis())),
                PlutusData.bytes(ascii(APPLICATION)), PlutusData.bytes(ascii(COMMITMENT_PROFILE)),
                PlutusData.bytes(HEX.parseHex(FPRINT))};
        Program vault = gateway("AssetVaultValidator", vaultParams(domain(), asset(v), anchor,
                need("vault." + v + ".shard-policy-id"), need("exit.policy-id"), need("exit.script-hash")));
        if (!hash(vault).equals(need("vault." + v + ".script-hash"))) throw new IllegalStateException("vault drift");
        return vault;
    }

    static Program shardProgram(int v) throws IOException {
        Program shard = gateway(need("shard-backend").equals("aiken") ? "AssetShardValidatorAiken"
                : "AssetShardValidator", PlutusData.bytes(HEX.parseHex(need("vault." + v + ".shard-policy-id"))),
                PlutusData.bytes(HEX.parseHex(need("vault." + v + ".script-hash"))));
        if (!hash(shard).equals(need("vault." + v + ".shard-script-hash"))) throw new IllegalStateException("drift");
        return shard;
    }

    /**
     * The applied vault's size with production-length parameters: a 32-byte asset name, a 32-byte chain id as the
     * anchor's thread name, and the longest claim, deposit, exit and status keys a composite component can have.
     */
    static void vaultSizes(boolean publish) throws Exception {
        PlutusData[] anchor = {PlutusData.bytes(new byte[28]), PlutusData.bytes(new byte[32]),
                PlutusData.bytes(new byte[28]), PlutusData.bytes(new byte[32]), PlutusData.bytes(new byte[64]),
                PlutusData.bytes(ascii(COMMITMENT_PROFILE)), PlutusData.bytes(new byte[32])};
        AssetId token = AssetId.of(new byte[28], new byte[32]);
        for (int prefix : new int[] {9, 32, 64}) {
            byte[] p = new byte[prefix];
            Arrays.fill(p, (byte) 'k');
            List<PlutusData> params = new ArrayList<>(List.of(PlutusData.bytes(new byte[32]),
                    PlutusData.bytes(token.policyId()), PlutusData.bytes(token.assetName()), PlutusData.integer(0),
                    PlutusData.integer(1)));
            params.addAll(List.of(anchor));
            params.addAll(List.of(PlutusData.bytes(new byte[28]), PlutusData.bytes(new byte[28]),
                    PlutusData.bytes(new byte[28]), PlutusData.bytes(p), PlutusData.bytes(p), PlutusData.bytes(p),
                    PlutusData.bytes(p), PlutusData.integer(16), PlutusData.integer(8), PlutusData.integer(FLOOR),
                    PlutusData.integer(EXIT_DELAY_FLOOR), PlutusData.integer(EXIT_DELAY_CEILING),
                    PlutusData.integer(MAX_PAUSE)));
            Program vault = gateway("AssetVaultValidator", params.toArray(PlutusData[]::new));
            System.out.println("vault, 32-byte asset name, 32-byte anchor name, 64-byte application id, key prefixes of "
                    + prefix + " bytes: " + scriptBytes(vault) + " bytes");
            if (publish && prefix == 64) {
                PlutusV3Script script = JulcScriptAdapter.fromProgram(vault);
                long lovelace = (scriptBytes(vault) + 400L) * 4_310L + 1_000_000L;
                Tx tx = new Tx().payToAddress(walletAddress("refstore"), Amount.lovelace(BigInteger.valueOf(lovelace)),
                        script).from(walletAddress("hub"));
                submit("ref-vault-longest-params", builder().compose(tx).mergeOutputs(false)
                        .withSigner(SignerProviders.signerFrom(secret("hub"))));
            }
        }
    }

    // ------------------------------------------------------------------ node settings

    static void nodeArgs(String node) {
        List<String> args = new ArrayList<>();
        String m = "-Dyano.app-chain.machines.asset-payments.";
        args.add("-Dyano.app-chain.state-machine=asset-payments");
        args.add(m + "domain.address-network-id=" + NETWORK_ID);
        args.add(m + "domain.network-magic=" + NETWORK_MAGIC);
        args.add(m + "domain.genesis-id=" + chainGenesis());
        args.add(m + "deposit-observer-id=deposits");
        args.add(m + "payout-observer-id=payouts");
        args.add(m + "exit-delay-ms=" + EXIT_DELAY_FLOOR);
        args.add(m + "min-stability-depth=" + need("stability-depth"));
        args.add(m + "settlement.min-queued-vault-lovelace=1000000");
        String chain = need("chain-id");
        for (String observer : List.of("deposits", "payouts", "shardboot")) {
            String o = "-Dyano.app-chain.observers." + observer + ".";
            args.add(o + "type=" + switch (observer) {
                case "deposits" -> "asset-vault-deposit-v1";
                case "payouts" -> "asset-vault-payout-v1";
                default -> "asset-shard-bootstrap-v1";
            });
            args.add(o + "domain.address-network-id=" + NETWORK_ID);
            args.add(o + "domain.network-magic=" + NETWORK_MAGIC);
            args.add(o + "domain.chain-id=" + chain);
            args.add(o + "domain.genesis-id=" + chainGenesis());
            args.add(o + "min-stability-depth=" + need("stability-depth"));
            if (!observer.equals("deposits")) args.add(o + "shard-backend=" + need("shard-backend"));
        }
        String x = "-Dyano.app-chain.effects.executors.asset-settlement.";
        for (int v = 0; v < 2; v++) {
            String p = "vault." + v + ".";
            String key = "vaults." + v + ".";
            args.add(m + key + "asset=" + need(p + "asset"));
            args.add(m + key + "script-hash=" + need(p + "script-hash"));
            args.add(m + key + "address=" + need(p + "address"));
            args.add(m + key + "ticker=" + (v == 0 ? "ADA" : "USDM"));
            args.add(m + key + "decimals=6");
            for (String observer : List.of("deposits", "payouts", "shardboot")) {
                String o = "-Dyano.app-chain.observers." + observer + "." + key;
                args.add(o + "asset=" + need(p + "asset"));
                args.add(o + "script-hash=" + need(p + "script-hash"));
                args.add(o + "address=" + need(p + "address"));
                if (!observer.equals("deposits")) {
                    args.add(o + "shard-policy-id=" + need(p + "shard-policy-id"));
                    args.add(o + "shard-script-hash=" + need(p + "shard-script-hash"));
                    args.add(o + "shard-address=" + need(p + "shard-address"));
                }
            }
            args.add(x + key + "asset=" + need(p + "asset"));
            args.add(x + key + "script-hash=" + need(p + "script-hash"));
            args.add(x + key + "address=" + need(p + "address"));
            args.add(x + key + "shard-policy-id=" + need(p + "shard-policy-id"));
            args.add(x + key + "shard-script-hash=" + need(p + "shard-script-hash"));
            args.add(x + key + "shard-address=" + need(p + "shard-address"));
            args.add(x + key + "script-ref=" + need("ref.vault." + v));
            args.add(x + key + "shard-script-ref=" + need("ref.shard." + v));
        }
        args.add(x + "domain.address-network-id=" + NETWORK_ID);
        args.add(x + "domain.network-magic=" + NETWORK_MAGIC);
        args.add(x + "domain.chain-id=" + chain);
        args.add(x + "domain.genesis-id=" + chainGenesis());
        args.add(x + "shard-backend=" + need("shard-backend"));
        args.add(x + "anchor.thread-policy-id=" + need("anchor.policy-id"));
        args.add(x + "anchor.thread-asset-name=" + need("anchor.asset-name"));
        args.add(x + "anchor.script-hash=" + need("anchor.script-hash"));
        args.add(x + "anchor.genesis-id=" + chainGenesis());
        args.add(x + "anchor.application-id=" + APPLICATION);
        args.add(x + "anchor.commitment-profile=" + COMMITMENT_PROFILE);
        args.add(x + "anchor.format-fingerprint=" + FPRINT);
        args.add(x + "exit-mode.policy-id=" + need("exit.policy-id"));
        args.add(x + "exit-mode.script-hash=" + need("exit.script-hash"));
        args.add(x + "continuation-floor=" + FLOOR);
        args.add(x + "slot.zero-time-ms=" + need("genesis-time-ms"));
        args.add(x + "slot.zero-slot=0");
        args.add(x + "slot.length-ms=200");
        args.add(x + "stability-window-ms=60000");
        args.add(x + "inclusion-timeout-ms=180000");
        args.add(x + "round-timeout-ms=20000");
        args.add(x + "pending-ttl-file=" + dir.resolve("node-" + node + "-pending-ttl"));
        switch (node) {
            case "c" -> {
                args.add(x + "owner=true");
                args.add(x + "fee-wallet.key-file=" + keyFile("ownerfee"));
                args.add(x + "accept.interval-ms=15000");
                args.add(x + "max-supplement-per-payout=1000000");
                args.add(x + "exit-relayer.enabled=" + System.getenv().getOrDefault("GW_OWNER_RELAYS", "false"));
            }
            case "b" -> {
                args.add(x + "owner=false");
                args.add(x + "exit-relayer.enabled=" + System.getenv().getOrDefault("GW_B_RELAYS", "true"));
                args.add(x + "exit-relayer.interval-ms=20000");
                args.add(x + "exit-relayer.fee-wallet.key-file=" + keyFile("relayfee"));
                args.add(x + "accept.interval-ms=15000");
            }
            default -> args.add(x + "owner=false");
        }
        args.forEach(System.out::println);
    }

    static String keyFile(String wallet) {
        try {
            Path file = dir.resolve(wallet + ".seed");
            Files.writeString(file, HEX.formatHex(seed(wallet)));
            return file.toString();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    // ------------------------------------------------------------------ token, reference scripts, bootstraps

    static void mintToken(long quantity) throws Exception {
        ScriptPubkey policy = new ScriptPubkey(HEX.formatHex(keyHash("issuer")));
        Asset asset = Asset.builder().name("0x" + HEX.formatHex(ascii(TOKEN_NAME))).value(BigInteger.valueOf(quantity))
                .build();
        Tx tx = new Tx().mintAssets(policy, asset, walletAddress("issuer")).from(walletAddress("issuer"));
        submit("mint-token", builder().compose(tx).withSigner(SignerProviders.signerFrom(secret("issuer"))));
    }

    static String tokenUnit() {
        return need("token.policy") + HEX.formatHex(ascii(TOKEN_NAME));
    }

    static void sendToken(long quantity, String[] users) throws Exception {
        Tx tx = new Tx().from(walletAddress("issuer"));
        for (String user : users) {
            tx.payToAddress(walletAddress(user), List.of(Amount.ada(3), Amount.asset(tokenUnit(),
                    BigInteger.valueOf(quantity))));
        }
        submit("send-token-" + users[0], builder().compose(tx).mergeOutputs(false)
                .withSigner(SignerProviders.signerFrom(secret("issuer"))));
    }

    /** Each vault's and shard's reference script in its own transaction; records its size (16,384-byte limit). */
    static void publishRefs() throws Exception {
        for (int v = 0; v < 2; v++) {
            for (String kind : List.of("vault", "shard")) {
                Program program = kind.equals("vault") ? vaultProgram(v) : shardProgram(v);
                PlutusV3Script script = JulcScriptAdapter.fromProgram(program);
                int bytes = HEX.parseHex(script.getCborHex()).length;
                long lovelace = (bytes + 400L) * 4_310L + 1_000_000L;
                // Paid by the hub to a wallet that never spends, so no later coin selection consumes a reference
                // script (and pays the Conway reference-script fee for it).
                Tx tx = new Tx().payToAddress(walletAddress("refstore"), Amount.lovelace(BigInteger.valueOf(lovelace)),
                        script).from(walletAddress("hub"));
                String key = "ref-" + kind + "-" + v;
                String hash = submit(key, builder().compose(tx).mergeOutputs(false)
                        .withSigner(SignerProviders.signerFrom(secret("hub"))));
                state.setProperty("ref." + kind + "." + v, hash + "#0");
                state.setProperty("ref." + kind + "." + v + ".script-bytes", Integer.toString(bytes));
            }
        }
    }

    static void bootShards(int v) throws Exception {
        String seed = need("seed.shardseed" + v);
        String[] parts = seed.split("#");
        Program program = gateway("AssetShardThreadPolicy", seedTx(seed), seedIndex(seed));
        PlutusV3Script policy = JulcScriptAdapter.fromProgram(program);
        String policyId = hash(program);
        if (!policyId.equals(need("vault." + v + ".shard-policy-id"))) throw new IllegalStateException("policy drift");
        List<Asset> tokens = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            tokens.add(Asset.builder().name("0x" + HEX.formatHex(new byte[] {(byte) i})).value(BigInteger.ONE).build());
        }
        String wallet = "shardseed" + v;
        ScriptTx tx = new ScriptTx().collectFrom(utxo(parts[0], Integer.parseInt(parts[1])))
                .mintAsset(policy, tokens, BigIntPlutusData.of(0));
        for (int i = 0; i < 16; i++) {
            tx.payToContract(need("vault." + v + ".shard-address"), List.of(Amount.ada(2),
                    Amount.asset(policyId + HEX.formatHex(new byte[] {(byte) i}), 1)),
                    GatewayPlutus.shardDatum(i, new byte[32]));
        }
        tx.withChangeAddress(walletAddress(wallet), (com.bloxbean.cardano.client.plutus.spec.PlutusData) null);
        submit("boot-shards-" + v, builder().compose(tx).feePayer(walletAddress(wallet))
                .collateralPayer(walletAddress(wallet)).mergeOutputs(false)
                .withSigner(SignerProviders.signerFrom(secret(wallet))));
    }

    static void bootExit() throws Exception {
        String seed = need("seed.exitseed");
        String[] parts = seed.split("#");
        Program program = gateway("ExitModeThreadPolicy", seedTx(seed), seedIndex(seed));
        PlutusV3Script policy = JulcScriptAdapter.fromProgram(program);
        String policyId = hash(program);
        if (!policyId.equals(need("exit.policy-id"))) throw new IllegalStateException("policy drift");
        ScriptTx tx = new ScriptTx().collectFrom(utxo(parts[0], Integer.parseInt(parts[1])))
                .mintAsset(policy, List.of(Asset.builder().name("").value(BigInteger.ONE).build()),
                        ConstrPlutusData.of(0))
                .payToContract(need("exit.address"), List.of(Amount.ada(2), Amount.asset(policyId, 1)),
                        GatewayPlutus.normalMode());
        tx.withChangeAddress(walletAddress("exitseed"), (com.bloxbean.cardano.client.plutus.spec.PlutusData) null);
        submit("boot-exit", builder().compose(tx).feePayer(walletAddress("exitseed"))
                .collateralPayer(walletAddress("exitseed")).mergeOutputs(false)
                .withSigner(SignerProviders.signerFrom(secret("exitseed"))));
    }

    static void floorTopUp(int v, long lovelace) throws Exception {
        Tx tx = new Tx().payToContract(need("vault." + v + ".address"), Amount.lovelace(BigInteger.valueOf(lovelace)),
                ConstrPlutusData.of(2)).from(walletAddress("floor"));
        submit("floor-" + v + "-" + System.currentTimeMillis(), builder().compose(tx)
                .withSigner(SignerProviders.signerFrom(secret("floor"))));
    }

    static void deposit(String user, int v, BigInteger quantity, long lovelace) throws Exception {
        DepositDatumV1 datum = new DepositDatumV1(accountId(user), rawAddress(user), keyHash(user));
        com.bloxbean.cardano.client.plutus.spec.PlutusData plutus =
                com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(datum.encode());
        List<Amount> amounts = v == 0 ? List.of(Amount.lovelace(quantity))
                : List.of(Amount.lovelace(BigInteger.valueOf(lovelace)), Amount.asset(tokenUnit(), quantity));
        Tx tx = new Tx().payToContract(need("vault." + v + ".address"), amounts, plutus).from(walletAddress(user));
        String key = "deposit-" + user + "-" + v + "-" + System.currentTimeMillis();
        String hash = submit(key, builder().compose(tx).mergeOutputs(false)
                .withSigner(SignerProviders.signerFrom(secret(user))));
        Hash32 depositId = GatewayIds.depositId(vaultId(v), Hash32.ofHex(hash), 0);
        System.out.println("{\"tx\":\"" + hash + "\",\"index\":0,\"depositId\":\"" + depositId.hex() + "\"}");
    }

    // ------------------------------------------------------------------ wallet intents (D13)

    static IntentEnvelopeV1.Domain intentDomain() {
        return new IntentEnvelopeV1.Domain(NETWORK_MAGIC, NETWORK_ID, need("chain-id"), Hash32.ofHex(chainGenesis()),
                APPLICATION);
    }

    /** CIP-30 {@code signData} with the user's enterprise address in the protected header. */
    static byte[][] signData(String user, byte[] payload) {
        byte[] address = rawAddress(user);
        byte[] protectedHeader = cat(new byte[] {(byte) 0xa2, 0x01, 0x27, 0x67}, ascii("address"),
                bstrHead(address.length), address);
        byte[] sigStructure = cat(new byte[] {(byte) 0x84, 0x6a}, ascii("Signature1"), bstrHead(protectedHeader.length),
                protectedHeader, new byte[] {0x40}, bstrHead(payload.length), payload);
        byte[] signature = WalletEd25519.sign(sigStructure, seed(user));
        byte[] sign1 = cat(new byte[] {(byte) 0x84}, bstrHead(protectedHeader.length), protectedHeader,
                new byte[] {(byte) 0xa0}, bstrHead(payload.length), payload, bstrHead(64), signature);
        byte[] key = cat(new byte[] {(byte) 0xa4, 0x01, 0x01, 0x03, 0x27, 0x20, 0x06, 0x21, 0x58, 0x20}, pub(user));
        return new byte[][] {sign1, key};
    }

    static ObjectNode submitIntent(String user, String action, byte[] body) throws Exception {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        IntentEnvelopeV1 envelope = new IntentEnvelopeV1(intentDomain(), action, accountId(user),
                new IntentEnvelopeV1.Owner(), nonce, System.currentTimeMillis() + 300_000, body);
        byte[][] cose = signData(user, envelope.encode());
        byte[] message = new PaymentsMessageV1.Owner(cose[0], cose[1]).encode();
        Hash32 intentId = GatewayIds.intentId(envelope.encode());
        ObjectNode request = JSON.createObjectNode();
        request.put("topic", AssetPaymentsContract.INTENT_TOPIC);
        request.put("bodyHex", HEX.formatHex(message));
        String response = post(app() + "messages", JSON.writeValueAsBytes(request), "application/json");
        ObjectNode out = JSON.createObjectNode();
        out.put("intentId", intentId.hex());
        out.put("submittedAtMs", System.currentTimeMillis());
        out.set("response", JSON.readTree(response));
        return out;
    }

    static void withdraw(String[] args) throws Exception {
        String user = args[0];
        int v = Integer.parseInt(args[1]);
        String recipientWallet = args.length > 4 ? args[4] : user;
        boolean base = args.length > 5 && args[5].equals("base");
        byte[] recipient = base ? new Address(baseAddress(recipientWallet)).getBytes() : rawAddress(recipientWallet);
        byte[] body = new PaymentsBodies.RequestWithdrawal(vaultId(v), recipient, new BigInteger(args[2]),
                new BigInteger(args[3])).encode();
        System.out.println(submitIntent(user, AssetPaymentsContract.REQUEST_WITHDRAWAL, body).toString());
    }

    /** {@code withdraw-many <vault> <amount> <vaultLovelace> <count> <user>...}: round-robin over the users. */
    static void withdrawMany(String[] args) throws Exception {
        int v = Integer.parseInt(args[0]);
        int count = Integer.parseInt(args[3]);
        String[] users = Arrays.copyOfRange(args, 4, args.length);
        long start = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            String user = users[i % users.length];
            byte[] body = new PaymentsBodies.RequestWithdrawal(vaultId(v), rawAddress(user), new BigInteger(args[1]),
                    new BigInteger(args[2])).encode();
            System.out.println(submitIntent(user, AssetPaymentsContract.REQUEST_WITHDRAWAL, body).toString());
        }
        System.out.println("{\"submitted\":" + count + ",\"ms\":" + (System.currentTimeMillis() - start) + "}");
    }

    /**
     * {@code measure <firstSequence> <count> [timeoutS]}: polls the committed claims {@code firstSequence ..} once a
     * second until {@code count} of them are resolved, and reports each claim's latency from its reservation (the
     * reserving block's timestamp) to the first poll that saw it PAID, with p50, p95, max and the throughput.
     */
    static void measure(long first, int count, int timeoutS) throws Exception {
        Map<String, Long> paidSeen = new TreeMap<>();
        Map<String, ObjectNode> last = new TreeMap<>();
        long deadline = System.currentTimeMillis() + timeoutS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            long total = SettlementQueries.decodeCount(query(SettlementQueries.CLAIM_COUNT, new byte[0]));
            for (long i = first; i < Math.min(total, first + count); i++) {
                byte[] id = query(SettlementQueries.CLAIM_BY_SEQUENCE, SettlementQueries.encodeSequence(i));
                String key = Hash32.of(id).hex();
                if (paidSeen.containsKey(key)) continue;
                ObjectNode claim = claimJson(Hash32.of(id));
                claim.put("sequence", i);
                last.put(key, claim);
                if (!claim.path("status").asText().equals("RESERVED")) paidSeen.put(key, System.currentTimeMillis());
            }
            if (paidSeen.size() >= count) break;
            Thread.sleep(1000);
        }
        List<Long> latencies = new ArrayList<>();
        long firstReserved = Long.MAX_VALUE;
        long lastPaid = 0;
        Map<String, Integer> statuses = new TreeMap<>();
        Map<String, Integer> txs = new TreeMap<>();
        for (Map.Entry<String, Long> entry : paidSeen.entrySet()) {
            ObjectNode claim = last.get(entry.getKey());
            long reserved = claim.path("reservedAtMs").asLong();
            latencies.add(entry.getValue() - reserved);
            firstReserved = Math.min(firstReserved, reserved);
            lastPaid = Math.max(lastPaid, entry.getValue());
            statuses.merge(claim.path("status").asText(), 1, Integer::sum);
            txs.merge(claim.path("resolvedTx").asText("-"), 1, Integer::sum);
        }
        latencies.sort(null);
        ObjectNode out = JSON.createObjectNode();
        out.put("requested", count);
        out.put("resolved", latencies.size());
        out.set("statuses", JSON.valueToTree(statuses));
        out.put("payoutTransactions", txs.size());
        out.set("claimsPerTransaction", JSON.valueToTree(txs.values()));
        if (!latencies.isEmpty()) {
            out.put("p50Ms", latencies.get((int) Math.ceil(0.50 * latencies.size()) - 1));
            out.put("p95Ms", latencies.get((int) Math.ceil(0.95 * latencies.size()) - 1));
            out.put("maxMs", latencies.get(latencies.size() - 1));
            out.put("minMs", latencies.get(0));
            long span = lastPaid - firstReserved;
            out.put("spanMs", span);
            out.put("claimsPerMinute", span <= 0 ? 0 : 60_000.0 * latencies.size() / span);
        }
        System.out.println(out);
    }

    // ------------------------------------------------------------------ a third-party relayer (D9)

    /**
     * A permissionless relayer with no node and no member key: it builds proof {@code Exit}s of the vault's
     * {@code EXIT_ELIGIBLE} claims from public data only (L1 outputs, committed claims and state proofs over the node
     * REST API, the live anchor's datum) with the gateway's public builder, and pays fees from its own wallet
     * ({@code thirdparty}). Without {@code submit} it only reports what it would relay.
     */
    static void thirdPartyExit(int v, boolean submit) throws Exception {
        org.yanoproject.x.assets.cardano.support.settlement.VaultPins pins = vaultPins(v);
        byte[] genesis = HEX.parseHex(chainGenesis());
        org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput anchorOut = null;
        org.yanoproject.x.assets.contracts.AnchorObservationV1.Anchored anchored = null;
        for (var out : outputs(scriptAddress(need("anchor.script-hash")))) {
            if (!out.quantityOf(pins.anchorToken()).equals(BigInteger.ONE) || out.inlineDatum().isEmpty()) continue;
            var datum = org.yanoproject.x.assets.contracts.GatewayDatums.acceptedAnchorV2(out.inlineDatum().get(),
                    genesis, ascii(APPLICATION), ascii(COMMITMENT_PROFILE), HEX.parseHex(FPRINT));
            if (datum.isPresent()) {
                anchorOut = out;
                anchored = datum.get();
            }
        }
        if (anchored == null) throw new IllegalStateException("no live anchor");
        long height = anchored.height();
        byte[] root = anchored.stateRoot().bytes();
        var proofs = org.yanoproject.x.assets.cardano.support.settlement.NodeStateProofs.at(height, root,
                (h, key) -> restProof(h, key));
        var statusProof = proofs.inclusion(ascii(STATUS_KEY)).orElseThrow(() -> new IllegalStateException("no status"));
        var status = org.yanoproject.x.assets.contracts.DomainStatusV1.decode(statusProof.value());
        var exitMode = outputs(need("exit.address")).stream()
                .filter(out -> out.quantityOf(pins.exitModeToken()).equals(BigInteger.ONE)).findFirst().orElseThrow();
        List<org.yanoproject.x.assets.cardano.support.settlement.ExitRelayer.Candidate> candidates = new ArrayList<>();
        Map<Integer, List<Hash32>> paid = new TreeMap<>();
        long total = SettlementQueries.decodeCount(query(SettlementQueries.CLAIM_COUNT, new byte[0]));
        for (long i = 0; i < total; i++) {
            Hash32 id = Hash32.of(query(SettlementQueries.CLAIM_BY_SEQUENCE, SettlementQueries.encodeSequence(i)));
            var view = SettlementQueries.decodeClaim(id, query(SettlementQueries.CLAIM, id.bytes())).orElseThrow();
            if (!view.leaf().vaultId().equals(pins.vaultId())) continue;
            var claimStatus = view.status().status();
            if (claimStatus == org.yanoproject.x.assets.contracts.SettlementRecords.ClaimStatus.RESERVED) {
                candidates.add(new org.yanoproject.x.assets.cardano.support.settlement.ExitRelayer.Candidate(
                        view.leaf(), claimStatus));
            } else {
                paid.computeIfAbsent(id.bytes()[31] & 0x0f, ignored -> new ArrayList<>()).add(id);
            }
        }
        Map<Integer, org.yanoproject.x.assets.cardano.support.settlement.ShardMirror> mirrors = new TreeMap<>();
        Map<Integer, org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput> shardInputs = new TreeMap<>();
        for (var out : outputs(need("vault." + v + ".shard-address"))) {
            for (int shard = 0; shard < 16; shard++) {
                if (!out.quantityOf(pins.shardToken(shard)).equals(BigInteger.ONE)) continue;
                var mirror = org.yanoproject.x.assets.cardano.support.settlement.ShardMirror.of(shard,
                        paid.getOrDefault(shard, List.of()));
                var live = out.inlineDatum().flatMap(org.yanoproject.x.assets.contracts.GatewayDatums::shardDatum);
                if (live.isPresent() && mirror.matches(live.get().nullifierRoot())) {
                    mirrors.put(shard, mirror);
                    shardInputs.put(shard, out);
                }
            }
        }
        long now = System.currentTimeMillis();
        long t0 = Long.parseLong(need("genesis-time-ms"));
        long slot = (now - t0) / 200;
        long validFrom = slot - 60;
        var batches = org.yanoproject.x.assets.cardano.support.settlement.ExitRelayer.plan(candidates, pins, exitMode,
                height, status, t0 + validFrom * 200, shard -> mirrors.getOrDefault(shard,
                        org.yanoproject.x.assets.cardano.support.settlement.ShardMirror.of(shard, List.of())));
        System.out.println("{\"anchorHeight\":" + height + ",\"reserved\":" + candidates.size() + ",\"eligibleBatches\":"
                + batches.size() + ",\"exitDelayMs\":" + status.exitDelayMs() + "}");
        if (!submit || batches.isEmpty()) return;
        var backend = new BFBackendService(l1(), "yano-devnet");
        var pp = backend.getEpochService().getProtocolParameters().getValue();
        com.bloxbean.cardano.client.api.TransactionEvaluator evaluator = (cbor, utxos) ->
                backend.getTransactionService().evaluateTx(cbor);
        var refs = List.of(output(need("ref.vault." + v)), output(need("ref.shard." + v)));
        byte[] wallet = rawAddress("thirdparty");
        var funding = outputs(walletAddress("thirdparty")).stream().filter(out -> out.assets().isEmpty()
                && out.referenceScript().isEmpty()).sorted((a, b) -> b.lovelace().compareTo(a.lovelace())).limit(3)
                .toList();
        var custody = outputs(need("vault." + v + ".address")).stream()
                .filter(out -> org.yanoproject.x.assets.cardano.support.settlement.PayoutTxBuilder.accepted(out, pins))
                .toList();
        var batch = batches.getFirst();
        var context = new org.yanoproject.x.assets.cardano.support.settlement.PayoutTxBuilder.Context(pins, custody,
                shardInputs.get(batch.shardIndex()), anchorOut, exitMode, refs,
                new org.yanoproject.x.assets.cardano.support.settlement.PayoutTxBuilder.Funding(funding, wallet),
                validFrom, slot + 600, pp, 8);
        var built = org.yanoproject.x.assets.cardano.support.settlement.ExitRelayer.build(context, batch, proofs,
                mirrors.get(batch.shardIndex()), evaluator);
        Transaction tx = Transaction.deserialize(built.cbor());
        byte[] signature = com.bloxbean.cardano.client.crypto.config.CryptoConfiguration.INSTANCE.getSigningProvider()
                .sign(built.txId().bytes(), seed("thirdparty"));
        tx.getWitnessSet().setVkeyWitnesses(List.of(
                new com.bloxbean.cardano.client.transaction.spec.VkeyWitness(pub("thirdparty"), signature)));
        byte[] signed = tx.serialize();
        String response = post(l1() + "tx/submit", signed, "application/cbor");
        state.setProperty("tx.third-party-exit-" + v + "-" + now, built.txId().hex());
        System.out.println("{\"submitted\":\"" + built.txId().hex() + "\",\"response\":" + response + ",\"claims\":\""
                + built.claimIds() + "\",\"shard\":" + batch.shardIndex() + ",\"fee\":" + built.fee()
                + ",\"bytes\":" + signed.length + "}");
    }

    static org.yanoproject.x.assets.cardano.support.settlement.VaultPins vaultPins(int v) {
        String p = "vault." + v + ".";
        var anchor = new org.yanoproject.x.assets.cardano.support.settlement.VaultPins.Anchor(
                HEX.parseHex(need("anchor.policy-id")), HEX.parseHex(need("anchor.asset-name")),
                HEX.parseHex(need("anchor.script-hash")), HEX.parseHex(chainGenesis()), ascii(APPLICATION),
                ascii(COMMITMENT_PROFILE), HEX.parseHex(FPRINT));
        return new org.yanoproject.x.assets.cardano.support.settlement.VaultPins(vaultId(v), asset(v), NETWORK_ID,
                HEX.parseHex(need(p + "script-hash")), new Address(need(p + "address")).getBytes(),
                HEX.parseHex(need(p + "shard-policy-id")), HEX.parseHex(need(p + "shard-script-hash")),
                new Address(need(p + "shard-address")).getBytes(), anchor, HEX.parseHex(need("exit.policy-id")),
                HEX.parseHex(need("exit.script-hash")), ascii(CLAIM_PREFIX), ascii(DEPOSIT_PREFIX), ascii(STATUS_KEY),
                BigInteger.valueOf(FLOOR), 16, 8, MAX_PAUSE, ShardBackend.parse(need("shard-backend")));
    }

    static Optional<org.yanoproject.x.assets.cardano.support.settlement.NodeStateProofs.NativeProof> restProof(
            long height, byte[] key) {
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(app() + "state/proof/"
                    + HEX.formatHex(key) + "?height=" + height)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Optional.empty();
            JsonNode node = JSON.readTree(response.body());
            if (!"PRESENT".equals(node.path("presence").asText()) || node.path("committedHeight").asLong() != height) {
                return Optional.empty();
            }
            return Optional.of(new org.yanoproject.x.assets.cardano.support.settlement.NodeStateProofs.NativeProof(
                    height, HEX.parseHex(node.path("stateRoot").asText()), HEX.parseHex(node.path("key").asText()),
                    HEX.parseHex(node.path("valueHex").asText()), HEX.parseHex(node.path("proofWireHex").asText())));
        } catch (Exception failure) {
            return Optional.empty();
        }
    }

    static List<org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput> outputs(String address)
            throws Exception {
        List<org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput> out = new ArrayList<>();
        for (int page = 1; page <= 20; page++) {
            JsonNode list = JSON.readTree(get(l1() + "addresses/" + address + "/utxos?count=100&page=" + page));
            for (JsonNode node : list) out.add(resolved(node));
            if (list.size() < 100) break;
        }
        return out;
    }

    static org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput output(String outpoint) throws Exception {
        String[] parts = outpoint.split("#");
        return resolved(JSON.readTree(get(l1() + "utxos/" + parts[0] + "/" + parts[1])));
    }

    static org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput resolved(JsonNode node) {
        TreeMap<AssetId, BigInteger> assets = new TreeMap<>();
        BigInteger lovelace = BigInteger.ZERO;
        for (JsonNode amount : node.path("amount")) {
            String unit = amount.path("unit").asText();
            BigInteger quantity = new BigInteger(amount.path("quantity").asText());
            if (unit.equals("lovelace")) lovelace = quantity;
            else assets.merge(AssetId.ofHex(unit.substring(0, 56), unit.substring(56)), quantity, BigInteger::add);
        }
        Optional<byte[]> datum = node.hasNonNull("inline_datum")
                ? Optional.of(HEX.parseHex(node.get("inline_datum").asText())) : Optional.empty();
        Optional<org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput.ReferenceScript> script =
                Optional.empty();
        if (node.hasNonNull("reference_script_hash")) {
            String ref = node.path("script_ref").asText("");
            script = Optional.of(new org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput.ReferenceScript(
                    HEX.parseHex(node.get("reference_script_hash").asText()), ref.length() / 2, Optional.empty()));
        }
        return new org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput(
                new org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput.Outpoint(
                        Hash32.ofHex(node.path("tx_hash").asText()), node.path("output_index").asInt()),
                new Address(node.path("address").asText()).getBytes(), lovelace, assets, datum, script);
    }

    // ------------------------------------------------------------------ committed records

    static Optional<byte[]> entry(byte[] key) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(app() + "state/entry/"
                + HEX.formatHex(key))).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return Optional.empty();
        JsonNode node = JSON.readTree(response.body());
        for (String field : List.of("valueHex", "value")) {
            if (node.hasNonNull(field) && !node.get(field).asText().isEmpty()) {
                return Optional.of(HEX.parseHex(node.get(field).asText()));
            }
        }
        if ("ABSENT".equals(node.path("presence").asText())) return Optional.empty();
        throw new IllegalStateException("unexpected state entry response: " + response.body());
    }

    static byte[] query(String path, byte[] params) throws Exception {
        ObjectNode request = JSON.createObjectNode();
        request.put("paramsHex", HEX.formatHex(params));
        JsonNode node = JSON.readTree(post(app() + "chains/" + need("chain-id") + "/query/" + path,
                JSON.writeValueAsBytes(request),
                "application/json"));
        for (String field : List.of("payloadHex", "resultHex", "valueHex")) {
            if (node.has(field)) return HEX.parseHex(node.get(field).asText());
        }
        throw new IllegalStateException("unexpected query response: " + node);
    }

    static String receipt(Hash32 intentId) throws Exception {
        Optional<byte[]> bytes = entry(AssetPaymentsContract.receiptKey(intentId));
        if (bytes.isEmpty()) return "{\"intentId\":\"" + intentId.hex() + "\",\"receipt\":null}";
        PaymentsRecords.ReceiptV1 receipt = PaymentsRecords.ReceiptV1.decode(bytes.get());
        ObjectNode out = JSON.createObjectNode();
        out.put("intentId", intentId.hex());
        out.put("action", receipt.action());
        out.put("outcome", receipt.outcome().name());
        out.put("reason", receipt.reason());
        out.put("amount", receipt.amount().toString());
        out.put("height", receipt.height());
        return out.toString();
    }

    static ObjectNode claimJson(Hash32 claimId) throws Exception {
        Optional<SettlementQueries.ClaimView> view = SettlementQueries.decodeClaim(claimId,
                query(SettlementQueries.CLAIM, claimId.bytes()));
        ObjectNode out = JSON.createObjectNode();
        out.put("claimId", claimId.hex());
        if (view.isEmpty()) return out;
        ClaimLeafV1 leaf = view.get().leaf();
        out.put("vault", leaf.vaultId().equals(vaultId(0)) ? 0 : 1);
        out.put("shard", claimId.bytes()[31] & 0x0f);
        out.put("kind", leaf.kind().name());
        out.put("value", leaf.value().toString());
        out.put("vaultLovelace", leaf.vaultLovelace().toString());
        out.put("recipient", HEX.formatHex(leaf.recipient()));
        out.put("reservedHeight", leaf.reservedHeight());
        out.put("reservedAtMs", leaf.reservedAtMs());
        out.put("status", view.get().status().status().name());
        view.get().status().resolvedTxId().ifPresent(tx -> out.put("resolvedTx", tx.hex()));
        out.put("resolvedHeight", view.get().status().resolvedHeight());
        return out;
    }

    static String claim(Hash32 claimId) throws Exception {
        return claimJson(claimId).toString();
    }

    static List<ObjectNode> claims() throws Exception {
        long count = SettlementQueries.decodeCount(query(SettlementQueries.CLAIM_COUNT, new byte[0]));
        List<ObjectNode> out = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            byte[] id = query(SettlementQueries.CLAIM_BY_SEQUENCE, SettlementQueries.encodeSequence(i));
            ObjectNode claim = claimJson(Hash32.of(id));
            claim.put("sequence", i);
            out.add(claim);
        }
        return out;
    }

    static String balance(String user, int v) throws Exception {
        AssetAccounts keys = new AssetAccounts(AssetPaymentsContract.PREFIX, new AssetAccounts.Store() {
            @Override public Optional<byte[]> get(byte[] key) { return Optional.empty(); }
            @Override public void put(byte[] key, byte[] value) { }
            @Override public void delete(byte[] key) { }
        });
        Optional<byte[]> bytes = entry(keys.exitLeafKey(vaultId(v), accountId(user)));
        ObjectNode out = JSON.createObjectNode();
        out.put("user", user);
        out.put("vault", v);
        if (bytes.isPresent()) {
            ExitLeafV1 leaf = ExitLeafV1.decode(bytes.get());
            out.put("amount", leaf.amount().toString());
            out.put("vaultLovelace", leaf.vaultLovelace().toString());
        } else {
            out.put("amount", "0");
            out.put("vaultLovelace", "0");
        }
        return out.toString();
    }

    static String depositRecord(int v, String tx, int index) throws Exception {
        Hash32 depositId = GatewayIds.depositId(vaultId(v), Hash32.ofHex(tx), index);
        byte[] response = query(SettlementQueries.DEPOSIT, depositId.bytes());
        Optional<DepositRecordV1> record = SettlementQueries.decodeDeposit(depositId, response);
        ObjectNode out = JSON.createObjectNode();
        out.put("depositId", depositId.hex());
        record.ifPresent(r -> {
            out.put("status", r.status().name());
            out.put("reason", r.reason().name());
            out.put("shape", r.shape().name());
            out.put("slot", r.slot());
            out.put("height", r.height());
            out.put("creditedAmount", r.creditedAmount().toString());
            out.put("creditedLovelace", r.creditedLovelace().toString());
        });
        return out.toString();
    }

    // ------------------------------------------------------------------ helpers

    static String get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
                .body();
    }

    static String post(String url, byte[] body, String contentType) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", contentType).header("X-API-Key", API_KEY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("POST " + url + " -> " + response.statusCode() + " " + response.body());
        }
        return response.body();
    }

    static byte[] bstrHead(int length) {
        if (length < 24) return new byte[] {(byte) (0x40 | length)};
        if (length < 256) return new byte[] {0x58, (byte) length};
        return new byte[] {0x59, (byte) (length >>> 8), (byte) length};
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    private GatewayDevnet() {
    }
}
