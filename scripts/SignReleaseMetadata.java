import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;

public final class SignReleaseMetadata {
    private SignReleaseMetadata() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: SignReleaseMetadata <metadata> <signature-output>");
        }
        String keystorePath = requiredEnvironment("ANDROID_KEYSTORE_PATH");
        String alias = requiredEnvironment("ANDROID_KEY_ALIAS");
        char[] storePassword = requiredEnvironment("ANDROID_KEYSTORE_PASSWORD").toCharArray();
        char[] keyPassword = requiredEnvironment("ANDROID_KEY_PASSWORD").toCharArray();
        try {
            KeyStore keyStore = loadKeyStore(Path.of(keystorePath), storePassword);
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, keyPassword);
            if (privateKey == null) {
                throw new IllegalArgumentException("Release signing key alias was not found.");
            }
            String keyAlgorithm = privateKey.getAlgorithm().toUpperCase();
            String algorithm;
            if ("RSA".equals(keyAlgorithm)) {
                algorithm = "SHA256withRSA";
            } else if ("EC".equals(keyAlgorithm) || "ECDSA".equals(keyAlgorithm)) {
                algorithm = "SHA256withECDSA";
            } else {
                throw new IllegalArgumentException(
                    "Unsupported release signing key algorithm: " + privateKey.getAlgorithm()
                );
            }
            byte[] metadata = Files.readAllBytes(Path.of(args[0]));
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(privateKey);
            signer.update(metadata);
            String encoded = Base64.getEncoder().encodeToString(signer.sign()) + System.lineSeparator();
            Files.writeString(Path.of(args[1]), encoded, StandardCharsets.US_ASCII);
        } finally {
            Arrays.fill(storePassword, '\0');
            Arrays.fill(keyPassword, '\0');
        }
    }

    private static KeyStore loadKeyStore(Path path, char[] password) throws Exception {
        Exception lastFailure = null;
        for (String type : new String[] { "PKCS12", "JKS" }) {
            try {
                KeyStore keyStore = KeyStore.getInstance(type);
                try (var input = Files.newInputStream(path)) {
                    keyStore.load(input, password);
                }
                return keyStore;
            } catch (Exception failure) {
                lastFailure = failure;
            }
        }
        throw new IllegalArgumentException("Release keystore is not PKCS12 or JKS.", lastFailure);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required.");
        }
        return value;
    }
}
