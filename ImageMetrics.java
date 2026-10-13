import java.awt.image.BufferedImage;

/**
 * Métriques de qualité d'image utilisées pour évaluer l'acceptabilité des
 * images stégo : MSE (Mean Square Error) et PSNR (Peak Signal-to-Noise
 * Ratio), calculées sur les trois canaux RVB.
 *
 *   MSE  = (1 / (3*W*H)) * somme des (c_original - c_stego)^2
 *   PSNR = 10 * log10(255^2 / MSE)
 *
 * Un PSNR élevé (typiquement &gt; 30 dB) traduit une image stégo quasi
 * identique à l'image de couverture : les modifications sont invisibles à
 * l'œil nu.
 */
public class ImageMetrics {

    private ImageMetrics() {
    }

    /** Ecart quadratique moyen entre deux images de mêmes dimensions. */
    public static double mse(BufferedImage original, BufferedImage modified) {
        int w = Math.min(original.getWidth(), modified.getWidth());
        int h = Math.min(original.getHeight(), modified.getHeight());
        long sum = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int ca = original.getRGB(x, y);
                int cb = modified.getRGB(x, y);
                int da = Math.abs(((ca >> 16) & 0xFF) - ((cb >> 16) & 0xFF));
                int db = Math.abs(((ca >> 8) & 0xFF) - ((cb >> 8) & 0xFF));
                int dc = Math.abs((ca & 0xFF) - (cb & 0xFF));
                sum += da * da + db * db + dc * dc;
            }
        }
        return (double) sum / (3.0 * w * h);
    }

    /** Rapport signal à bruit de crête, en décibels. */
    public static double psnr(BufferedImage original, BufferedImage modified) {
        double mse = mse(original, modified);
        if (mse == 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return 10.0 * Math.log10(255.0 * 255.0 / mse);
    }
}