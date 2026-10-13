import java.awt.image.BufferedImage;

/**
 * Stéganographie dans le domaine fréquentiel par Discrete Cosine Transform
 * (DCT-II, blocs de 8x8 sur le canal vert).
 *
 * Le bit est inséré dans un seul coefficient de chaque bloc (position
 * MEDIAN_ROW, MEDIAN_COL) : le coefficient est fixé à +STEP (bit 1) ou
 * -STEP (bit 0). À l'extraction on lit simplement le signe du coefficient.
 *
 * Cette technique est intrinsèquement plus robuste au recadrage léger et aux
 * petites distorsions que le LSB, mais produit des valeurs de MSE plus
 * élevées (modifications plus visibles), ce qui est confirmé par les mesures
 * du mémoire.
 */
public class DCTSteganography {

    /** Taille d'un bloc. */
    public static final int BLOCK = 8;

    /** Longueur de l'en-tête (identique au LSB pour garder une API commune). */
    public static final int HEADER_BITS = 32;

    /** Position du coefficient hôte dans le bloc 8x8. */
    private static final int MEDIAN_ROW = 4;
    private static final int MEDIAN_COL = 1;

    /** Amplitude utilisée pour coder un bit. */
    private static final double STEP = 40.0;

    /** Base orthonormale de la DCT-II 8x8. */
    private static final double[][] BASIS = basis();

    private DCTSteganography() {
    }

    /**
     * Intègre les données dans le canal vert de l'image, bloc par bloc.
     */
    public static BufferedImage embed(BufferedImage cover, boolean[] data) {
        int w = cover.getWidth();
        int h = cover.getHeight();
        int capacity = (w / BLOCK) * (h / BLOCK) - HEADER_BITS;
        if (data.length > capacity) {
            throw new IllegalArgumentException(
                    "Message trop long (" + data.length + " bits) : capacité DCT "
                            + capacity + " bits.");
        }

        double[][] green = extractGreen(cover);
        boolean[] payload = new boolean[HEADER_BITS + data.length];
        int[] header = bitsOf(data.length, HEADER_BITS);
        for (int i = 0; i < HEADER_BITS; i++) {
            payload[i] = header[i] == 1;
        }
        System.arraycopy(data, 0, payload, HEADER_BITS, data.length);

        double[] cc = new double[BLOCK];
        double[] rr = new double[BLOCK];
        int bitIndex = 0;
        for (int by = 0; by + BLOCK <= h; by += BLOCK) {
            for (int bx = 0; bx + BLOCK <= w; bx += BLOCK) {
                double[][] block = sub(green, bx, by);
                double[][] dct = forward(block);
                boolean bit = bitIndex < payload.length && payload[bitIndex++];
                dct[MEDIAN_ROW][MEDIAN_COL] = bit ? STEP : -STEP;
                double[][] inv = inverse(dct);
                writeBlock(green, inv, bx, by);
            }
        }
        return reconstructImage(cover, green);
    }

    /**
     * Extrait les données cachées par DCT.
     */
    public static boolean[] extract(BufferedImage stego) {
        int w = stego.getWidth();
        int h = stego.getHeight();
        double[][] green = extractGreen(stego);

        boolean[] headerBits = new boolean[HEADER_BITS];
        boolean[] allBits = new boolean[(w / BLOCK) * (h / BLOCK)];
        int bitIndex = 0;
        for (int by = 0; by + BLOCK <= h; by += BLOCK) {
            for (int bx = 0; bx + BLOCK <= w; bx += BLOCK) {
                double[][] block = sub(green, bx, by);
                if (bitIndex < allBits.length) {
                    allBits[bitIndex] = forward(block)[MEDIAN_ROW][MEDIAN_COL] > 0;
                }
                bitIndex++;
            }
        }
        System.arraycopy(allBits, 0, headerBits, 0, HEADER_BITS);
        int length = toInt(headerBits);
        if (length < 0 || length + HEADER_BITS > allBits.length) {
            throw new IllegalStateException(
                    "En-tête DCT invalide : le message n'est pas présent.");
        }
        boolean[] data = new boolean[length];
        System.arraycopy(allBits, HEADER_BITS, data, 0, length);
        return data;
    }

    /* ------------------------------------------------------------------ */
    /* DCT-II 8x8 orthonormale                                            */
    /* ------------------------------------------------------------------ */

    private static double[][] basis() {
        double[][] c = new double[BLOCK][BLOCK];
        for (int u = 0; u < BLOCK; u++) {
            for (int x = 0; x < BLOCK; x++) {
                double alpha = u == 0 ? Math.sqrt(0.5) : 1.0;
                c[u][x] = Math.sqrt(2.0 / BLOCK) * alpha
                        * Math.cos(Math.PI * (2 * x + 1) * u / (2.0 * BLOCK));
            }
        }
        return c;
    }

    private static double[][] forward(double[][] block) {
        double[][] g = new double[BLOCK][BLOCK];
        for (int u = 0; u < BLOCK; u++) {
            for (int v = 0; v < BLOCK; v++) {
                double s = 0;
                for (int x = 0; x < BLOCK; x++) {
                    for (int y = 0; y < BLOCK; y++) {
                        s += BASIS[u][x] * BASIS[v][y] * block[x][y];
                    }
                }
                g[u][v] = s;
            }
        }
        return g;
    }

    private static double[][] inverse(double[][] coeff) {
        double[][] block = new double[BLOCK][BLOCK];
        for (int x = 0; x < BLOCK; x++) {
            for (int y = 0; y < BLOCK; y++) {
                double s = 0;
                for (int u = 0; u < BLOCK; u++) {
                    for (int v = 0; v < BLOCK; v++) {
                        s += BASIS[u][x] * BASIS[v][y] * coeff[u][v];
                    }
                }
                block[x][y] = s;
            }
        }
        return block;
    }

    /* ------------------------------------------------------------------ */
    /* Manipulation du canal vert                                         */
    /* ------------------------------------------------------------------ */

    private static double[][] extractGreen(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        double[][] green = new double[w][h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                green[x][y] = (img.getRGB(x, y) >> 8) & 0xFF;
            }
        }
        return green;
    }

    private static BufferedImage reconstructImage(BufferedImage cover, double[][] green) {
        BufferedImage out = new BufferedImage(
                cover.getWidth(), cover.getHeight(), BufferedImage.TYPE_INT_RGB);
        int w = cover.getWidth();
        int h = cover.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = cover.getRGB(x, y);
                int r = ((rgb >> 16) & 0xFF);
                int b = rgb & 0xFF;
                int g = clamp((int) Math.round(green[x][y]));
                out.setRGB(x, y, (0xFF << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    private static double[][] sub(double[][] src, int bx, int by) {
        double[][] block = new double[BLOCK][BLOCK];
        for (int i = 0; i < BLOCK; i++) {
            for (int j = 0; j < BLOCK; j++) {
                block[i][j] = src[bx + j][by + i];
            }
        }
        return block;
    }

    private static void writeBlock(double[][] dst, double[][] block, int bx, int by) {
        for (int i = 0; i < BLOCK; i++) {
            for (int j = 0; j < BLOCK; j++) {
                dst[bx + j][by + i] = clamp((int) Math.round(block[i][j]));
            }
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    private static int[] bitsOf(int value, int nBits) {
        int[] bits = new int[nBits];
        for (int i = nBits - 1; i >= 0; i--) {
            bits[i] = value & 1;
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
}