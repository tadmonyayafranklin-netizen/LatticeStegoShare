import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Steganographic audio par Least Significant Bit dans un fichier WAV PCM
 * (8 ou 16 bits). Le message est precede d'un en-tete de longueur (32 bits)
 * et est dissemine sur le bit de poids faible de chaque echantillon.
 *
 * Perspective implementee : extension de la securisation des parts a un autre
 * media (l'audio), en complement de l'image.
 */
public class AudioSteganography {

    /** Taille de l'en-tete indiquant la longueur (en bits) du message. */
    public static final int HEADER_BITS = 32;

    private AudioSteganography() {
    }

    /**
     * Intègre les donnees dans un fichier WAV PCM.
     *
     * @param wav  contenu du fichier WAV source.
     * @param data bits du message a cacher.
     * @return le fichier WAV modifie.
     */
    public static byte[] embed(byte[] wav, boolean[] data) {
        Wav source = Wav.parse(wav);
        int capacity = source.samples.length - HEADER_BITS;
        if (data.length > capacity) {
            throw new IllegalArgumentException(
                    "Message trop long (" + data.length + " bits) : capacite audio "
                            + capacity + " bits.");
        }

        boolean[] payload = new boolean[HEADER_BITS + data.length];
        int[] header = bitsOf(data.length, HEADER_BITS);
        for (int i = 0; i < HEADER_BITS; i++) {
            payload[i] = header[i] == 1;
        }
        System.arraycopy(data, 0, payload, HEADER_BITS, data.length);

        int[] samples = source.samples.clone();
        for (int i = 0; i < payload.length; i++) {
            samples[i] = (samples[i] & ~1) | (payload[i] ? 1 : 0);
        }
        return source.rebuild(samples);
    }

    /**
     * Extrait les donnees cachees dans un fichier WAV PCM.
     */
    public static boolean[] extract(byte[] wav) {
        Wav source = Wav.parse(wav);
        boolean[] headerBits = new boolean[HEADER_BITS];
        for (int i = 0; i < HEADER_BITS; i++) {
            headerBits[i] = (source.samples[i] & 1) == 1;
        }
        int length = toInt(headerBits);
        if (length < 0 || length + HEADER_BITS > source.samples.length) {
            throw new IllegalStateException(
                    "En-tete audio invalide : le message n'est pas present.");
        }
        boolean[] data = new boolean[length];
        for (int i = 0; i < length; i++) {
            data[i] = (source.samples[i + HEADER_BITS] & 1) == 1;
        }
        return data;
    }

    /** Ecart quadratique moyen sur la partie PCM utile (sans l'en-tete). */
    public static double mse(byte[] original, byte[] modified) {
        Wav a = Wav.parse(original);
        Wav b = Wav.parse(modified);
        int n = Math.min(a.samples.length, b.samples.length);
        long sum = 0;
        int count = 0;
        for (int i = HEADER_BITS; i < n; i++) {
            long d = a.samples[i] - b.samples[i];
            sum += d * d;
            count++;
        }
        return count == 0 ? 0.0 : (double) sum / count;
    }

    /** Genere un WAV PCM 16-bit mono (sinusoide) de test. */
    public static byte[] synthetic(int sampleRate, double seconds, double frequency) {
        int n = (int) (sampleRate * seconds);
        int dataSize = n * 2;
        byte[] wav = new byte[44 + dataSize];

        // En-tete RIFF/WAVE
        writeAscii(wav, 0, "RIFF");
        writeIntLE(wav, 4, 36 + dataSize);
        writeAscii(wav, 8, "WAVE");

        // Chunk fmt
        writeAscii(wav, 12, "fmt ");
        writeIntLE(wav, 16, 16);
        writeIntLE(wav, 20, 1);          // PCM
        writeIntLE(wav, 22, 1);          // mono
        writeIntLE(wav, 24, sampleRate);
        writeIntLE(wav, 28, sampleRate * 2); // byteRate
        writeIntLE(wav, 32, 2);          // blockAlign
        writeIntLE(wav, 34, 16);         // bitsPerSample

        // Chunk data
        writeAscii(wav, 36, "data");
        writeIntLE(wav, 40, dataSize);
        for (int i = 0; i < n; i++) {
            int sample = (int) (Math.sin(2 * Math.PI * frequency * i / sampleRate)
                    * 12000);
            writeShortLE(wav, 44 + i * 2, sample);
        }
        return wav;
    }

    /* ------------------------------------------------------------------ */

    private static void writeAscii(byte[] out, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            out[off + i] = (byte) s.charAt(i);
        }
    }

    private static void writeIntLE(byte[] out, int off, int value) {
        out[off] = (byte) (value & 0xFF);
        out[off + 1] = (byte) ((value >> 8) & 0xFF);
        out[off + 2] = (byte) ((value >> 16) & 0xFF);
        out[off + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void writeShortLE(byte[] out, int off, int value) {
        out[off] = (byte) (value & 0xFF);
        out[off + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static int readIntLE(byte[] in, int off) {
        return (in[off] & 0xFF)
                | ((in[off + 1] & 0xFF) << 8)
                | ((in[off + 2] & 0xFF) << 16)
                | ((in[off + 3] & 0xFF) << 24);
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

    /** Representation interne d'un WAV PCM. */
    private static final class Wav {
        final byte[] raw;
        final int dataStart;
        final int dataSize;
        final int bitsPerSample;
        final int[] samples;

        private Wav(byte[] raw, int dataStart, int dataSize, int bitsPerSample) {
            this.raw = raw;
            this.dataStart = dataStart;
            this.dataSize = dataSize;
            this.bitsPerSample = bitsPerSample;
            this.samples = extractSamples(raw, dataStart, dataSize, bitsPerSample);
        }

        /** Analyse l'en-tete RIFF/WAVE et localise le chunk 'data'. */
        static Wav parse(byte[] bytes) {
            if (bytes.length < 44
                    || !startsWith(bytes, 0, "RIFF")
                    || !startsWith(bytes, 8, "WAVE")) {
                throw new IllegalArgumentException("Fichier WAV invalide.");
            }
            int bitsPerSample = 16;
            int off = 12;
            while (off + 8 <= bytes.length) {
                String id = ascii(bytes, off);
                int chunkSize = readIntLE(bytes, off + 4);
                int body = off + 8;
                if (id.equals("fmt ")) {
                    bitsPerSample = (bytes[body + 14] & 0xFF)
                            | ((bytes[body + 15] & 0xFF) << 8);
                } else if (id.equals("data")) {
                    return new Wav(bytes, body, chunkSize, bitsPerSample);
                }
                off = body + chunkSize + (chunkSize % 2);
            }
            throw new IllegalArgumentException("Chunk data absent du fichier WAV.");
        }

        private static int[] extractSamples(byte[] bytes, int start, int size,
                                            int bitsPerSample) {
            int step = bitsPerSample / 8;
            int count = Math.min(size / step, (bytes.length - start) / step);
            int[] samples = new int[count];
            for (int i = 0; i < count; i++) {
                int base = start + i * step;
                if (step == 2) {
                    int low = bytes[base] & 0xFF;
                    int high = bytes[base + 1] & 0xFF;
                    samples[i] = (short) ((low) | (high << 8));
                } else {
                    samples[i] = (bytes[base] & 0xFF) - 128;
                }
            }
            return samples;
        }

        /** Reconstruit le fichier WAV avec de nouveaux echantillons. */
        byte[] rebuild(int[] newSamples) {
            byte[] out = raw.clone();
            int step = bitsPerSample / 8;
            int count = Math.min(newSamples.length, samples.length);
            for (int i = 0; i < count; i++) {
                int base = dataStart + i * step;
                if (step == 2) {
                    int v = newSamples[i] & 0xFFFF;
                    out[base] = (byte) (v & 0xFF);
                    out[base + 1] = (byte) ((v >> 8) & 0xFF);
                } else {
                    out[base] = (byte) (newSamples[i] + 128);
                }
            }
            return out;
        }

        static boolean startsWith(byte[] in, int off, String s) {
            for (int i = 0; i < s.length(); i++) {
                if (in[off + i] != (byte) s.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        static String ascii(byte[] in, int off) {
            StringBuilder sb = new StringBuilder(4);
            for (int i = 0; i < 4; i++) {
                sb.append((char) in[off + i]);
            }
            return sb.toString();
        }
    }
}