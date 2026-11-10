package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import org.junit.jupiter.api.Test;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** La paire générée par le moteur est exploitable telle quelle par le client Revolut X (parse PEM + signature vérifiable). */
class RevolutXKeyPairSupportTest {

    @Test
    void generatedPairRoundTripsThroughRevolutXSigning() throws Exception {
        KeyPair pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        PrivateKey parsed = RevolutXRequests.privateKey(pem);
        String sig = RevolutXRequests.sign(parsed, "1", "GET", "/api/1.0/x", "", "");
        Signature v = Signature.getInstance("Ed25519");
        v.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(pair.getPublic().getEncoded())));
        v.update("1GET/api/1.0/x".getBytes());
        assertTrue(v.verify(Base64.getDecoder().decode(sig)));
    }

    @Test
    void declaresBothScopes() {
        RevolutXKeyPairSupport s = new RevolutXKeyPairSupport(null);
        assertEquals(java.util.Set.of(CredentialScope.READ, CredentialScope.TRADE), s.scopes());
        assertEquals("Ed25519", s.algorithm());
    }

    @Test
    void rateLimitedReadsAreRetried() {
        int[] calls = {0};
        RevolutXRequests.Reply reply = RevolutXRequests.retryOnRateLimit(
                () -> new RevolutXRequests.Reply(++calls[0] < 3 ? 429 : 200, "{}"), 4, 1);
        assertEquals(200, reply.status());
        assertEquals(3, calls[0]);
        int[] always = {0};
        assertEquals(429, RevolutXRequests.retryOnRateLimit(() -> new RevolutXRequests.Reply(429 + 0 * ++always[0], ""), 3, 1).status());
        assertEquals(3, always[0]);
    }
}
