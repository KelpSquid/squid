package squid;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The lists Squid downloads that lead to installing something are signed by Samuel: the Store's store.json (mods,
 * resource packs and capes) and official-capes.json (links to official cape pictures). Next to each list is a .sig
 * file, like store.json.sig: a signature made with Kelp & Squid's private key, which is only on Samuel's laptop.
 * Squid checks it against the exact bytes of the list before reading anything in it, with the public key below.
 * Someone who got into the GitHub account could change a list, but couldn't make a signature that matches, so their
 * list is never used. A missing .sig counts the same as a wrong one.
 *
 * Samuel signs with "build.bat sign" (the build signs its own store.json by itself). Kelp checks the same way, with
 * the same key, in kelp.Signatures.
 */
public final class Signatures {
    private Signatures() {
    }

    /** Kelp & Squid's public key: Ed25519, base64 of its X.509 form. Its private half never leaves Samuel's laptop. */
    static final String PUBLIC_KEY = "MCowBQYDK2VwAyEAinGKl0XlKHoYRbob8CJMuPsYWLpm349fNiU15Xsu1/k=";

    /**
     * The key lists have to be signed with. Only the tests change it, to a key of their own; nothing a player can set
     * (a setting, a file, a -D option) does.
     */
    static PublicKey trusted = publicKey(PUBLIC_KEY);

    /** A list that isn't signed by Kelp & Squid: its .sig is missing, or doesn't match it. */
    public static final class Unsigned extends IOException {
        Unsigned(String message) {
            super(message);
        }
    }

    /**
     * Throws {@link Unsigned} with this message unless sig (a .sig file's contents, or null if there wasn't one) is
     * Kelp & Squid's signature of exactly these bytes. Call it before reading anything in the list.
     */
    public static void check(byte[] list, byte[] sig, String notSigned) throws Unsigned {
        if (sig == null || !valid(list, sig)) throw new Unsigned(notSigned);
    }

    static boolean valid(byte[] list, byte[] sig) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(trusted);
            verifier.update(list);
            return verifier.verify(Base64.getDecoder().decode(new String(sig, StandardCharsets.US_ASCII).strip()));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return false; // not base64, or not a signature at all
        }
    }

    static PublicKey publicKey(String base64) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
