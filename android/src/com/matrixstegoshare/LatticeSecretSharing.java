package com.matrixstegoshare;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Partage de secret post-quantique base sur LWE (Learning With Errors).
 *
 * Construction (inspiree du chiffrement de Regev, 2005) :
 *   1. Le detenteur genere une paire de cles LWE (A, b = A.s + e) ou s est un
 *      vecteur binaire secret (dimension N) et e un petit bruit ;
 *   2. Chaque bit du secret est chiffre (chiffrement LWE de Regev) sous cette
 *      cle publique : u = A^T x, c = b.x + e' + mu * floor(q/2) ;
 *   3. Le vecteur secret s est partage entre n participants avec un seuil k au
 *      moyen d'un schema de Shamir sur le corps premier Z_q (interpolation
 *      polynomiale) : chaque participant recoit une part (x_i, f_1(x_i), ...,
 *      f_N(x_i)) ;
 *   4. La reconstitution assemble au moins k parts, interpole s (Shamir), puis
 *      dechiffre chaque bit en arrondissant c - <u, s>.
 *
 * Securite (post-quantique) :
 *   - la cle publique et les chiffres sont des instances LWE : retrouver s ou
 *     les bits sans les parts equivaut a resoudre LWE, repute difficile meme
 *     pour un adversaire quantique (base des standards NIST PQC) ;
 *   - les parts sont des evaluations de Shamir : moins de k parts ne revelent
 *     aucune information (securite informationnelle, donc post-quantique).
 *
 * Parametres : jeu reduit a but demonstratif (Q, N, M), non dimensionne pour
 * un deploiement reel.
 */
public final class LatticeSecretSharing {

    /** Module premier (corps Z_Q). */
    public static final int Q = 12289;
    /** Dimension du vecteur secret LWE s. */
    public static final int N = 8;
    /** Nombre d'echantillons LWE composant la cle publique. */
    public static final int M = 32;

    private static final long HALF_Q = Q / 2;
    private static final long QUARTER_Q = Q / 4;

    /** Bits de codage de l'indice de participant dans une part serialisee. */
    private static final int INDEX_BITS = 16;
    /** Bits de codage d'un coefficient de Z_Q (14 bits suffisent pour Q). */
    private static final int ELEM_BITS = 14;

    private final String secretBinary;
    private final long[] s;        // cle secrete LWE (N)
    private final long[][] a;      // matrice publique (M x N)
    private final long[] b;        // vecteur public A.s + e (M)
    private final long[][] cipherU; // chiffre LWE par bit (nbBits x N)
    private final long[] cipherC;   // composante scalaire du chiffre (nbBits)

    private final SecureRandom rnd = new SecureRandom();

    public LatticeSecretSharing(String secretBinary) {
        if (secretBinary == null
                || secretBinary.isEmpty()
                || !secretBinary.matches("[01]+")) {
            throw new IllegalArgumentException(
                    "Le secret doit être une chaîne binaire non vide.");
        }
        this.secretBinary = secretBinary;

        // --- 1. Generation de la cle LWE : b = A.s + e --------------------
        this.s = new long[N];
        for (int i = 0; i < N; i++) {
            s[i] = rnd.nextInt(2); // cle secrete binaire
        }
        this.a = new long[M][N];
        for (int i = 0; i < M; i++) {
            for (int j = 0; j < N; j++) {
                a[i][j] = rnd.nextInt(Q);
            }
        }
        this.b = new long[M];
        for (int i = 0; i < M; i++) {
            long sum = 0;
            for (int j = 0; j < N; j++) {
                sum = (sum + a[i][j] * s[j]) % Q;
            }
            long e = rnd.nextInt(3) - 1; // bruit dans {-1, 0, 1}
            b[i] = mod(sum + e);
        }

        // --- 2. Chiffrement LWE de chaque bit du secret -------------------
        int nbBits = secretBinary.length();
        this.cipherU = new long[nbBits][N];
        this.cipherC = new long[nbBits];
        for (int t = 0; t < nbBits; t++) {
            int[] x = new int[M];
            for (int i = 0; i < M; i++) {
                x[i] = rnd.nextInt(2);
            }
            for (int k = 0; k < N; k++) {
                long sum = 0;
                for (int i = 0; i < M; i++) {
                    if (x[i] == 1) {
                        sum = (sum + a[i][k]) % Q;
                    }
                }
                cipherU[t][k] = sum;
            }
            long cs = 0;
            for (int i = 0; i < M; i++) {
                if (x[i] == 1) {
                    cs = (cs + b[i]) % Q;
                }
            }
            int mu = secretBinary.charAt(t) - '0';
            long e2 = rnd.nextInt(3) - 1;
            cs = (cs + e2 + (mu == 1 ? HALF_Q : 0)) % Q;
            cipherC[t] = mod(cs);
        }
    }

    public int size() {
        return secretBinary.length();
    }

    public String secret() {
        return secretBinary;
    }

    public int modulus() {
        return Q;
    }

    public int dimension() {
        return N;
    }

    public int samples() {
        return M;
    }

    /* ------------------------------------------------------------------ */
    /* Generation / reconstitution des parts (Shamir sur Z_Q)             */
    /* ------------------------------------------------------------------ */

    /**
     * Genere n parts avec un seuil k : chaque part rassemble les evaluations de
     * N polynomes de Shamir (un par coordonnee de la cle secrete s).
     *
     * @param n nombre de participants (indices 1..n).
     * @param k seuil de reconstruction (k &lt;= n).
     * @return la liste des n parts ; une part est un tableau
     *         [indice, f_1(x), ..., f_N(x)].
     */
    public List<long[]> generate(int n, int k) {
        if (n < 1) {
            throw new IllegalArgumentException("Nombre de parts invalide : " + n);
        }
        if (k < 1 || k > n) {
            throw new IllegalArgumentException(
                    "Seuil invalide : k (" + k + ") doit être dans [1, " + n + "].");
        }
        List<long[]> shares = new ArrayList<>();
        long[][] coeffs = new long[N][k];
        for (int j = 0; j < N; j++) {
            coeffs[j][0] = s[j];
            for (int t = 1; t < k; t++) {
                coeffs[j][t] = rnd.nextInt(Q);
            }
        }
        for (int i = 1; i <= n; i++) {
            long[] share = new long[N + 1];
            share[0] = i;
            for (int j = 0; j < N; j++) {
                share[j + 1] = evalPoly(coeffs[j], i);
            }
            shares.add(share);
        }
        return shares;
    }

    /**
     * Reconstitue le secret a partir d'au moins k parts, puis dechiffre.
     *
     * @return le secret binaire, ou null si moins de k parts sont fournies.
     */
    public String reconstruct(List<long[]> shares, int k) {
        if (k < 1) {
            throw new IllegalArgumentException("Seuil invalide : " + k);
        }
        if (shares.size() < k) {
            return null;
        }
        List<long[]> chosen = shares.subList(0, k);
        long[] xs = new long[k];
        for (int i = 0; i < k; i++) {
            xs[i] = chosen.get(i)[0];
        }
        long[] recovered = new long[N];
        for (int j = 0; j < N; j++) {
            long[] ys = new long[k];
            for (int i = 0; i < k; i++) {
                ys[i] = chosen.get(i)[j + 1];
            }
            recovered[j] = lagrangeAtZero(xs, ys);
        }
        return decrypt(recovered);
    }

    /* ------------------------------------------------------------------ */
    /* Dechiffrement LWE                                                   */
    /* ------------------------------------------------------------------ */

    private String decrypt(long[] key) {
        StringBuilder sb = new StringBuilder(secretBinary.length());
        for (int t = 0; t < cipherC.length; t++) {
            long dot = 0;
            for (int j = 0; j < N; j++) {
                dot = (dot + cipherU[t][j] * key[j]) % Q;
            }
            long d = mod(cipherC[t] - dot);
            if (d > HALF_Q) {
                d -= Q; // recentrage dans (-Q/2, Q/2]
            }
            sb.append(Math.abs(d) > QUARTER_Q ? '1' : '0');
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /* Serialisation d'une part en bits (pour la steganographie)          */
    /* ------------------------------------------------------------------ */

    /** Longueur en bits d'une part serialisee. */
    public static int shareBitLength() {
        return INDEX_BITS + N * ELEM_BITS;
    }

    public static boolean[] toBits(long[] share) {
        boolean[] bits = new boolean[shareBitLength()];
        writeBits(bits, 0, INDEX_BITS, share[0]);
        for (int j = 0; j < N; j++) {
            writeBits(bits, INDEX_BITS + j * ELEM_BITS, ELEM_BITS, share[j + 1]);
        }
        return bits;
    }

    public static long[] fromBits(boolean[] bits) {
        if (bits.length < shareBitLength()) {
            throw new IllegalArgumentException(
                    "Part trop courte (" + bits.length + " bits, attendu "
                            + shareBitLength() + ").");
        }
        long[] share = new long[N + 1];
        share[0] = readBits(bits, 0, INDEX_BITS);
        for (int j = 0; j < N; j++) {
            share[j + 1] = readBits(bits, INDEX_BITS + j * ELEM_BITS, ELEM_BITS);
        }
        return share;
    }

    private static void writeBits(boolean[] out, int off, int nBits, long value) {
        for (int i = nBits - 1; i >= 0; i--) {
            out[off + i] = ((value >>> (nBits - 1 - i)) & 1L) == 1L;
        }
    }

    private static long readBits(boolean[] in, int off, int nBits) {
        long value = 0;
        for (int i = 0; i < nBits; i++) {
            value = (value << 1) | (in[off + i] ? 1 : 0);
        }
        return value;
    }

    /* ------------------------------------------------------------------ */
    /* Arithmetique modulaire                                              */
    /* ------------------------------------------------------------------ */

    private static long evalPoly(long[] coeffs, long x) {
        long result = 0;
        for (int t = coeffs.length - 1; t >= 0; t--) {
            result = (result * x + coeffs[t]) % Q;
        }
        return result;
    }

    /** Interpolation de Lagrange au point 0 sur les couples (xs, ys). */
    private static long lagrangeAtZero(long[] xs, long[] ys) {
        long result = 0;
        int n = xs.length;
        for (int i = 0; i < n; i++) {
            long num = 1;
            long den = 1;
            for (int l = 0; l < n; l++) {
                if (l == i) {
                    continue;
                }
                num = (num * mod(-xs[l])) % Q;
                den = (den * mod(xs[i] - xs[l])) % Q;
            }
            long li = (num * modInverse(den)) % Q;
            result = (result + ys[i] * li) % Q;
        }
        return mod(result);
    }

    private static long mod(long v) {
        long r = v % Q;
        return r < 0 ? r + Q : r;
    }

    private static long modInverse(long value) {
        long a = mod(value);
        long b = Q;
        long x0 = 1;
        long x1 = 0;
        while (b != 0) {
            long q = a / b;
            long t = a - q * b;
            a = b;
            b = t;
            long tx = x0 - q * x1;
            x0 = x1;
            x1 = tx;
        }
        return mod(x0);
    }
}
