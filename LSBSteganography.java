import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Random;

import javax.imageio.ImageIO;

/**
 * Stéganographie spatiale par Least Significant Bit (LSB).
 *
 * Chaque bit du message (ici une part du secret) est intégré dans le bit de
 * poids faible du canal vert de l'image de couverture. Le canal vert est
 * préféré au bleu : la perception humaine y est moins sensible, ce qui
 * améliore l'imperceptibilité (PSNR plus élevé).
 *
 * Améliorations par rapport au prototype initial du mémoire :
 *   - intégration dans le canal vert (au lieu du bleu) ;
 *   - en-tête de longueur (32 bits) pour une extraction automatique ;
 *   - ordre d'insertion aléatoire contrôlé par une clé stégo (seed).
 */
public class LSBSteganography {

    /** Taille de l'en-tête indiquant la longueur (en bits) du message. */
    public static final int HEADER_BITS = 32;

    private static final int GREEN_MASK = 0xFE;
    private static final int GREEN_SHIFT = 8;

    private LSBSteganography() {
    }

    /**
     * Intègre les données dans le canal vert de l'image de couverture.
     *
     * @param cover          image de couverture (sera recopiée, non modifiée).
     * @param data           bits de la part à cacher.
     * @param randomOrder    si true, l'ordre des pixels est rendu aléatoire.
     * @param seed           clé stégo (doit être connue à l'extraction).
     * @return l'image stégo.
     */
    public static BufferedImage embed(BufferedImage cover, boolean[] data,
                                      boolean randomOrder, long seed) {
        int w = cover.getWidth();
        int h = cover.getHeight();
        int capacity = w * h - HEADER_BITS;
        if (data.length > capacity) {
            throw new IllegalArgumentException(
                    "Message trop long (" + data.length + " bits) pour une image de "
                            + w + "x" + h + " : capacité " + capacity + " bits.");
        }

        BufferedImage stego = copy(cover);
        boolean[] payload = merge(toBits(data.length, HEADER_BITS), data);
        int[] order = indexOrder(w * h, randomOrder, seed);

        for (int i = 0; i < payload.length; i++) {
            int pos = order[i];
            int x = pos % w;
            int y = pos / w;
            setGreenLSB(stego, x, y, payload[i]);
        }
        return stego;
    }

    /**
     * Extrait les données cachées dans une image stégo.
     *
     * @return les bits du message, précédés de rien (seules les données utiles
     *         sont renvoyées).
     */
    public static boolean[] extract(BufferedImage stego,
                                    boolean randomOrder, long seed) {
        int w = stego.getWidth();
        int h = stego.getHeight();
        int[] order = indexOrder(w * h, randomOrder, seed);

        boolean[] header = new boolean[HEADER_BITS];
        for (int i = 0; i < HEADER_BITS; i++) {
            int pos = order[i];
            header[i] = getGreenLSB(stego, pos % w, pos / w);
        }
        int length = toInt(header);

        if (length < 0 || length > w * h - HEADER_BITS) {
            throw new IllegalStateException(
                    "En-tête invalide : la clé stégo ou l'image est incorrecte.");
        }
        boolean[] data = new boolean[length];
        for (int i = 0; i < length; i++) {
            int pos = order[i + HEADER_BITS];
            data[i] = getGreenLSB(stego, pos % w, pos / w);
        }
        return data;
    }

    /* ------------------------------------------------------------------ */
    /* Mécanismes internes                                                 */
    /* ------------------------------------------------------------------ */

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage dst = new BufferedImage(
                src.getWidth(), src.getHeight(), src.getType() == 0
                        ? BufferedImage.TYPE_INT_RGB : src.getType());
        int w = src.getWidth();
        int h = src.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst.setRGB(x, y, src.getRGB(x, y));
            }
        }
        return dst;
    }

    private static void setGreenLSB(BufferedImage img, int x, int y, boolean bit) {
        int rgb = img.getRGB(x, y);
        int a = (rgb >> 24) & 0xFF;
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> GREEN_SHIFT) & 0xFF;
        int b = rgb & 0xFF;
        g = (g & GREEN_MASK) | (bit ? 1 : 0);
        img.setRGB(x, y, (a << 24) | (r << 16) | (g << GREEN_SHIFT) | b);
    }

    private static boolean getGreenLSB(BufferedImage img, int x, int y) {
        int rgb = img.getRGB(x, y);
        return ((rgb >> GREEN_SHIFT) & 1) == 1;
    }

    private static int[] indexOrder(int size, boolean randomOrder, long seed) {
        int[] order = new int[size];
        for (int i = 0; i < size; i++) {
            order[i] = i;
        }
        if (randomOrder) {
            Random rng = new Random(seed);
            for (int i = size - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int tmp = order[i];
                order[i] = order[j];
                order[j] = tmp;
            }
        }
        return order;
    }

    private static boolean[] toBits(int value, int nBits) {
        boolean[] bits = new boolean[nBits];
        for (int i = nBits - 1; i >= 0; i--) {
            bits[i] = (value & 1) == 1;
            value >>>= 1;
        }
        return bits;
    }

    private static int toInt(boolean[] bits) {
        int value = 0;
        for (boolean bit : bits) {
            value = (value << 1) | (bit ? 1 : 0);
        }
        return value;
    }

    private static boolean[] merge(boolean[] head, boolean[] tail) {
        boolean[] all = new boolean[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    /**
     * Helpeur : charge une image depuis le disque.
     */
    public static BufferedImage read(File file) throws IOException {
        return ImageIO.read(file);
    }

    /**
     * Helpeur : écrit une image sur le disque.
     */
    public static void write(BufferedImage img, File file) throws IOException {
        ImageIO.write(img, "png", file);
    }
}