# Secret sharing post-quantique via LWE + stéganographie

Prototype Java démontrant la combinaison d'un **partage de secret
post-quantique basé sur LWE** (Learning With Errors, chiffrement de Regev)
avec la **stéganographie par image** (LSB et DCT) et par **audio** (WAV).

## Principe

1. Le détenteur génère une paire de clés LWE `(A, b = A·s + e)` ;
2. Chaque bit du secret est chiffré en LWE (`u = Aᵀx`, `c = b·x + e' + μ·⌊q/2⌋`) ;
3. La clé secrète `s` est partagée entre `n` participants avec un seuil `k` par
   un **schéma de Shamir sur le corps premier Z_q** ;
4. Au moins `k` parts reconstituent `s` (interpolation de Lagrange), puis le
   déchiffrement LWE (arrondi de `c − ⟨u, s⟩`) révèle le secret.

**Sécurité post-quantique** : les chiffrés sont des instances LWE, réputées
difficiles même pour un adversaire quantique (base des standards NIST PQC) ; les
parts sont des évaluations de Shamir, à sécurité informationnelle (donc
post-quantique). Les paramètres `(q, N, M)` sont réduits à but démonstratif.

## Contenu

| Fichier | Rôle |
| --- | --- |
| `LatticeSecretSharing.java` | Génère les parts (Shamir sur Z_q d'une clé LWE) et reconstitue/déchiffre le secret. |
| `SecretCodec.java` | Convertit les secrets non binaires (texte, codes bancaires, fichiers) en binaire. |
| `MultiBlockSecretSharing.java` | Partage un secret binaire multi-bloc, bloc par bloc (LWE). |
| `LSBSteganography.java` | Cache une part dans le bit de poids faible du canal vert (ordre aléatoire via une clé). |
| `DCTSteganography.java` | Cache une part dans un coefficient DCT d'un bloc 8x8 du canal vert. |
| `AudioSteganography.java` | Cache une part dans le LSB des échantillons PCM d'un WAV (8/16 bits). |
| `ImageMetrics.java` | Calcule MSE et PSNR pour évaluer l'impact visuel. |
| `Main.java` | Programme de démonstration du pipeline image complet. |
| `Benchmark.java` | Benchmark automatisé : seuil `k`, secrets non binaires, audio et métriques image. |
| `android/` | Application Android (portage `Bitmap`) et script de build d'APK. |

## Prérequis

- JDK 17 ou plus (`javac` et `java` dans le `PATH`).

## Compilation et exécution

```bash
javac *.java
java Main                 # image de couverture synthétique
java Main chemin/image.png
java Benchmark            # utilise le répertoire ./images (baboon, cat, lenna, pepper)
java Benchmark autre/rep  # répertoire d'images optionnel
```

Deux images stégo sont écrites : `steg_lsb.png` et `steg_dct.png`.

## Application Android (APK)

```bash
cd android
./build.sh                # génère build/MatrixStegoShare.apk
adb install build/MatrixStegoShare.apk
```

Le build utilise directement les outils du SDK (`aapt2`, `d8`, `zipalign`,
`apksigner`), sans Gradle. Voir `android/build.sh`.

### Parcours utilisateur

1. **Choisir une image** — bouton *Choose image* (`ACTION_OPEN_DOCUMENT`) ou
   partage d'une photo depuis une autre application (`ACTION_SEND`, `image/*`).
   L'image est rééchantillonnée à 1600 px et l'orientation EXIF est corrigée.
2. **Saisir le secret** — texte libre converti en binaire (`SecretCodec`), avec
   aperçu en direct du nombre de bits et de la taille de part.
3. **Hide secret in image** — insertion LSB dans le canal vert, puis extraction
   immédiate de contrôle ; affichage de `PSNR`/`MSE` et d'un badge crypto
   (clé LWE partagée en 8-of-10 par Shamir, reconstituée à l'identique ✓).
4. **Save to gallery** — l'image stégo est écrite dans
   `Pictures/MatrixStegoShare/` via `MediaStore`. *Start over* réinitialise.

L'interface suit le thème Material (cartes, boutons, icônes vectorielles) et
fonctionne sans Gradle.

## Nettoyage

```bash
rm -f *.class steg_lsb.png steg_dct.png
```
