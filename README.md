# Post-quantum secret sharing via LWE + steganography

Java prototype demonstrating the combination of **post-quantum LWE-based
secret sharing** (Learning With Errors, Regev encryption) with **image
steganography** (LSB and DCT) and **audio steganography** (WAV).

## Principle

1. The holder generates an LWE key pair `(A, b = A·s + e)`;
2. Each bit of the secret is encrypted with LWE (`u = Aᵀx`, `c = b·x + e' + μ·⌊q/2⌋`);
3. The secret key `s` is shared among `n` participants with a threshold `k` using
   a **Shamir scheme over the prime field Z_q**;
4. At least `k` shares reconstruct `s` (Lagrange interpolation), then LWE
   decryption (rounding of `c − ⟨u, s⟩`) reveals the secret.

**Post-quantum security**: the ciphertexts are LWE instances, believed to be
hard even for a quantum adversary (the basis of the NIST PQC standards); the
shares are Shamir evaluations with information-theoretic security (hence
post-quantum). The parameters `(q, N, M)` are reduced for demonstration
purposes.

## Contents

| File | Role |
| --- | --- |
| `LatticeSecretSharing.java` | Generates the shares (Shamir over Z_q of an LWE key) and reconstructs/decrypts the secret. |
| `SecretCodec.java` | Converts non-binary secrets (text, bank codes, files) into binary. |
| `MultiBlockSecretSharing.java` | Shares a multi-block binary secret, block by block (LWE). |
| `LSBSteganography.java` | Hides a share in the least significant bit of the green channel (random order via a key). |
| `DCTSteganography.java` | Hides a share in a DCT coefficient of an 8x8 block of the green channel. |
| `AudioSteganography.java` | Hides a share in the LSB of PCM samples of a WAV file (8/16-bit). |
| `ImageMetrics.java` | Computes MSE and PSNR to assess the visual impact. |
| `Main.java` | Demonstration program of the complete image pipeline. |
| `Benchmark.java` | Automated benchmark: threshold `k`, non-binary secrets, audio and image metrics. |
| `android/` | Android application (`Bitmap` port) and APK build script. |

## Requirements

- JDK 17 or later (`javac` and `java` in the `PATH`).

## Compilation and execution

```bash
javac *.java
java Main                 # synthetic cover image
java Main path/image.png
java Benchmark            # uses the ./images directory (baboon, cat, lenna, pepper)
java Benchmark other/dir  # optional image directory
```

Two stego images are written: `steg_lsb.png` and `steg_dct.png`.

## Android application (APK)

```bash
cd android
./build.sh                # generates build/MatrixStegoShare.apk
adb install build/MatrixStegoShare.apk
```

The build uses the SDK tools directly (`aapt2`, `d8`, `zipalign`, `apksigner`),
without Gradle. See `android/build.sh`.

### User flow

1. **Choose an image** — *Choose image* button (`ACTION_OPEN_DOCUMENT`) or
   share a photo from another application (`ACTION_SEND`, `image/*`).
   The image is resampled to 1600 px and the EXIF orientation is corrected.
2. **Enter the secret** — free text converted to binary (`SecretCodec`), with a
   live preview of the number of bits and the share size.
3. **Hide secret in image** — LSB insertion in the green channel, followed by
   immediate verification extraction; display of `PSNR`/`MSE` and a crypto
   badge (LWE key shared as 8-of-10 by Shamir, reconstructed identically ✓).
4. **Save to gallery** — the stego image is written to
   `Pictures/MatrixStegoShare/` via `MediaStore`. *Start over* resets.

The interface follows the Material theme (cards, buttons, vector icons) and
works without Gradle.

## Cleanup

```bash
rm -f *.class steg_lsb.png steg_dct.png
```
