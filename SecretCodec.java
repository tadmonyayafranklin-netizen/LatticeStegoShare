import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Codec permettant d'adapter des secrets non binaires (fichiers, codes
 * bancaires, textes, etc.) au partage matriciel : toute donnee est reduite
 * a une chaine binaire, puis decoupee en blocs de N bits.
 *
 * Perspective implementee : adaptation du modele a d'autres types de
 * secrets que le binaire brut.
 */
public final class SecretCodec {

    private SecretCodec() {
    }

    /** Convertit un tableau d'octets en chaine binaire (8 bits par octet). */
    public static String toBinaryString(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 8);
        for (byte value : data) {
            int v = value & 0xFF;
            for (int i = 7; i >= 0; i--) {
                sb.append((v >> i) & 1);
            }
        }
        return sb.toString();
    }

    /** Convertit un texte (encodage UTF-8) en chaine binaire. */
    public static String toBinaryString(String text) {
        return toBinaryString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Convertit une chaine binaire en tableau d'octets (multiple de 8). */
    public static byte[] fromBinaryString(String binary) {
        while (binary.length() % 8 != 0) {
            binary = "0" + binary;
        }
        byte[] data = new byte[binary.length() / 8];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) Integer.parseInt(binary.substring(i * 8, i * 8 + 8), 2);
        }
        return data;
    }

    /** Reconstruit un texte UTF-8 a partir d'une chaine binaire. */
    public static String toString(String binary) {
        return new String(fromBinaryString(binary), StandardCharsets.UTF_8);
    }

    /** Lit le contenu binaire complet d'un flux. */
    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}