import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Prototype de démonstration du partage de secret POST-QUANTIQUE base sur LWE,
 * securise par steganographie par image (LSB et DCT).
 *
 * Pipeline :
 *   1. Generation des parts (Shamir sur Z_q d'une cle LWE) ;
 *   2. Reconstitution + dechiffrement immediats (sans steganographie) ;
 *   3. Integration des parts dans des images de couverture via LSB puis DCT ;
 *   4. Extraction et reconstitution du secret a partir des images stego ;
 *   5. Evaluation de l'impact par les metriques MSE / PSNR.
 *
 * Usage :
 *   java Main [imageDeCouverture]   (une image par defaut est generee si
 *                                    aucun argument n'est fourni)
 */
public class Main {

    /** Exemple du mémoire : secret [36]_16 = 100100 en binaire. */
    private static final String SECRET = "100100";

    /** Seuil k : nombre minimum de parts pour reconstituer le secret. */
    private static final int THRESHOLD = 8;

    /** Nombre total de participants. */
    private static final int SHARES = 10;

    /** Clé stégo pour l'ordre aléatoire d'insertion (LSB). */
    private static final long STEGO_KEY = 2023L;

    public static void main(String[] args) throws Exception {
        LatticeSecretSharing scheme = new LatticeSecretSharing(SECRET);

        System.out.println("=== Partage de secret post-quantique (LWE / Regev) ===");
        System.out.println("Secret (binaire) : " + scheme.secret()
                + "  (hex: " + toHex(SECRET) + ")");
        System.out.println("Taille du secret : " + scheme.size() + " bits");
        System.out.println("Parametres LWE   : q=" + scheme.modulus()
                + ", N=" + scheme.dimension() + ", M=" + scheme.samples());
        System.out.println("Part serialisee  : " + LatticeSecretSharing.shareBitLength()
                + " bits");
        System.out.println("Parts generees   : " + SHARES + "  (seuil k=" + THRESHOLD + ")");
        System.out.println();

        List<long[]> shares = scheme.generate(SHARES, THRESHOLD);

        // --- Reconstitution directe --------------------------------------
        List<long[]> direct = new ArrayList<>(shares.subList(0, THRESHOLD));
        String recovered = scheme.reconstruct(direct, THRESHOLD);
        System.out.println("Reconstitution directe de " + THRESHOLD + " parts : "
                + recovered + "  ->  " + (recovered.equals(SECRET) ? "SUCCES" : "ECHEC"));

        // Avec un nombre de parts inferieur au seuil, le secret ne doit pas
        // être reconstruit.
        String partial = scheme.reconstruct(
                new ArrayList<>(shares.subList(0, THRESHOLD - 1)), THRESHOLD);
        boolean leak = partial != null && partial.equals(SECRET);
        System.out.println("Avec " + (THRESHOLD - 1) + " parts seulement : "
                + (partial == null ? "(reconstruction impossible)" : partial)
                + "  ->  " + (leak ? "LEAK !" : "secret protégé"));
        System.out.println();

        // --- Image de couverture -----------------------------------------
        BufferedImage cover = args.length > 0
                ? LSBSteganography.read(new File(args[0]))
                : syntheticCover(256, 256);
        System.out.println("Image de couverture : " + cover.getWidth()
                + "x" + cover.getHeight());

        // --- Intégration / extraction LSB ---------------------------------
        System.out.println();
        System.out.println("--- Stéganographie LSB (canal vert) ---");
        BufferedImage[] lsbStegos = new BufferedImage[THRESHOLD];
        for (int i = 0; i < THRESHOLD; i++) {
            boolean[] bits = LatticeSecretSharing.toBits(shares.get(i));
            lsbStegos[i] = LSBSteganography.embed(
                    cover, bits, /* randomOrder */ true, STEGO_KEY);
        }
        List<long[]> lsbExtracted = new ArrayList<>();
        for (BufferedImage stego : lsbStegos) {
            boolean[] bits = LSBSteganography.extract(
                    stego, /* randomOrder */ true, STEGO_KEY);
            lsbExtracted.add(LatticeSecretSharing.fromBits(bits));
        }
        String lsbSecret = scheme.reconstruct(lsbExtracted, THRESHOLD);
        System.out.println("Secret reconstruit après LSB : " + lsbSecret
                + "  ->  " + (lsbSecret.equals(SECRET) ? "SUCCES" : "ECHEC"));

        LSBSteganography.write(lsbStegos[0], new File("steg_lsb.png"));

        double mseLsb = ImageMetrics.mse(cover, lsbStegos[0]);
        double psnrLsb = ImageMetrics.psnr(cover, lsbStegos[0]);
        System.out.printf("MSE(LSB)  = %.8f%n", mseLsb);
        System.out.printf("PSNR(LSB) = %.2f dB%n", psnrLsb);

        // --- Intégration / extraction DCT ---------------------------------
        System.out.println();
        System.out.println("--- Stéganographie DCT (blocs 8x8) ---");
        BufferedImage[] dctStegos = new BufferedImage[THRESHOLD];
        for (int i = 0; i < THRESHOLD; i++) {
            boolean[] bits = LatticeSecretSharing.toBits(shares.get(i));
            dctStegos[i] = DCTSteganography.embed(cover, bits);
        }
        List<long[]> dctExtracted = new ArrayList<>();
        for (BufferedImage stego : dctStegos) {
            boolean[] bits = DCTSteganography.extract(stego);
            dctExtracted.add(LatticeSecretSharing.fromBits(bits));
        }
        String dctSecret = scheme.reconstruct(dctExtracted, THRESHOLD);
        System.out.println("Secret reconstruit après DCT : " + dctSecret
                + "  ->  " + (dctSecret.equals(SECRET) ? "SUCCES" : "ECHEC"));

        java.io.File fDct = new File("steg_dct.png");
        try {
            javax.imageio.ImageIO.write(dctStegos[0], "png", fDct);
        } catch (java.io.IOException ex) {
            System.err.println("Impossible d'écrire l'image stégo DCT.");
        }

        double mseDct = ImageMetrics.mse(cover, dctStegos[0]);
        double psnrDct = ImageMetrics.psnr(cover, dctStegos[0]);
        System.out.printf("MSE(DCT)  = %.8f%n", mseDct);
        System.out.printf("PSNR(DCT) = %.2f dB%n", psnrDct);

        System.out.println();
        System.out.println("Conclusion : la combinaison partage de secret post-quantique "
                + "(LWE) + stéganographie préserve l'intégrité du secret ; le LSB "
                + "offre le meilleur compromis (PSNR élevé => modifications "
                + "invisibles).");
    }

    /* ------------------------------------------------------------------ */

    private static String toHex(String binary) {
        int value = Integer.parseInt(binary, 2);
        return String.format("%0" + ((binary.length() + 3) / 4) + "X", value);
    }

    /** Génère une image synthétique dégradée si aucune n'est fournie. */
    private static BufferedImage syntheticCover(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = (x * 255) / w;
                int g = (y * 255) / h;
                int b = 128 + (int) (32 * Math.sin(x * 0.05) * Math.cos(y * 0.05));
                img.setRGB(x, y, (0xFF << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }
}
