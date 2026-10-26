package org.yanoproject.x.appchain.formats;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An operator's {@code <name>.member} card: "I hold this key and want to join ceremony {@code invite}".
 * The signature covers every other field. It is not consent to the final rules; that is given at join.
 */
public record MemberCard(
        String format,
        String invite,
        String name,
        String publicKey,
        String node,
        String http,
        String signature) {

    public static final String FORMAT = "yano-appchain-member-v1";
    private static final byte[] DOMAIN = (FORMAT + "\0").getBytes(StandardCharsets.US_ASCII);

    public MemberCard {
        if (!FORMAT.equals(format)) {
            throw new FormatException("Not a member card (format '" + format + "'); expected " + FORMAT);
        }
        Names.invite(invite);
        Names.name(name, "Operator name");
        if (!Ed25519.isKey(publicKey)) throw new FormatException("Member card has an invalid public key");
        Names.nodeAddress(node);
        Names.httpAddress(http);
    }

    /** Creates and signs a card with the operator's seed. */
    public static MemberCard create(String seed, String invite, String name, String node, String http) {
        MemberCard unsigned = new MemberCard(FORMAT, invite, name, Ed25519.publicKey(seed), node, http, null);
        return new MemberCard(FORMAT, invite, name, unsigned.publicKey(), node, http,
                Ed25519.sign(seed, unsigned.signedBytes()));
    }

    /** The exact bytes the signature covers. */
    public byte[] signedBytes() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("format", format);
        fields.put("invite", invite);
        fields.put("name", name);
        fields.put("publicKey", publicKey);
        fields.put("node", node);
        fields.put("http", http);
        byte[] body = Canonical.bytes(fields);
        byte[] message = new byte[DOMAIN.length + body.length];
        System.arraycopy(DOMAIN, 0, message, 0, DOMAIN.length);
        System.arraycopy(body, 0, message, DOMAIN.length, body.length);
        return message;
    }

    public boolean signatureValid() {
        return signature != null && Ed25519.verify(publicKey, signedBytes(), signature);
    }

    /** Fails with a user-facing reason unless the card is signed and made for {@code expectedInvite}. */
    public MemberCard verifiedFor(String expectedInvite) {
        if (!signatureValid()) {
            throw new FormatException(name + ".member has a bad signature; ask " + name
                    + " to run request again and send the new card");
        }
        if (!invite.equals(expectedInvite)) {
            throw new FormatException(name + ".member was made for a different invite (" + invite
                    + "); ask " + name + " to run request again with this draft");
        }
        return this;
    }

    public String toJson() {
        return Canonical.pretty(this);
    }

    public static MemberCard parse(String json) {
        try {
            JsonNode node = Canonical.JSON.readTree(json);
            return new MemberCard(
                    node.path("format").asText(null),
                    node.path("invite").asText(null),
                    node.path("name").asText(null),
                    node.path("publicKey").asText(null),
                    node.path("node").asText(null),
                    node.path("http").asText(null),
                    node.path("signature").asText(null));
        } catch (IOException error) {
            throw new FormatException("A member card must be JSON written by `yano.sh appchain request`", error);
        }
    }
}
