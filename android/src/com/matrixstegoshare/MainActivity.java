package com.matrixstegoshare;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ecran unique de l'application : l'utilisateur choisit une image de son
 * appareil, saisit un secret, puis l'application integre le secret dans
 * l'image (canal vert, LSB) et affiche l'image stego resultante.
 *
 * Le secret est egalement traite par la brique post-quantique de demonstration
 * (chiffrement LWE + partage de Shamir k-parmi-n) afin d'illustrer le seuil de
 * reconstruction.
 */
public class MainActivity extends Activity {

    private static final long STEGO_KEY = 2023L;
    private static final int SHARES = 10;
    private static final int THRESHOLD = 8;
    private static final int MAX_DIM = 1600;
    private static final int REQ_PICK = 1001;
    private static final int REQ_PERM = 1002;

    private ImageView coverPreview;
    private LinearLayout coverPlaceholder;
    private EditText etSecret;
    private TextView tvBits;
    private Button btnPick;
    private Button btnHide;
    private LinearLayout busyRow;
    private LinearLayout resultCard;
    private ImageView stegoPreview;
    private TextView tvMetrics;
    private TextView tvStatus;
    private TextView tvCrypto;
    private Button btnSave;
    private Button btnReset;

    private Bitmap coverBitmap;
    private Bitmap stegoBitmap;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        coverPreview = findViewById(R.id.coverPreview);
        coverPlaceholder = findViewById(R.id.coverPlaceholder);
        etSecret = findViewById(R.id.etSecret);
        tvBits = findViewById(R.id.tvBits);
        btnPick = findViewById(R.id.btnPick);
        btnHide = findViewById(R.id.btnHide);
        busyRow = findViewById(R.id.busyRow);
        resultCard = findViewById(R.id.resultCard);
        stegoPreview = findViewById(R.id.stegoPreview);
        tvMetrics = findViewById(R.id.tvMetrics);
        tvStatus = findViewById(R.id.tvStatus);
        tvCrypto = findViewById(R.id.tvCrypto);
        btnSave = findViewById(R.id.btnSave);
        btnReset = findViewById(R.id.btnReset);

        View coverFrame = findViewById(R.id.coverFrame);
        coverFrame.setOnClickListener(v -> chooseImage());
        btnPick.setOnClickListener(v -> chooseImage());
        btnHide.setOnClickListener(v -> hideSecret());
        btnSave.setOnClickListener(v -> saveStego());
        btnReset.setOnClickListener(v -> reset());

        etSecret.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                updateBits(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        updateBits(etSecret.getText().toString());
        handleSendIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSendIntent(intent);
    }

    /** Accepte une image partagee depuis une autre application (ACTION_SEND). */
    private void handleSendIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            return;
        }
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) {
            loadCover(uri);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Choix de l'image                                                    */
    /* ------------------------------------------------------------------ */

    private void chooseImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                loadCover(uri);
            }
        }
    }

    private void loadCover(Uri uri) {
        setBusy(true);
        executor.execute(() -> {
            try {
                final Bitmap bmp = loadBitmap(uri);
                main.post(() -> {
                    setBusy(false);
                    coverBitmap = bmp;
                    coverPreview.setImageBitmap(bmp);
                    coverPlaceholder.setVisibility(View.GONE);
                    hideResult();
                });
            } catch (final Exception e) {
                Log.e("MatrixStegoShare", "loadCover failed for " + uri, e);
                main.post(() -> {
                    setBusy(false);
                    toast("Could not open that image");
                });
            }
        });
    }

    private Bitmap loadBitmap(Uri uri) throws Exception {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream exifStream = getContentResolver().openInputStream(uri)) {
            if (exifStream != null) {
                ExifInterface exif = new ExifInterface(exifStream);
                orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Exception ignored) {
            // non-fatal: proceed without orientation
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream s1 = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(s1, null, bounds);
        }
        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / sample > MAX_DIM) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        try (InputStream s2 = getContentResolver().openInputStream(uri)) {
            decoded = BitmapFactory.decodeStream(s2, null, opts);
        }
        if (decoded == null) {
            throw new IllegalStateException("decode failed");
        }
        return applyOrientation(decoded, orientation);
    }

    private Bitmap applyOrientation(Bitmap src, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.postScale(1, -1);
                break;
            default:
                return src;
        }
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (out != src) {
            src.recycle();
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* Traitement                                                          */
    /* ------------------------------------------------------------------ */

    private void hideSecret() {
        final String text = etSecret.getText().toString();
        if (coverBitmap == null) {
            toast(getString(R.string.need_cover));
            return;
        }
        if (text.isEmpty()) {
            toast(getString(R.string.need_secret));
            return;
        }
        hideKeyboard();
        setBusy(true);

        executor.execute(() -> {
            try {
                String binary = SecretCodec.toBinaryString(text);
                boolean[] payload = toBooleans(binary);
                if (payload.length > StegoEngine.capacityBits(coverBitmap)) {
                    throw new IllegalStateException(
                            "This image is too small for the secret.");
                }

                StegoEngine.EmbedResult res =
                        StegoEngine.embed(coverBitmap, payload, true, STEGO_KEY);

                boolean[] extracted = StegoEngine.extract(res.stego, true, STEGO_KEY);
                String recovered = SecretCodec.toString(fromBooleans(extracted));
                boolean stegoOk = recovered.equals(text);

                boolean cryptoOk = false;
                try {
                    LatticeSecretSharing scheme = new LatticeSecretSharing(binary);
                    List<long[]> shares = scheme.generate(SHARES, THRESHOLD);
                    String rec = scheme.reconstruct(
                            new ArrayList<>(shares.subList(0, THRESHOLD)), THRESHOLD);
                    cryptoOk = binary.equals(rec);
                } catch (Exception ignored) {
                }
                final boolean fStegoOk = stegoOk;
                final boolean fCryptoOk = cryptoOk;

                main.post(() -> {
                    setBusy(false);
                    stegoBitmap = res.stego;
                    showResult(res, fStegoOk, fCryptoOk);
                });
            } catch (final Exception e) {
                main.post(() -> {
                    setBusy(false);
                    toast(getString(R.string.error_prefix) + e.getMessage());
                });
            }
        });
    }

    private void showResult(StegoEngine.EmbedResult res, boolean stegoOk, boolean cryptoOk) {
        stegoPreview.setImageBitmap(res.stego);
        String psnr = Double.isInfinite(res.psnr)
                ? "∞" : String.format(Locale.US, "%.2f", res.psnr);
        tvMetrics.setText(String.format(Locale.US,
                "PSNR %s dB  ·  MSE %.5f  ·  payload %d bits",
                psnr, res.mse, res.messageBits));
        tvStatus.setText(stegoOk
                ? "Secret hidden and verified ✓"
                : "Hidden, but extraction did not match");
        tvCrypto.setText(cryptoOk
                ? String.format(Locale.US,
                        "Post-quantum layer: LWE key shared %d-of-%d (Shamir) and reconstructed exactly ✓",
                        THRESHOLD, SHARES)
                : "Post-quantum layer: threshold sharing demonstration");
        resultCard.setVisibility(View.VISIBLE);
        resultCard.bringToFront();
    }

    private void hideResult() {
        resultCard.setVisibility(View.GONE);
        stegoBitmap = null;
    }

    private void reset() {
        hideResult();
        etSecret.setText("");
        etSecret.requestFocus();
    }

    /* ------------------------------------------------------------------ */
    /* Enregistrement dans la galerie                                      */
    /* ------------------------------------------------------------------ */

    private void saveStego() {
        if (stegoBitmap == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
            return;
        }
        doSave(stegoBitmap);
    }

    private void doSave(Bitmap bitmap) {
        String name = "stego_" + System.currentTimeMillis() + ".png";
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/MatrixStegoShare");
            }
            Uri uri = getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("insert failed");
            }
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            toast(getString(R.string.saved_ok));
        } catch (Exception e) {
            toast(getString(R.string.save_failed));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            doSave(stegoBitmap);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Utilitaires d'interface                                             */
    /* ------------------------------------------------------------------ */

    private void updateBits(String text) {
        int bits = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length * 8;
        tvBits.setText(String.format(Locale.US,
                "%d characters · payload %d bits · share %d bits",
                text.length(), bits, LatticeSecretSharing.shareBitLength()));
    }

    private void setBusy(boolean busy) {
        busyRow.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnPick.setEnabled(!busy);
        btnHide.setEnabled(!busy);
        btnHide.setAlpha(busy ? 0.6f : 1f);
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static boolean[] toBooleans(String binary) {
        boolean[] out = new boolean[binary.length()];
        for (int i = 0; i < binary.length(); i++) {
            out[i] = binary.charAt(i) == '1';
        }
        return out;
    }

    private static String fromBooleans(boolean[] bits) {
        StringBuilder sb = new StringBuilder(bits.length);
        for (boolean b : bits) {
            sb.append(b ? '1' : '0');
        }
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
