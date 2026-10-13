package com.matrixstegoshare;

import android.graphics.Bitmap;

import java.util.Random;

/**
 * Moteur de steganographie spatiale (LSB sur le canal vert) adapte a Android.
 *
 * Chaque bit du message est integre dans le bit de poids faible du canal vert
 * de l'image de couverture. Un en-tete de 32 bits stocke la longueur du
 * message, ce qui permet une extraction automatique. L'ordre d'insertion peut
 * etre rendu aleatoire au moyen d'une cle (seed) partagee avec l'extracteur.
 */
public final class StegoEngine {

    public static final int HEADER_BITS = 32;

    private static final int GREEN_MASK = 0xFE;
    private static final int KEEP_ARB_MASK = 0xFFFF00FF;

    private StegoEngine() {
    }

    /** Resultat d'une insertion : image stego + metriques de qualite. */
    public static final class EmbedResult {
        public final Bitmap stego;
        public final double mse;
        public final double psnr;
        public final int messageBits;

        EmbedResult(Bitmap stego, double mse, double psnr, int messageBits) {
            this.stego = stego;
            this.mse = mse;
            this.psnr = psnr;
            this.messageBits = messageBits;
        }
    }

    /** Capacite maximale (en bits) offerte par une image de couverture. */
    public static int capacityBits(Bitmap cover) {
        return cover.getWidth() * cover.getHeight() - HEADER_BITS;
    }

    /** Integre les donnees dans le canal vert de l'image de couverture. */
    public static EmbedResult embed(Bitmap cover, boolean[] data,
                                    boolean randomOrder, long seed) {
        int w = cover.getWidth();
        int h = cover.getHeight();
        int capacity = w * h - HEADER_BITS;
        if (data.length > capacity) {
            throw new IllegalArgumentException(
                    "Image too small: " + data.length + " bits to hide, capacity "
                            + capacity + " bits.");
        }

        int[] pixels = new int[w * h];
        cover.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] original = pixels.clone();

        boolean[] payload = merge(toBits(data.length, HEADER_BITS), data);
        int[] order = indexOrder(w * h, randomOrder, seed);

        for (int i = 0; i < payload.length; i++) {
            int pos = order[i];
            int p = pixels[pos];
            int g = (p >> 8) & 0xFF;
            g = (g & GREEN_MASK) | (payload[i] ? 1 : 0);
            pixels[pos] = (p & KEEP_ARB_MASK) | (g << 8);
        }

        double mse = mse(original, pixels);
        double psnr = mse == 0.0 ? Double.POSITIVE_INFINITY
                : 10.0 * Math.log10(255.0 * 255.0 / mse);

        Bitmap stego = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        stego.setPixels(pixels, 0, w, 0, 0, w, h);
        return new EmbedResult(stego, mse, psnr, data.length);
    }

    /** Extrait les donnees cachees dans une image stego. */
    public static boolean[] extract(Bitmap stego, boolean randomOrder, long seed) {
        int w = stego.getWidth();
        int h = stego.getHeight();
        int[] pixels = new int[w * h];
        stego.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] order = indexOrder(w * h, randomOrder, seed);

        boolean[] header = new boolean[HEADER_BITS];
        for (int i = 0; i < HEADER_BITS; i++) {
            header[i] = ((pixels[order[i]] >> 8) & 1) == 1;
        }
        int length = toInt(header);
        if (length < 0 || length > w * h - HEADER_BITS) {
            throw new IllegalStateException(
                    "Invalid header: wrong stego key or corrupted image.");
        }
        boolean[] data = new boolean[length];
        for (int i = 0; i < length; i++) {
            data[i] = ((pixels[order[i + HEADER_BITS]] >> 8) & 1) == 1;
        }
        return data;
    }

    /* ------------------------------------------------------------------ */

    private static double mse(int[] a, int[] b) {
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            int da = ((a[i] >> 16) & 0xFF) - ((b[i] >> 16) & 0xFF);
            int dg = ((a[i] >> 8) & 0xFF) - ((b[i] >> 8) & 0xFF);
            int db = (a[i] & 0xFF) - (b[i] & 0xFF);
            sum += (long) da * da + (long) dg * dg + (long) db * db;
        }
        return (double) sum / (3.0 * a.length);
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
}
