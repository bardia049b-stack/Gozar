import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Offline signing tool for Gozar signed-update blobs ("GOZU1").
 *
 * Blob layout:
 *   magic   5 bytes  "GOZU1"
 *   version 8 bytes  big-endian, monotonic per channel
 *   length  4 bytes  big-endian payload length
 *   payload length bytes
 *   sig     64 bytes Ed25519 over everything above
 *
 * Usage (JDK 17+, no dependencies):
 *   java SignGozar.java genkey out.pkcs8.b64          -> writes private key, prints public hex
 *   java SignGozar.java pubkey out.pkcs8.b64          -> prints public hex
 *   java SignGozar.java sign key.pkcs8.b64 VERSION IN OUT
 *   java SignGozar.java verify PUBHEX BLOB            -> exit 0 when valid
 */
public final class SignGozar {

    private static final byte[] MAGIC = {'G', 'O', 'Z', 'U', '1'};

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("missing command");
        switch (args[0]) {
            case "genkey" -> {
                KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                Files.writeString(Path.of(args[1]), Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
                System.out.println(hex(pair.getPublic().getEncoded()));
                System.out.println("NOTE: the printed hex is the X.509 SubjectPublicKeyInfo; the raw 32-byte key is the last 32 bytes.");
                System.out.println("raw public hex: " + hex(rawPublic(pair.getPublic())));
            }
            case "pubkey" -> {
                PrivateKey key = readPrivate(args[1]);
                // Derive the public key by signing and recovering is overkill; regenerate from seed is not
                // exposed by the JCA, so the genkey output is the source of truth for the public hex.
                throw new IllegalStateException("record the public hex printed by genkey; got " + key.getAlgorithm());
            }
            case "sign" -> {
                PrivateKey key = readPrivate(args[1]);
                long version = Long.parseLong(args[2]);
                if (version <= 0) throw new IllegalArgumentException("version must be positive");
                byte[] payload = Files.readAllBytes(Path.of(args[3]));
                if (payload.length == 0) throw new IllegalArgumentException("empty payload");
                byte[] blob = blob(version, payload);
                Signature signer = Signature.getInstance("Ed25519");
                signer.initSign(key);
                signer.update(blob);
                byte[] sig = signer.sign();
                byte[] out = new byte[blob.length + sig.length];
                System.arraycopy(blob, 0, out, 0, blob.length);
                System.arraycopy(sig, 0, out, blob.length, sig.length);
                Files.write(Path.of(args[4]), out);
                System.out.println("signed " + out.length + " bytes, version " + version);
            }
            case "verify" -> {
                PublicKey pub = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(x509FromRaw(unhex(args[1]))));
                byte[] blob = Files.readAllBytes(Path.of(args[2]));
                if (blob.length < MAGIC.length + 8 + 4 + 1 + 64) throw new IllegalStateException("blob too short");
                for (int i = 0; i < MAGIC.length; i++) if (blob[i] != MAGIC[i]) throw new IllegalStateException("bad magic");
                int length = ((blob[13] & 0xFF) << 24) | ((blob[14] & 0xFF) << 16) | ((blob[15] & 0xFF) << 8) | (blob[16] & 0xFF);
                if (MAGIC.length + 8 + 4 + length + 64 != blob.length) throw new IllegalStateException("bad length");
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(pub);
                verifier.update(blob, 0, blob.length - 64);
                if (!verifier.verify(blob, blob.length - 64, 64)) throw new IllegalStateException("bad signature");
                long version = 0;
                for (int i = 5; i < 13; i++) version = (version << 8) | (blob[i] & 0xFFL);
                System.out.println("OK version " + version + " payload " + length + " bytes");
            }
            default -> throw new IllegalArgumentException("unknown command: " + args[0]);
        }
    }

    private static byte[] blob(long version, byte[] payload) {
        byte[] out = new byte[MAGIC.length + 8 + 4 + payload.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        for (int i = 0; i < 8; i++) out[5 + i] = (byte) (version >>> (56 - 8 * i));
        out[13] = (byte) (payload.length >>> 24);
        out[14] = (byte) (payload.length >>> 16);
        out[15] = (byte) (payload.length >>> 8);
        out[16] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 17, payload.length);
        return out;
    }

    private static PrivateKey readPrivate(String path) throws Exception {
        byte[] der = Base64.getDecoder().decode(Files.readString(Path.of(path)).trim());
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** Ed25519 public keys from the JCA are X.509 wrappers; the raw point is the trailing 32 bytes. */
    private static byte[] rawPublic(PublicKey key) {
        byte[] encoded = key.getEncoded();
        byte[] raw = new byte[32];
        System.arraycopy(encoded, encoded.length - 32, raw, 0, 32);
        return raw;
    }

    /** Wraps a raw 32-byte Ed25519 point into the X.509 SubjectPublicKeyInfo the JCA expects. */
    private static byte[] x509FromRaw(byte[] raw) {
        byte[] prefix = {0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00};
        byte[] out = new byte[prefix.length + raw.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(raw, 0, out, prefix.length, raw.length);
        return out;
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) out.append(String.format("%02x", b));
        return out.toString();
    }

    private static byte[] unhex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
