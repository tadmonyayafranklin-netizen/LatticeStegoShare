import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Benchmark du prototype post-quantique : met en oeuvre et mesure les trois
 * perspectives du memoire, en utilisant le partage de secret 
 * basé sur LWE (post-quantique) :
 *   (i)   steganographie sur d'autres medias (audio WAV) ;
 *   (ii)  secrets non binaires (codes bancaires, textes, fichiers) ;
 *   (iii) etude du seuil k du schema LWE et resistance aux attaques.
 *
 * Usage :
 *   java Benchmark [repertoireImages]
 */
public class Benchmark {

    private static final String IMAGES_DIR = "images";

    public static void main(String[] args) throws Exception {
        String imagesDir = args.length > 0 ? args[0] : IMAGES_DIR;

        runLatticeStudy();
        runNonBinarySecret();
        runAudio();
        runImageBenchmark(imagesDir);
    }

    /* ------------------------------------------------------------------ */
    /* (iii) Etude du seuil du schema LWE                                  */
    /* ------------------------------------------------------------------ */
    private static void runLatticeStudy() {
        System.out.println("=== (iii) Schema post-quantique LWE : influence du seuil k ===");
        String secret = "100100";
        LatticeSecretSharing scheme = new LatticeSecretSharing(secret);
        int n = 10;

        System.out.println("Secret : " + secret + "  (N=" + secret.length() + " bits)");
        System.out.println("Parametres LWE : q=" + scheme.modulus()
                + ", dimension=" + scheme.dimension()
                + ", echantillons=" + scheme.samples()
                + ", part=" + LatticeSecretSharing.shareBitLength() + " bits");
        System.out.printf("%-4s %-8s %-9s %-10s %-9s%n",
                "n", "k", "recon k", "recon k-1", "gen(ms)");

        for (int k = 2; k <= n; k++) {
            long start = System.nanoTime();
            List<long[]> shares = scheme.generate(n, k);
            long genMs = (System.nanoTime() - start) / 1_000_000;

            String ok = scheme.reconstruct(
                    new ArrayList<>(shares.subList(0, k)), k);
            String blocked = scheme.reconstruct(
                    new ArrayList<>(shares.subList(0, k - 1)), k);

            boolean success = secret.equals(ok);
            boolean protectedOk = !secret.equals(blocked);
            System.out.printf("%-4d %-8d %-9s %-10s %-9d%n",
                    n, k,
                    success ? "OK" : "FAIL",
                    protectedOk ? "blocked" : "LEAK",
                    genMs);
        }
        System.out.println();
    }

    /* ------------------------------------------------------------------ */
    /* (ii) Secrets non binaires (code bancaire / texte)                  */
    /* ------------------------------------------------------------------ */
    private static void runNonBinarySecret() {
        System.out.println("=== (ii) Secret non binaire (code bancaire) — post-quantique ===");
        String bankCode = "15467-9812";
        String binary = SecretCodec.toBinaryString(bankCode);
        int n = 8;
        MultiBlockSecretSharing mss = new MultiBlockSecretSharing(binary, n);
        int shares = 10;
        int k = 6;

        System.out.println("Code bancaire : " + bankCode
                + "  ->  binaire " + binary.length() + " bits, "
                + mss.blockCount() + " blocs de " + n + " bits");

        long start = System.nanoTime();
        List<List<long[]>> participants = mss.generate(shares, k);
        long genMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println("Parts generees par participant : " + participants.size()
                + "  (generation: " + genMs + " ms)");

        List<List<long[]>> chosen = new ArrayList<>(participants.subList(0, k));
        String recBinary = mss.reconstruct(chosen, k);
        String recCode = SecretCodec.toString(recBinary);
        boolean ok = bankCode.equals(recCode);
        System.out.println("Reconstitution avec k=" + k + " parts : " + recCode
                + "  ->  " + (ok ? "SUCCES" : "ECHEC"));

        String recBinaryShort = mss.reconstruct(
                new ArrayList<>(participants.subList(0, k - 1)), k);
        boolean leak = recBinaryShort != null
                && bankCode.equals(SecretCodec.toString(recBinaryShort));
        System.out.println("Avec k-1=" + (k - 1) + " parts : "
                + (leak ? "LEAK !" : "code protege"));
        System.out.println();
    }

    /* ------------------------------------------------------------------ */
    /* (i) Steganographie audio (WAV)                                      */
    /* ------------------------------------------------------------------ */
    private static void runAudio() {
        System.out.println("=== (i) Steganographie audio (WAV 16-bit) — parts LWE ===");
        LatticeSecretSharing scheme = new LatticeSecretSharing("100100");
        int n = 10;
        int k = 8;
        List<long[]> shares = scheme.generate(n, k);

        byte[] cover = AudioSteganography.synthetic(8000, 1.0, 440.0);
        List<long[]> embeddedShares = new ArrayList<>();
        double mseSum = 0.0;
        boolean allIdentical = true;
        for (int i = 0; i < k; i++) {
            boolean[] shareBits = LatticeSecretSharing.toBits(shares.get(i));
            byte[] stego = AudioSteganography.embed(cover, shareBits);
            boolean[] extracted = AudioSteganography.extract(stego);
            allIdentical &= Arrays.equals(shareBits, extracted);
            embeddedShares.add(LatticeSecretSharing.fromBits(extracted));
            mseSum += AudioSteganography.mse(cover, stego);
        }

        String recSecret = scheme.reconstruct(embeddedShares, k);
        boolean ok = "100100".equals(recSecret);

        System.out.println("Echantillons : " + cover.length + " octets (1 s, 8 kHz, 8000 samples)");
        System.out.println(k + " parts de " + LatticeSecretSharing.shareBitLength()
                + " bits cachees dans " + k + " fichiers WAV");
        System.out.println("Bits extraits identiques (toutes parts) : " + allIdentical);
        System.out.println("Secret reconstruit apres audio : " + recSecret
                + "  ->  " + (ok ? "SUCCES" : "ECHEC"));
        System.out.printf("MSE audio moyenne : %.6f%n", mseSum / k);
        System.out.println();
    }

    /* ------------------------------------------------------------------ */
    /* Benchmark image LSB / DCT (mesures de qualite)                     */
    /* ------------------------------------------------------------------ */
    private static void runImageBenchmark(String dir) {
        System.out.println("=== Benchmark image LSB / DCT (parts LWE) ===");
        String[] names = {"baboon.png", "cat.jpg", "lenna.png", "pepper.png"};
        String[] labels = {"Baboon", "Cat", "Lenna", "Pepper"};
        LatticeSecretSharing scheme = new LatticeSecretSharing("100100");
        List<long[]> shares = scheme.generate(10, 8);

        System.out.printf("%-8s %-12s %-12s %-12s %-12s%n",
                "Image", "MSE(LSB)", "PSNR(LSB)", "MSE(DCT)", "PSNR(DCT)");
        for (int i = 0; i < names.length; i++) {
            File file = new File(dir, names[i]);
            if (!file.isFile()) {
                System.out.println(labels[i] + ": fichier absent (" + file + ")");
                continue;
            }
            BufferedImage cover = load(file);
            boolean[] bits = LatticeSecretSharing.toBits(shares.get(0));

            BufferedImage lsb = LSBSteganography.embed(
                    cover, bits, /* randomOrder */ true, 2023L);
            double mseLsb = ImageMetrics.mse(cover, lsb);
            double psnrLsb = ImageMetrics.psnr(cover, lsb);

            BufferedImage dct = DCTSteganography.embed(cover, bits);
            double mseDct = ImageMetrics.mse(cover, dct);
            double psnrDct = ImageMetrics.psnr(cover, dct);

            System.out.printf("%-8s %-12.5f %-12.2f %-12.5f %-12.2f%n",
                    labels[i], mseLsb, psnrLsb, mseDct, psnrDct);
        }
        System.out.println();
    }

    /* ------------------------------------------------------------------ */
    private static BufferedImage load(File f) {
        try {
            return javax.imageio.ImageIO.read(f);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
