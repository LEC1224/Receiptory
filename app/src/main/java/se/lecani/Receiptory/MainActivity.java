package se.lecani.Receiptory;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import com.google.common.util.concurrent.ListenableFuture;
import com.android.billingclient.api.Purchase;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.NumberFormat;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Currency;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity implements BillingManager.Listener {
    private static final int CAMERA_PERMISSION_REQUEST = 42;
    private static final String PRIVACY_POLICY_URL = "https://github.com/LEC1224/Receiptory/blob/master/PRIVACY_POLICY.md";
    private static final String TERMS_URL = "https://github.com/LEC1224/Receiptory/blob/master/TERMS.md";
    private static final String[] CATEGORY_ICONS = {
            "🧾", "🛒", "🍽", "🛠", "💻", "📱", "⛽", "🏥", "🏠", "👕",
            "✈", "🏨", "☕", "🎁", "🎓", "⚡", "🧴", "🧰", "📦", "💳"
    };

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService apiExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> imageCache = new LruCache<String, Bitmap>(
            (int) Math.max(4 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 8)
    ) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount() / 1024;
        }
    };

    private FrameLayout root;
    private ReceiptStore receiptStore;
    private SettingsStore settingsStore;
    private ReceiptBackendClient receiptExtractor;
    private OpenAiReceiptExtractor openAiReceiptExtractor;
    private BillingManager billingManager;
    private EntitlementState entitlementState = EntitlementState.empty();
    private final Set<String> verifyingPurchaseTokens = new HashSet<>();
    private ImageCapture imageCapture;
    private File pendingCapture;
    private DateFilter activeFilter = DateFilter.all();
    private Palette palette;
    private int bottomInset = 0;
    private String fullscreenReceiptId;
    private String storageSearchQuery = "";
    private Runnable currentBackAction;
    private ImageButton shutterButton;
    private ProgressBar shutterProgress;
    private boolean captureInProgress = false;
    private ActivityResultLauncher<String> createBackupLauncher;
    private ActivityResultLauncher<String[]> restoreBackupLauncher;
    private boolean pendingRestoreReplace = false;
    private boolean settingsVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        receiptStore = new ReceiptStore(this);
        receiptStore.ensureDefaultCategories();
        settingsStore = new SettingsStore(this);
        receiptExtractor = new ReceiptBackendClient();
        openAiReceiptExtractor = new OpenAiReceiptExtractor();
        billingManager = new BillingManager(this, this);
        palette = Palette.from(this, settingsStore.getTheme());
        registerBackupRestoreLaunchers();
        applySystemBars();

        root = new FrameLayout(this);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            bottomInset = insets.getSystemWindowInsetBottom();
            view.setPadding(0, 0, 0, bottomInset);
            return insets;
        });
        setContentView(root);
        billingManager.start();
        refreshEntitlements(false);
        showCamera();
    }

    private void registerBackupRestoreLaunchers() {
        createBackupLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/zip"),
                uri -> {
                    if (uri != null) {
                        exportBackup(uri);
                    }
                }
        );
        restoreBackupLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );
                        } catch (SecurityException ignored) {
                        }
                        importBackup(uri, pendingRestoreReplace);
                    }
                }
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        apiExecutor.shutdown();
        imageExecutor.shutdown();
        billingManager.destroy();
    }

    @Override
    public void onBackPressed() {
        if (fullscreenReceiptId != null) {
            String receiptId = fullscreenReceiptId;
            fullscreenReceiptId = null;
            showReceiptDetail(receiptId);
            return;
        }
        if (currentBackAction != null) {
            currentBackAction.run();
            return;
        }
        super.onBackPressed();
    }

    private void showCamera() {
        currentBackAction = null;
        fullscreenReceiptId = null;
        captureInProgress = false;
        shutterButton = null;
        shutterProgress = null;
        FrameLayout cameraRoot = new FrameLayout(this);
        cameraRoot.setBackgroundColor(Color.BLACK);
        transitionRootView(cameraRoot, Color.BLACK);
        attachSwipe(cameraRoot, this::showCategories, this::showSettings);

        PreviewView previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        cameraRoot.addView(previewView, matchParent());

        LinearLayout cameraHeader = cameraHeader();
        FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(wrap(), dp(46), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        headerParams.topMargin = dp(34);
        cameraRoot.addView(cameraHeader, headerParams);

        ImageButton settingsButton = floatingIconButton(R.drawable.ic_settings);
        FrameLayout.LayoutParams settingsParams = cornerButtonParams(Gravity.TOP | Gravity.START);
        cameraRoot.addView(settingsButton, settingsParams);
        settingsButton.setOnClickListener(view -> showSettings());

        ImageButton storageButton = floatingIconButton(R.drawable.ic_folder);
        FrameLayout.LayoutParams storageParams = cornerButtonParams(Gravity.TOP | Gravity.END);
        cameraRoot.addView(storageButton, storageParams);
        storageButton.setOnClickListener(view -> showCategories());

        FrameLayout shutterFrame = new FrameLayout(this);
        ImageButton shutter = new ImageButton(this);
        shutterButton = shutter;
        shutter.setImageResource(R.drawable.ic_camera);
        shutter.setColorFilter(palette.accent);
        shutter.setPadding(dp(20), dp(20), dp(20), dp(20));
        shutter.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable shutterBackground = new GradientDrawable();
        shutterBackground.setShape(GradientDrawable.OVAL);
        shutterBackground.setColor(palette.shutter);
        shutterBackground.setStroke(dp(5), palette.shutterRing);
        shutter.setBackground(withRipple(shutterBackground));
        installPressFeedback(shutter);
        shutterFrame.addView(shutter, matchParent());

        ProgressBar progress = new ProgressBar(this);
        shutterProgress = progress;
        progress.setVisibility(View.GONE);
        progress.setIndeterminate(true);
        progress.setAlpha(0.9f);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER);
        shutterFrame.addView(progress, progressParams);

        FrameLayout.LayoutParams shutterParams = new FrameLayout.LayoutParams(dp(76), dp(76), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        shutterParams.bottomMargin = dp(36);
        cameraRoot.addView(shutterFrame, shutterParams);
        shutter.setOnClickListener(view -> captureReceipt());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera(previewView);
        } else {
            requestCameraPermissionWithContext();
        }
    }

    private void startCamera(PreviewView previewView) {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                Preview preview = new Preview.Builder().build();
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
            } catch (Exception exception) {
                toast("Camera could not start: " + exception.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void captureReceipt() {
        if (captureInProgress) {
            return;
        }
        if (imageCapture == null) {
            toast("Camera is not ready yet.");
            return;
        }

        setCaptureInProgress(true);
        File outputFile = new File(getCacheDir(), "capture_" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(outputFile).build();
        imageCapture.takePicture(options, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                runOnUiThread(() -> showCaptureReview(outputFile));
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                runOnUiThread(() -> {
                    setCaptureInProgress(false);
                    toast("Could not take photo: " + exception.getMessage());
                });
            }
        });
    }

    private void setCaptureInProgress(boolean inProgress) {
        captureInProgress = inProgress;
        if (shutterButton != null) {
            shutterButton.setEnabled(!inProgress);
            shutterButton.setAlpha(inProgress ? 0.5f : 1.0f);
            shutterButton.setColorFilter(inProgress ? palette.muted : palette.accent);
        }
        if (shutterProgress != null) {
            shutterProgress.setVisibility(inProgress ? View.VISIBLE : View.GONE);
        }
    }

    private void showCaptureReview(File photoFile) {
        currentBackAction = this::showCamera;
        pendingCapture = photoFile;
        FrameLayout reviewRoot = new FrameLayout(this);
        reviewRoot.setBackgroundColor(Color.BLACK);
        transitionRootView(reviewRoot, Color.BLACK);

        ImageView imageView = new ImageView(this);
        loadPhoto(imageView, photoFile, 420, 760, ImageView.ScaleType.FIT_CENTER);
        reviewRoot.addView(imageView, matchParent());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(dp(16), dp(16), dp(16), dp(36));
        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(match(), wrap(), Gravity.BOTTOM);
        reviewRoot.addView(actions, actionParams);

        Button retake = secondaryButton("Retake");
        actions.addView(retake, weightedButton());
        retake.setOnClickListener(view -> showCamera());

        Button submit = primaryButton("Scan with AI");
        actions.addView(submit, weightedButton());
        submit.setOnClickListener(view -> submitReceipt(photoFile));

        Button manual = secondaryButton("Save manually");
        actions.addView(manual, weightedButton());
        manual.setOnClickListener(view -> saveManualReceipt(photoFile));
    }

    private void submitReceipt(File photoFile) {
        if (!ensureAiProviderConfigured()) {
            showSettings();
            return;
        }
        if (!ensureAiCreditsOrOpenStore(1)) {
            return;
        }

        showLoading(settingsStore.useOwnOpenAiKey()
                ? "Uploading receipt photo to OpenAI..."
                : "Uploading receipt photo to AI backend...");
        apiExecutor.execute(() -> {
            try {
                File storedPhoto = receiptStore.savePhoto(Uri.fromFile(photoFile));
                ReceiptExtraction extraction = extractReceiptWithConfiguredAi(storedPhoto);
                String categoryId = resolveCategoryId(extraction);
                String receiptDate = extraction.date == null || extraction.date.isEmpty()
                        ? new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date())
                        : extraction.date;
                Receipt receipt = new Receipt(
                        UUID.randomUUID().toString(),
                        categoryId,
                        extraction.merchant,
                        receiptDate,
                        extraction.total,
                        storedPhoto.getAbsolutePath(),
                        extraction.items,
                        extraction.rawText,
                        System.currentTimeMillis(),
                        false,
                        Receipt.AI_SCAN_EXTRACTED,
                        System.currentTimeMillis(),
                        ""
                );
                receiptStore.addReceipt(receipt);
                runOnUiThread(() -> {
                    if (!settingsStore.useOwnOpenAiKey()) {
                        refreshEntitlements(false);
                    }
                    showReceiptDetail(receipt.id);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    toast(exception.getMessage());
                    if (pendingCapture != null) {
                        showCaptureReview(pendingCapture);
                    } else {
                        showCamera();
                    }
                });
            }
        });
    }

    private void saveManualReceipt(File photoFile) {
        showLoading("Saving receipt...");
        apiExecutor.execute(() -> {
            try {
                File storedPhoto = receiptStore.savePhoto(Uri.fromFile(photoFile));
                Receipt receipt = new Receipt(
                        UUID.randomUUID().toString(),
                        defaultCategoryId(),
                        "",
                        "",
                        0,
                        storedPhoto.getAbsolutePath(),
                        new ArrayList<>(),
                        "",
                        System.currentTimeMillis(),
                        false,
                        Receipt.AI_SCAN_MANUAL,
                        0,
                        ""
                );
                receiptStore.addReceipt(receipt);
                runOnUiThread(() -> showEditReceipt(receipt.id));
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    toast(exception.getMessage());
                    showCaptureReview(photoFile);
                });
            }
        });
    }

    private String resolveCategoryId(ReceiptExtraction extraction) {
        if ("use_existing".equals(extraction.categoryAction)
                && receiptStore.getCategory(extraction.existingCategoryId) != null) {
            return extraction.existingCategoryId;
        }
        String name = extraction.newCategoryName == null || extraction.newCategoryName.isEmpty()
                ? "Other"
                : extraction.newCategoryName;
        return receiptStore.addAiSuggestedCategory(name).id;
    }

    private String defaultCategoryId() {
        for (Category category : receiptStore.getCategories()) {
            if ("Other".equalsIgnoreCase(category.name)) {
                return category.id;
            }
        }
        return receiptStore.addCategory("Other").id;
    }

    private void showSettings() {
        ScrollView scrollView = page("Settings", this::showCamera);
        attachSwipe(scrollView, this::showCamera, null);
        LinearLayout content = (LinearLayout) scrollView.getChildAt(0);

        LinearLayout appearanceCard = card();
        appearanceCard.addView(label("Appearance"));

        RadioGroup themeGroup = new RadioGroup(this);
        themeGroup.setOrientation(RadioGroup.VERTICAL);
        addThemeOption(themeGroup, SettingsStore.THEME_SYSTEM, "System");
        addThemeOption(themeGroup, SettingsStore.THEME_LIGHT, "Bright");
        addThemeOption(themeGroup, SettingsStore.THEME_DARK, "Dark");
        appearanceCard.addView(themeGroup, matchWrap());
        content.addView(appearanceCard, matchWrap());

        LinearLayout aiCard = card();
        aiCard.addView(label("AI backend"));
        aiCard.addView(subtitle("Default AI scans upload the receipt photo, category names, and your installation ID to this backend. The backend forwards the photo and category context to OpenAI for extraction and uses scan credits."));

        EditText backendUrl = input("Backend URL");
        backendUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        backendUrl.setText(settingsStore.getBackendUrl());
        aiCard.addView(backendUrl, matchWrap());

        CheckBox useOwnOpenAiKey = new CheckBox(this);
        useOwnOpenAiKey.setText("Use my own OpenAI key instead of scan credits");
        useOwnOpenAiKey.setTextColor(palette.text);
        useOwnOpenAiKey.setTextSize(15);
        useOwnOpenAiKey.setChecked(settingsStore.useOwnOpenAiKey());
        useOwnOpenAiKey.setPadding(dp(6), dp(8), dp(6), dp(8));
        aiCard.addView(useOwnOpenAiKey, matchWrap());

        EditText openAiApiKey = input("OpenAI API key");
        openAiApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        openAiApiKey.setText(settingsStore.getOpenAiApiKey());
        aiCard.addView(openAiApiKey, matchWrap());

        EditText openAiModel = input("OpenAI model");
        openAiModel.setText(settingsStore.getOpenAiModel());
        aiCard.addView(openAiModel, matchWrap());

        CheckBox allowAiNewCategories = new CheckBox(this);
        allowAiNewCategories.setText("Allow AI to suggest new category");
        allowAiNewCategories.setTextColor(palette.text);
        allowAiNewCategories.setTextSize(15);
        allowAiNewCategories.setChecked(settingsStore.allowAiNewCategories());
        allowAiNewCategories.setPadding(dp(6), dp(8), dp(6), dp(8));
        aiCard.addView(allowAiNewCategories, matchWrap());
        content.addView(aiCard, matchWrap());

        LinearLayout trustCard = card();
        trustCard.addView(label("Trust and privacy"));
        trustCard.addView(subtitle("See what stays on this device, what is uploaded for AI scanning, and where to find the Privacy Policy and Terms."));
        Button trust = iconButtonText(R.drawable.ic_receipt, "Open trust and privacy");
        trustCard.addView(trust, compactButtonParams());
        trust.setOnClickListener(view -> showTrustAndPrivacy());
        content.addView(trustCard, matchWrap());

        addStoreCard(content);

        LinearLayout moneyCard = card();
        moneyCard.addView(label("Currency"));

        EditText currency = input("Currency code");
        currency.setText(settingsStore.getCurrencyCode());
        moneyCard.addView(currency, matchWrap());
        content.addView(moneyCard, matchWrap());

        LinearLayout backupCard = card();
        backupCard.addView(label("Local backup"));
        backupCard.addView(subtitle("Export creates a zip file with local receipt records and stored photos. Restore imports a backup onto this device."));
        Button backup = iconButtonText(R.drawable.ic_folder, "Back up receipts");
        backupCard.addView(backup, compactButtonParams());
        backup.setOnClickListener(view -> startBackup());
        Button restore = iconButtonText(R.drawable.ic_folder, "Restore backup");
        backupCard.addView(restore, compactButtonParams());
        restore.setOnClickListener(view -> showRestoreChoiceDialog());
        content.addView(backupCard, matchWrap());

        Button save = primaryButton("Save settings");
        content.addView(save, matchWrap());
        save.setOnClickListener(view -> {
            settingsStore.setBackendUrl(backendUrl.getText().toString());
            settingsStore.setUseOwnOpenAiKey(useOwnOpenAiKey.isChecked());
            settingsStore.setOpenAiApiKey(openAiApiKey.getText().toString());
            settingsStore.setOpenAiModel(openAiModel.getText().toString());
            settingsStore.setAllowAiNewCategories(allowAiNewCategories.isChecked());
            settingsStore.setCurrencyCode(currency.getText().toString());
            int checkedId = themeGroup.getCheckedRadioButtonId();
            View checked = themeGroup.findViewById(checkedId);
            if (checked != null && checked.getTag() instanceof String) {
                settingsStore.setTheme((String) checked.getTag());
            }
            palette = Palette.from(this, settingsStore.getTheme());
            applySystemBars();
            showCamera();
        });

    }

    private void startBackup() {
        String fileName = "receiptory-backup-"
                + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date())
                + ".zip";
        createBackupLauncher.launch(fileName);
    }

    private void exportBackup(Uri uri) {
        showLoading("Creating backup...");
        apiExecutor.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) {
                    throw new IllegalStateException("Could not open backup destination.");
                }
                receiptStore.exportBackup(output);
                runOnUiThread(() -> {
                    toast("Backup created.");
                    showSettings();
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    toast("Backup failed: " + exception.getMessage());
                    showSettings();
                });
            }
        });
    }

    private void showRestoreChoiceDialog() {
        String[] choices = {"Merge with current data", "Replace current data"};
        new AlertDialog.Builder(this)
                .setTitle("Restore backup")
                .setItems(choices, (dialog, which) -> {
                    pendingRestoreReplace = which == 1;
                    restoreBackupLauncher.launch(new String[]{"application/zip", "application/octet-stream", "*/*"});
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void importBackup(Uri uri, boolean replace) {
        showLoading(replace ? "Replacing from backup..." : "Merging backup...");
        apiExecutor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    throw new IllegalStateException("Could not open backup.");
                }
                ReceiptStore.RestoreResult result = receiptStore.importBackup(input, replace);
                runOnUiThread(() -> {
                    toast("Restore complete: " + result.addedReceipts + " receipts, "
                            + result.addedCategories + " categories"
                            + (result.skippedReceipts > 0 ? ", " + result.skippedReceipts + " skipped" : "") + ".");
                    showCategories();
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    toast("Restore failed: " + exception.getMessage());
                    showSettings();
                });
            }
        });
    }

    private void showTrustAndPrivacy() {
        ScrollView scrollView = page("Trust and privacy", this::showSettings);
        LinearLayout content = (LinearLayout) scrollView.getChildAt(0);

        LinearLayout localCard = card();
        localCard.addView(label("Stored on this device"));
        localCard.addView(subtitle("Saved receipts, receipt photos, categories, extracted text, item rows, totals, currency, theme, backend URL, optional OpenAI key/model, and this app installation ID are stored in the app's local storage."));
        localCard.addView(subtitle("Manual receipt entry stays local unless you later choose to scan that saved receipt with AI."));
        content.addView(localCard, matchWrap());

        LinearLayout uploadCard = card();
        uploadCard.addView(label("Uploaded for AI scanning"));
        uploadCard.addView(subtitle("By default, AI scans upload the receipt photo, category names, the allow-new-categories setting, and the installation ID to the configured backend URL."));
        uploadCard.addView(subtitle("The backend sends the receipt image and category context to OpenAI, then returns merchant, date, total, item rows, raw extracted text, and a category decision."));
        uploadCard.addView(subtitle("If you turn on own OpenAI key mode, AI scans use your key and send receipt photos and category context directly from this device to OpenAI instead of using backend scan credits."));
        uploadCard.addView(subtitle("The backend also receives the installation ID for scan credit checks and purchase verification. Google Play purchase tokens are sent to the backend only when verifying scan pack purchases."));
        content.addView(uploadCard, matchWrap());

        LinearLayout controlCard = card();
        controlCard.addView(label("Deletion and export"));
        controlCard.addView(subtitle("Deleting a receipt removes its local record and stored photo. Deleting a category removes that local category and the receipts/photos in it."));
        controlCard.addView(subtitle("Export creates a zip backup containing local receipt data and photos. Restore reads a selected backup file and imports it onto this device."));
        controlCard.addView(subtitle("The app does not currently provide an in-app request to delete data that may have been processed by your configured backend or OpenAI."));
        content.addView(controlCard, matchWrap());

        LinearLayout permissionCard = card();
        permissionCard.addView(label("Permissions"));
        permissionCard.addView(subtitle("Camera is used to photograph receipts. Internet is used for AI scanning, scan credit checks, purchase verification, and opening policy links. Billing is used for Google Play AI scan pack purchases."));
        content.addView(permissionCard, matchWrap());

        LinearLayout linksCard = card();
        linksCard.addView(label("Policies"));
        linksCard.addView(subtitle("These documents are versioned in the GitHub repository so the Play listing can point to stable public URLs."));
        Button privacy = iconButtonText(R.drawable.ic_receipt, "Privacy Policy");
        linksCard.addView(privacy, compactButtonParams());
        privacy.setOnClickListener(view -> openExternalUrl(PRIVACY_POLICY_URL));
        Button terms = iconButtonText(R.drawable.ic_receipt, "Terms");
        linksCard.addView(terms, compactButtonParams());
        terms.setOnClickListener(view -> openExternalUrl(TERMS_URL));
        content.addView(linksCard, matchWrap());
    }

    private void showCategories() {
        ScrollView scrollView = page("Storage", this::showCamera);
        attachSwipe(scrollView, null, this::showCamera);
        LinearLayout content = (LinearLayout) scrollView.getChildAt(0);

        LinearLayout tools = row();
        ImageButton filter = toolbarIconButton(R.drawable.ic_filter);
        ImageButton addCategory = toolbarIconButton(R.drawable.ic_add);
        tools.addView(filter, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(0, 1, 1);
        tools.addView(new View(this), spacer);
        tools.addView(addCategory, new LinearLayout.LayoutParams(dp(52), dp(52)));
        content.addView(tools, matchWrap());
        content.addView(chip("Filter: " + activeFilter.label()));
        filter.setOnClickListener(view -> showFilterDialog());
        addCategory.setOnClickListener(view -> showCreateCategoryDialog());

        addMonthlySummaryDashboard(content);

        Button archive = iconButtonText(R.drawable.ic_folder, "Archived receipts");
        content.addView(archive, compactButtonParams());
        archive.setOnClickListener(view -> showArchivedReceipts());

        List<Receipt> unscannedReceipts = receiptStore.getUnscannedReceipts();
        if (!unscannedReceipts.isEmpty()) {
            Button scanAll = iconButtonText(R.drawable.ic_spark, "Scan all unscanned (" + unscannedReceipts.size() + ")");
            content.addView(scanAll, compactButtonParams());
            scanAll.setOnClickListener(view -> confirmScanAllUnscanned());
        }

        LinearLayout searchRow = row();
        EditText search = input("Search receipts");
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setText(storageSearchQuery);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        searchParams.rightMargin = dp(10);
        searchRow.addView(search, searchParams);
        ImageButton searchButton = toolbarIconButton(R.drawable.ic_search);
        searchRow.addView(searchButton, new LinearLayout.LayoutParams(dp(52), dp(52)));
        content.addView(searchRow, matchWrap());

        LinearLayout searchResults = new LinearLayout(this);
        searchResults.setOrientation(LinearLayout.VERTICAL);
        content.addView(searchResults, matchWrap());

        LinearLayout categoryList = new LinearLayout(this);
        categoryList.setOrientation(LinearLayout.VERTICAL);
        content.addView(categoryList, matchWrap());

        int categoryIndex = 0;
        for (Category category : receiptStore.getCategories()) {
            LinearLayout card = categoryCard(category);
            card.setOnClickListener(view -> showCategory(category.id));
            categoryList.addView(card);
            animateListItem(card, categoryIndex++);
        }
        renderStorageSearchResults(storageSearchQuery, searchResults, categoryList);
        Runnable runSearch = () -> {
            storageSearchQuery = search.getText().toString();
            renderStorageSearchResults(storageSearchQuery, searchResults, categoryList);
        };
        searchButton.setOnClickListener(view -> runSearch.run());
        search.setOnEditorActionListener((view, actionId, event) -> {
            boolean keyboardSearch = actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE;
            boolean enterKey = event != null
                    && event.getAction() == KeyEvent.ACTION_UP
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (keyboardSearch || enterKey) {
                runSearch.run();
                return true;
            }
            return false;
        });

    }

    private void showCategory(String categoryId) {
        Category category = receiptStore.getCategory(categoryId);
        if (category == null) {
            showCategories();
            return;
        }

        ScrollView scrollView = page(category.icon + " " + category.name, this::showCategories);
        LinearLayout content = (LinearLayout) scrollView.getChildAt(0);
        content.addView(subtitle("Total spent: " + money(receiptStore.totalForCategory(categoryId, activeFilter))));

        Button editCategory = iconButtonText(R.drawable.ic_edit, "Edit category");
        content.addView(editCategory, compactButtonParams());
        editCategory.setOnClickListener(view -> showEditCategoryDialog(category.id));

        Button deleteCategory = iconButtonText(R.drawable.ic_close, "Delete category");
        content.addView(deleteCategory, compactButtonParams());
        deleteCategory.setOnClickListener(view -> confirmDeleteCategory(category.id));

        List<Receipt> receipts = receiptStore.getReceiptsForCategory(categoryId);
        if (receipts.isEmpty()) {
            content.addView(subtitle("No receipts in this category yet."));
        }

        int receiptIndex = 0;
        for (Receipt receipt : receipts) {
            if (!activeFilter.matches(receipt.date)) {
                continue;
            }
            LinearLayout entry = receiptEntry(receipt);
            entry.setOnClickListener(view -> showReceiptDetail(receipt.id));
            content.addView(entry);
            animateListItem(entry, receiptIndex++);
        }

    }

    private void showArchivedReceipts() {
        ScrollView scrollView = page("Archived receipts", this::showCategories);
        LinearLayout content = (LinearLayout) scrollView.getChildAt(0);

        List<Receipt> archivedReceipts = receiptStore.getArchivedReceipts();
        double total = 0;
        for (Receipt receipt : archivedReceipts) {
            if (activeFilter.matches(receipt.date)) {
                total += receipt.total;
            }
        }
        content.addView(subtitle("Archived total: " + money(total)));
        content.addView(chip("Filter: " + activeFilter.label()));

        if (archivedReceipts.isEmpty()) {
            content.addView(subtitle("No archived receipts."));
            return;
        }

        Map<String, Double> categoryTotals = new HashMap<>();
        int visibleReceipts = 0;
        for (Receipt receipt : archivedReceipts) {
            if (!activeFilter.matches(receipt.date)) {
                continue;
            }
            visibleReceipts++;
            double categoryTotal = categoryTotals.containsKey(receipt.categoryId) ? categoryTotals.get(receipt.categoryId) : 0;
            categoryTotals.put(receipt.categoryId, categoryTotal + receipt.total);
        }
        if (visibleReceipts == 0) {
            content.addView(subtitle("No archived receipts match this filter."));
            return;
        }

        content.addView(label("Archived category totals"));
        List<Map.Entry<String, Double>> rankedCategories = new ArrayList<>(categoryTotals.entrySet());
        Collections.sort(rankedCategories, (left, right) -> Double.compare(right.getValue(), left.getValue()));
        double maxCategoryTotal = rankedCategories.isEmpty() ? 0 : rankedCategories.get(0).getValue();
        for (Map.Entry<String, Double> entry : rankedCategories) {
            Category category = receiptStore.getCategory(entry.getKey());
            String name = category == null ? "Deleted category" : category.icon + " " + category.name;
            content.addView(categorySpendRow(name, entry.getValue(), maxCategoryTotal), matchWrap());
        }

        content.addView(label("Receipts"));
        int archivedReceiptIndex = 0;
        for (Receipt receipt : archivedReceipts) {
            if (!activeFilter.matches(receipt.date)) {
                continue;
            }
            LinearLayout entry = receiptEntry(receipt);
            entry.setOnClickListener(view -> showReceiptDetail(receipt.id, true));
            content.addView(entry);
            animateListItem(entry, archivedReceiptIndex++);
        }
    }

    private void showReceiptDetail(String receiptId) {
        showReceiptDetail(receiptId, false);
    }

    private void showReceiptDetail(String receiptId, boolean fromArchiveView) {
        Receipt receipt = receiptStore.getReceipt(receiptId);
        if (receipt == null) {
            if (fromArchiveView) {
                showArchivedReceipts();
            } else {
                showCategories();
            }
            return;
        }

        Category category = receiptStore.getCategory(receipt.categoryId);
        Runnable backAction = fromArchiveView || receipt.archived
                ? this::showArchivedReceipts
                : () -> showCategory(receipt.categoryId);
        ScrollView scrollView = page(receipt.merchant == null || receipt.merchant.trim().isEmpty() ? "Receipt" : receipt.merchant, backAction);
        LinearLayout content = (LinearLayout) scrollView.getChildAt(0);

        ImageView photo = new ImageView(this);
        loadPhoto(photo, new File(receipt.photoPath), 360, 210, ImageView.ScaleType.CENTER_CROP);
        content.addView(photo, new LinearLayout.LayoutParams(match(), dp(210)));
        photo.setOnClickListener(view -> showFullscreenPhoto(receipt.id));
        installPressFeedback(photo);

        String status = receipt.archived ? " · Archived" : "";
        content.addView(subtitle(receipt.date + " · " + (category == null ? "Uncategorized" : category.name) + status));
        content.addView(chip(aiScanLabel(receipt)));
        content.addView(title(money(receipt.total)));

        Button edit = iconButtonText(R.drawable.ic_edit, "Edit receipt");
        content.addView(edit, compactButtonParams());
        edit.setOnClickListener(view -> showEditReceipt(receipt.id));

        if (!receipt.isAiExtracted()) {
            Button scan = iconButtonText(R.drawable.ic_spark, receipt.isAiUnscanned() ? "Scan missing details" : "Retry AI scan");
            content.addView(scan, compactButtonParams());
            scan.setOnClickListener(view -> scanReceipt(receipt.id, fromArchiveView || receipt.archived));
        }

        Button move = iconButtonText(R.drawable.ic_folder, "Move category");
        content.addView(move, compactButtonParams());
        move.setOnClickListener(view -> showMoveReceiptDialog(receipt.id));

        Button addAnother = iconButtonText(R.drawable.ic_receipt, "Add another");
        content.addView(addAnother, compactButtonParams());
        addAnother.setOnClickListener(view -> showCamera());

        Button archive = iconButtonText(R.drawable.ic_folder, receipt.archived ? "Restore receipt" : "Archive receipt");
        content.addView(archive, compactButtonParams());
        archive.setOnClickListener(view -> {
            boolean archiveReceipt = !receipt.archived;
            receiptStore.setReceiptArchived(receipt.id, archiveReceipt);
            toast(archiveReceipt ? "Receipt archived." : "Receipt restored.");
            if (archiveReceipt) {
                showReceiptDetail(receipt.id, true);
            } else {
                showCategory(receipt.categoryId);
            }
        });

        Button delete = iconButtonText(R.drawable.ic_close, "Delete receipt");
        content.addView(delete, compactButtonParams());
        delete.setOnClickListener(view -> confirmDeleteReceipt(receipt.id, fromArchiveView || receipt.archived));

        content.addView(label("Items"));
        LinearLayout itemTable = card();
        for (ReceiptItem item : receipt.items) {
            LinearLayout row = row();
            row.addView(subtitle(item.name), new LinearLayout.LayoutParams(0, wrap(), 1));
            TextView cost = subtitle(money(item.cost));
            cost.setGravity(Gravity.END);
            row.addView(cost, new LinearLayout.LayoutParams(dp(110), wrap()));
            itemTable.addView(row, matchWrap());
        }
        content.addView(itemTable, matchWrap());

        content.addView(label("Extracted text"));
        LinearLayout rawTextCard = card();
        rawTextCard.addView(subtitle(receipt.rawText.isEmpty() ? "No raw text returned." : receipt.rawText));
        content.addView(rawTextCard, matchWrap());
    }

    private void addStoreCard(LinearLayout content) {
        LinearLayout storeCard = card();
        storeCard.addView(label("AI scan credits"));
        storeCard.addView(subtitle(settingsStore.useOwnOpenAiKey()
                ? "Own OpenAI key mode is on. AI scans use your OpenAI account instead of scan credits."
                : entitlementState.remainingScans + " AI scans remaining. Manual receipt entry is free."));
        Button refresh = iconButtonText(R.drawable.ic_spark, "Refresh credits");
        storeCard.addView(refresh, compactButtonParams());
        refresh.setOnClickListener(view -> refreshEntitlements(true));

        for (AiScanPack pack : AiScanPack.PACKS) {
            Button buy = iconButtonText(
                    R.drawable.ic_wallet,
                    pack.scans + " AI scans - " + billingManager.priceFor(pack)
            );
            buy.setEnabled(billingManager.hasProductDetails(pack));
            if (!billingManager.hasProductDetails(pack)) {
                buy.setAlpha(0.55f);
            }
            storeCard.addView(buy, compactButtonParams());
            buy.setOnClickListener(view -> billingManager.launchPurchase(pack));
        }
        content.addView(storeCard, matchWrap());
    }

    private void confirmDeleteReceipt(String receiptId, boolean fromArchiveView) {
        Receipt receipt = receiptStore.getReceipt(receiptId);
        if (receipt == null) {
            if (fromArchiveView) {
                showArchivedReceipts();
            } else {
                showCategories();
            }
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete receipt?")
                .setMessage("This removes the receipt and its stored photo.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    receiptStore.deleteReceipt(receiptId);
                    toast("Receipt deleted.");
                    if (fromArchiveView) {
                        showArchivedReceipts();
                    } else {
                        showCategory(receipt.categoryId);
                    }
                })
                .show();
    }

    private void confirmDeleteCategory(String categoryId) {
        Category category = receiptStore.getCategory(categoryId);
        if (category == null) {
            showCategories();
            return;
        }
        int receiptCount = receiptStore.getReceiptsForCategory(categoryId, true).size();
        String message = receiptCount == 0
                ? "This removes the category."
                : "This removes the category, " + receiptCount + " receipts, and their stored photos.";
        new AlertDialog.Builder(this)
                .setTitle("Delete category?")
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    receiptStore.deleteCategory(categoryId);
                    toast("Category deleted.");
                    showCategories();
                })
                .show();
    }

    private void showEditReceipt(String receiptId) {
        Receipt receipt = receiptStore.getReceipt(receiptId);
        if (receipt == null) {
            showCategories();
            return;
        }

        ScrollView scrollView = page("Edit receipt", () -> showReceiptDetail(receiptId));
        LinearLayout content = (LinearLayout) scrollView.getChildAt(0);

        LinearLayout basics = card();
        basics.addView(label("Receipt"));

        EditText merchant = input("Merchant");
        merchant.setText(receipt.merchant);
        basics.addView(merchant, matchWrap());

        EditText date = input("Date");
        date.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        date.setText(receipt.date);
        basics.addView(date, matchWrap());

        EditText total = input("Total");
        total.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        total.setText(formatDecimal(receipt.total));
        basics.addView(total, matchWrap());

        List<Category> categories = receiptStore.getCategories();
        int[] selectedCategory = {0};
        for (int index = 0; index < categories.size(); index++) {
            if (categories.get(index).id.equals(receipt.categoryId)) {
                selectedCategory[0] = index;
            }
        }
        Button categoryButton = iconButtonText(R.drawable.ic_folder, categoryLabel(categories, selectedCategory[0]));
        basics.addView(categoryButton, compactButtonParams());
        categoryButton.setOnClickListener(view -> showReceiptCategoryPicker(categories, selectedCategory, categoryButton));
        content.addView(basics, matchWrap());

        LinearLayout itemsCard = card();
        itemsCard.addView(label("Items"));
        LinearLayout itemRows = new LinearLayout(this);
        itemRows.setOrientation(LinearLayout.VERTICAL);
        List<EditItemRow> editRows = new ArrayList<>();
        for (ReceiptItem item : receipt.items) {
            addEditableItemRow(itemRows, editRows, item.name, item.cost);
        }
        itemsCard.addView(itemRows, matchWrap());
        Button addItem = iconButtonText(R.drawable.ic_add, "Add item");
        itemsCard.addView(addItem, compactButtonParams());
        addItem.setOnClickListener(view -> addEditableItemRow(itemRows, editRows, "", 0));
        content.addView(itemsCard, matchWrap());

        LinearLayout textCard = card();
        textCard.addView(label("Extracted text"));
        EditText rawText = input("Raw text");
        rawText.setSingleLine(false);
        rawText.setMinLines(5);
        rawText.setGravity(Gravity.TOP | Gravity.START);
        rawText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        rawText.setText(receipt.rawText);
        textCard.addView(rawText, matchWrap());
        content.addView(textCard, matchWrap());

        Button save = primaryButton("Save changes");
        content.addView(save, matchWrap());
        save.setOnClickListener(view -> {
            String merchantValue = merchant.getText().toString().trim();
            String dateValue = date.getText().toString().trim();
            if (merchantValue.isEmpty()) {
                toast("Merchant cannot be empty.");
                return;
            }
            if (dateValue.isEmpty()) {
                toast("Date cannot be empty.");
                return;
            }

            double totalValue;
            try {
                totalValue = parseAmount(total.getText().toString());
            } catch (NumberFormatException exception) {
                toast("Total must be a valid number.");
                return;
            }

            List<ReceiptItem> updatedItems = new ArrayList<>();
            for (EditItemRow row : editRows) {
                if (row.deleted) {
                    continue;
                }
                String name = row.name.getText().toString().trim();
                String costText = row.cost.getText().toString().trim();
                if (name.isEmpty() && costText.isEmpty()) {
                    continue;
                }
                if (name.isEmpty()) {
                    toast("Item name cannot be empty.");
                    return;
                }
                try {
                    updatedItems.add(new ReceiptItem(name, parseAmount(costText)));
                } catch (NumberFormatException exception) {
                    toast("Item costs must be valid numbers.");
                    return;
                }
            }

            String categoryId = receipt.categoryId;
            if (selectedCategory[0] >= 0 && selectedCategory[0] < categories.size()) {
                categoryId = categories.get(selectedCategory[0]).id;
            }
            Receipt updatedReceipt = new Receipt(
                    receipt.id,
                    categoryId,
                    merchantValue,
                    dateValue,
                    totalValue,
                    receipt.photoPath,
                    updatedItems,
                    rawText.getText().toString(),
                    receipt.createdAt,
                    receipt.archived,
                    receipt.aiScanStatus,
                    receipt.aiScannedAt,
                    receipt.aiScanError
            );
            receiptStore.updateReceipt(updatedReceipt);
            toast("Receipt updated.");
            showReceiptDetail(receiptId);
        });
    }

    private void confirmScanAllUnscanned() {
        List<Receipt> unscannedReceipts = receiptStore.getUnscannedReceipts();
        if (unscannedReceipts.isEmpty()) {
            toast("No unscanned receipts.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Scan all unscanned receipts?")
                .setMessage("This uploads " + unscannedReceipts.size() + " stored receipt photos to "
                        + (settingsStore.useOwnOpenAiKey() ? "OpenAI using your own key" : "the configured AI backend")
                        + " and fills missing details without overwriting existing manual fields.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Scan", (dialog, which) -> scanAllUnscanned())
                .show();
    }

    private void scanAllUnscanned() {
        if (!ensureAiProviderConfigured()) {
            showSettings();
            return;
        }

        List<Receipt> unscannedReceipts = receiptStore.getUnscannedReceipts();
        if (!ensureAiCreditsOrOpenStore(unscannedReceipts.size())) {
            return;
        }
        showLoading("Uploading " + unscannedReceipts.size() + (settingsStore.useOwnOpenAiKey()
                ? " receipts to OpenAI..."
                : " receipts to AI backend..."));
        apiExecutor.execute(() -> {
            int scanned = 0;
            int failed = 0;
            for (Receipt receipt : unscannedReceipts) {
                try {
                    ReceiptExtraction extraction = extractReceiptWithConfiguredAi(new File(receipt.photoPath));
                    receiptStore.updateReceipt(applyExtractionToReceipt(receipt, extraction));
                    scanned++;
                } catch (Exception exception) {
                    markReceiptScanFailed(receipt, exception);
                    failed++;
                }
            }
            int scannedCount = scanned;
            int failedCount = failed;
            runOnUiThread(() -> {
                if (!settingsStore.useOwnOpenAiKey()) {
                    refreshEntitlements(false);
                }
                toast("AI scan complete: " + scannedCount + " updated"
                        + (failedCount > 0 ? ", " + failedCount + " failed" : "") + ".");
                showCategories();
            });
        });
    }

    private void scanReceipt(String receiptId, boolean fromArchiveView) {
        if (!ensureAiProviderConfigured()) {
            showSettings();
            return;
        }

        Receipt receipt = receiptStore.getReceipt(receiptId);
        if (receipt == null) {
            showCategories();
            return;
        }
        if (!ensureAiCreditsOrOpenStore(1)) {
            return;
        }

        showLoading(settingsStore.useOwnOpenAiKey()
                ? "Uploading receipt photo to OpenAI..."
                : "Uploading receipt photo to AI backend...");
        apiExecutor.execute(() -> {
            try {
                ReceiptExtraction extraction = extractReceiptWithConfiguredAi(new File(receipt.photoPath));
                receiptStore.updateReceipt(applyExtractionToReceipt(receipt, extraction));
                runOnUiThread(() -> {
                    if (!settingsStore.useOwnOpenAiKey()) {
                        refreshEntitlements(false);
                    }
                    toast("Receipt scanned.");
                    showReceiptDetail(receiptId, fromArchiveView);
                });
            } catch (Exception exception) {
                markReceiptScanFailed(receipt, exception);
                runOnUiThread(() -> {
                    toast("AI scan failed: " + exception.getMessage());
                    showReceiptDetail(receiptId, fromArchiveView);
                });
            }
        });
    }

    private Receipt applyExtractionToReceipt(Receipt receipt, ReceiptExtraction extraction) {
        String categoryId = receipt.categoryId;
        if (shouldReplaceCategory(receipt)) {
            categoryId = resolveCategoryId(extraction);
        }

        String merchant = shouldReplaceText(receipt.merchant) ? extraction.merchant : receipt.merchant;
        String date = shouldReplaceText(receipt.date) ? extraction.date : receipt.date;
        double total = receipt.total <= 0 ? extraction.total : receipt.total;
        List<ReceiptItem> items = receipt.items == null || receipt.items.isEmpty()
                ? extraction.items
                : receipt.items;
        String rawText = shouldReplaceText(receipt.rawText) ? extraction.rawText : receipt.rawText;

        return new Receipt(
                receipt.id,
                categoryId,
                merchant == null ? "" : merchant,
                date == null ? "" : date,
                total,
                receipt.photoPath,
                items == null ? new ArrayList<>() : new ArrayList<>(items),
                rawText == null ? "" : rawText,
                receipt.createdAt,
                receipt.archived,
                Receipt.AI_SCAN_EXTRACTED,
                System.currentTimeMillis(),
                ""
        );
    }

    private void markReceiptScanFailed(Receipt receipt, Exception exception) {
        Receipt failedReceipt = new Receipt(
                receipt.id,
                receipt.categoryId,
                receipt.merchant,
                receipt.date,
                receipt.total,
                receipt.photoPath,
                receipt.items == null ? new ArrayList<>() : new ArrayList<>(receipt.items),
                receipt.rawText,
                receipt.createdAt,
                receipt.archived,
                Receipt.AI_SCAN_FAILED,
                receipt.aiScannedAt,
                exception.getMessage()
        );
        receiptStore.updateReceipt(failedReceipt);
    }

    private boolean shouldReplaceText(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.isEmpty()
                || "Receipt".equalsIgnoreCase(clean)
                || "Unknown merchant".equalsIgnoreCase(clean);
    }

    private boolean shouldReplaceCategory(Receipt receipt) {
        Category category = receiptStore.getCategory(receipt.categoryId);
        return category == null || "Other".equalsIgnoreCase(category.name);
    }

    private String aiScanLabel(Receipt receipt) {
        if (Receipt.AI_SCAN_EXTRACTED.equals(receipt.aiScanStatus)) {
            return "AI scanned";
        }
        if (Receipt.AI_SCAN_FAILED.equals(receipt.aiScanStatus)) {
            return "AI scan failed";
        }
        return "Manual receipt";
    }

    private void addEditableItemRow(LinearLayout container, List<EditItemRow> editRows, String nameValue, double costValue) {
        LinearLayout itemRow = new LinearLayout(this);
        itemRow.setOrientation(LinearLayout.VERTICAL);
        itemRow.setPadding(0, dp(6), 0, dp(10));

        EditText name = input("Item name");
        name.setText(nameValue);
        itemRow.addView(name, matchWrap());

        LinearLayout costRow = row();
        EditText cost = input("Cost");
        cost.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        cost.setText(formatDecimal(costValue));
        costRow.addView(cost, new LinearLayout.LayoutParams(0, dp(52), 1));

        ImageButton delete = toolbarIconButton(R.drawable.ic_close);
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(52), dp(52));
        deleteParams.leftMargin = dp(8);
        costRow.addView(delete, deleteParams);
        itemRow.addView(costRow, matchWrap());

        EditItemRow editRow = new EditItemRow(name, cost);
        editRows.add(editRow);
        delete.setOnClickListener(view -> {
            editRow.deleted = true;
            container.removeView(itemRow);
        });
        container.addView(itemRow, matchWrap());
    }

    private String categoryLabel(List<Category> categories, int selectedIndex) {
        if (selectedIndex >= 0 && selectedIndex < categories.size()) {
            Category category = categories.get(selectedIndex);
            return category.icon + " " + category.name;
        }
        return "Choose category";
    }

    private void showReceiptCategoryPicker(List<Category> categories, int[] selectedIndex, Button categoryButton) {
        String[] names = new String[categories.size()];
        for (int index = 0; index < categories.size(); index++) {
            names[index] = categories.get(index).icon + " " + categories.get(index).name;
        }
        new AlertDialog.Builder(this)
                .setTitle("Category")
                .setItems(names, (dialog, which) -> {
                    selectedIndex[0] = which;
                    categoryButton.setText(categoryLabel(categories, selectedIndex[0]));
                    dialog.dismiss();
                })
                .show();
    }

    private void addMonthlySummaryDashboard(LinearLayout content) {
        String currentMonth = monthKey(0);
        String previousMonth = monthKey(-1);
        double currentTotal = totalForMonth(currentMonth);
        double previousTotal = totalForMonth(previousMonth);
        double difference = currentTotal - previousTotal;

        LinearLayout summary = card();
        LinearLayout header = row();
        header.setPadding(0, 0, 0, dp(2));
        installPressFeedback(header);

        LinearLayout headerCopy = new LinearLayout(this);
        headerCopy.setOrientation(LinearLayout.VERTICAL);
        TextView heading = label("Monthly summary");
        heading.setPadding(0, 0, 0, dp(4));
        headerCopy.addView(heading);
        TextView range = subtitle(monthLabel(0) + " compared with " + monthLabel(-1));
        range.setTextSize(14);
        range.setPadding(0, 0, 0, 0);
        headerCopy.addView(range);
        header.addView(headerCopy, new LinearLayout.LayoutParams(0, wrap(), 1));

        TextView toggle = subtitle("Show");
        toggle.setTextColor(palette.accent);
        toggle.setTextSize(14);
        toggle.setGravity(Gravity.CENTER);
        toggle.setBackground(rounded(palette.chip, dp(999)));
        toggle.setPadding(dp(14), dp(8), dp(14), dp(8));
        header.addView(toggle, new LinearLayout.LayoutParams(wrap(), wrap()));
        summary.addView(header, matchWrap());

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(View.GONE);
        details.setAlpha(0f);

        LinearLayout totals = row();
        totals.setGravity(Gravity.CENTER_VERTICAL);
        totals.addView(summaryMetric("This month", money(currentTotal)), new LinearLayout.LayoutParams(0, wrap(), 1));
        LinearLayout.LayoutParams previousParams = new LinearLayout.LayoutParams(0, wrap(), 1);
        previousParams.leftMargin = dp(10);
        totals.addView(summaryMetric("Last month", money(previousTotal)), previousParams);
        details.addView(totals, matchWrap());

        String differenceText = difference >= 0 ? "+" + money(difference) : money(difference);
        TextView change = chip("Difference: " + differenceText);
        change.setTextColor(difference > 0 ? palette.accent : palette.muted);
        details.addView(change);

        List<Receipt> monthReceipts = receiptsForMonth(currentMonth);
        List<Receipt> comparisonReceipts = monthReceipts.isEmpty() ? receiptStore.getReceipts() : monthReceipts;
        boolean usingAllTimeFallback = monthReceipts.isEmpty() && !comparisonReceipts.isEmpty();

        addTopCategories(details, comparisonReceipts, usingAllTimeFallback);
        addBiggestPurchases(details, comparisonReceipts, usingAllTimeFallback);
        addRecentReceipts(details);

        if (receiptStore.getReceipts().isEmpty()) {
            details.addView(subtitle("Add a receipt to start seeing monthly totals, category trends, and recent activity."));
        }

        summary.addView(details, matchWrap());
        final boolean[] expanded = {false};
        header.setOnClickListener(view -> {
            expanded[0] = !expanded[0];
            setMonthlySummaryExpanded(details, toggle, expanded[0]);
        });

        content.addView(summary, matchWrap());
    }

    private void setMonthlySummaryExpanded(LinearLayout details, TextView toggle, boolean expanded) {
        toggle.setText(expanded ? "Hide" : "Show");
        toggle.animate()
                .scaleX(0.94f)
                .scaleY(0.94f)
                .setDuration(60)
                .withEndAction(() -> toggle.animate().scaleX(1f).scaleY(1f).setDuration(100).start())
                .start();

        if (expanded) {
            View parent = (View) details.getParent();
            int availableWidth = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            if (availableWidth <= 0) {
                details.post(() -> setMonthlySummaryExpanded(details, toggle, true));
                return;
            }

            details.setVisibility(View.VISIBLE);
            details.setAlpha(0f);
            details.setTranslationY(-dp(6));
            details.measure(
                    View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            );
            int targetHeight = details.getMeasuredHeight();
            ViewGroup.LayoutParams params = details.getLayoutParams();
            params.height = 0;
            details.setLayoutParams(params);

            ValueAnimator animator = ValueAnimator.ofInt(0, targetHeight);
            animator.setDuration(220);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(animation -> {
                params.height = (int) animation.getAnimatedValue();
                details.setLayoutParams(params);
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    details.setLayoutParams(params);
                }
            });
            animator.start();
            details.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            return;
        }

        int startHeight = details.getHeight();
        ViewGroup.LayoutParams params = details.getLayoutParams();
        params.height = startHeight;
        details.setLayoutParams(params);

        ValueAnimator animator = ValueAnimator.ofInt(startHeight, 0);
        animator.setDuration(180);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            params.height = (int) animation.getAnimatedValue();
            details.setLayoutParams(params);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                details.setVisibility(View.GONE);
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                details.setLayoutParams(params);
            }
        });
        animator.start();
        details.animate()
                .alpha(0f)
                .translationY(-dp(6))
                .setDuration(120)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private LinearLayout summaryMetric(String caption, String value) {
        LinearLayout metric = new LinearLayout(this);
        metric.setOrientation(LinearLayout.VERTICAL);
        metric.setPadding(dp(12), dp(10), dp(12), dp(10));
        metric.setBackground(rounded(palette.chip, dp(16)));

        TextView captionView = subtitle(caption);
        captionView.setTextSize(13);
        captionView.setPadding(0, 0, 0, dp(4));
        metric.addView(captionView);

        TextView valueView = title(value);
        valueView.setTextSize(20);
        metric.addView(valueView);
        return metric;
    }

    private void addTopCategories(LinearLayout summary, List<Receipt> receipts, boolean usingAllTimeFallback) {
        summary.addView(label("Top categories"));
        if (receipts.isEmpty()) {
            summary.addView(subtitle("No category spending yet."));
            return;
        }

        Map<String, Double> totals = new HashMap<>();
        for (Receipt receipt : receipts) {
            double current = totals.containsKey(receipt.categoryId) ? totals.get(receipt.categoryId) : 0;
            totals.put(receipt.categoryId, current + receipt.total);
        }

        List<Map.Entry<String, Double>> ranked = new ArrayList<>(totals.entrySet());
        Collections.sort(ranked, (left, right) -> Double.compare(right.getValue(), left.getValue()));
        double max = ranked.isEmpty() ? 0 : ranked.get(0).getValue();
        int shown = Math.min(3, ranked.size());
        for (int index = 0; index < shown; index++) {
            Map.Entry<String, Double> entry = ranked.get(index);
            Category category = receiptStore.getCategory(entry.getKey());
            String name = category == null ? "Uncategorized" : category.icon + " " + category.name;
            summary.addView(categorySpendRow(name, entry.getValue(), max), matchWrap());
        }
        if (usingAllTimeFallback) {
            summary.addView(subtitle("No receipts this month yet, so this uses all-time category totals."));
        }
    }

    private LinearLayout categorySpendRow(String name, double total, double max) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(4), 0, dp(6));

        LinearLayout header = row();
        header.setPadding(0, 0, 0, dp(4));
        TextView nameView = subtitle(name);
        nameView.setTextColor(palette.text);
        header.addView(nameView, new LinearLayout.LayoutParams(0, wrap(), 1));
        TextView totalView = subtitle(money(total));
        totalView.setGravity(Gravity.END);
        header.addView(totalView, new LinearLayout.LayoutParams(dp(116), wrap()));
        container.addView(header, matchWrap());

        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(rounded(palette.iconWell, dp(999)));
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(match(), dp(8));
        container.addView(track, trackParams);

        View fill = new View(this);
        fill.setBackground(rounded(palette.accent, dp(999)));
        int fillWeight = max <= 0 ? 1 : Math.max(1, (int) Math.round((total / max) * 100));
        track.addView(fill, new LinearLayout.LayoutParams(0, dp(8), fillWeight));
        track.addView(new View(this), new LinearLayout.LayoutParams(0, dp(8), Math.max(0, 100 - fillWeight)));
        return container;
    }

    private void addBiggestPurchases(LinearLayout summary, List<Receipt> receipts, boolean usingAllTimeFallback) {
        summary.addView(label("Biggest purchases"));
        if (receipts.isEmpty()) {
            summary.addView(subtitle("No purchases to rank yet."));
            return;
        }

        List<Receipt> ranked = new ArrayList<>(receipts);
        Collections.sort(ranked, (left, right) -> Double.compare(right.total, left.total));
        int shown = Math.min(3, ranked.size());
        for (int index = 0; index < shown; index++) {
            Receipt receipt = ranked.get(index);
            LinearLayout entry = compactReceiptEntry(receipt);
            entry.setOnClickListener(view -> showReceiptDetail(receipt.id));
            summary.addView(entry);
            animateListItem(entry, index);
        }
        if (usingAllTimeFallback) {
            summary.addView(subtitle("No receipts this month yet, so this shows your biggest saved purchases."));
        }
    }

    private void addRecentReceipts(LinearLayout summary) {
        summary.addView(label("Recent receipts"));
        List<Receipt> recent = receiptStore.getReceipts();
        if (recent.isEmpty()) {
            summary.addView(subtitle("Recent receipts will appear here as soon as you save one."));
            return;
        }

        int shown = Math.min(3, recent.size());
        for (int index = 0; index < shown; index++) {
            Receipt receipt = recent.get(index);
            LinearLayout entry = compactReceiptEntry(receipt);
            entry.setOnClickListener(view -> showReceiptDetail(receipt.id));
            summary.addView(entry);
            animateListItem(entry, index);
        }
    }

    private LinearLayout compactReceiptEntry(Receipt receipt) {
        LinearLayout entry = row();
        entry.setPadding(dp(10), dp(8), dp(10), dp(8));
        entry.setBackground(withRipple(roundedStroke(palette.input, dp(14), palette.stroke, 1)));
        installPressFeedback(entry);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(8);
        entry.setLayoutParams(params);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView merchant = title(receipt.merchant == null || receipt.merchant.trim().isEmpty() ? "Receipt" : receipt.merchant);
        merchant.setTextSize(17);
        Category category = receiptStore.getCategory(receipt.categoryId);
        String detail = receipt.date + " · " + (category == null ? "Uncategorized" : category.name);
        TextView meta = subtitle(detail);
        meta.setTextSize(14);
        copy.addView(merchant);
        copy.addView(meta);
        entry.addView(copy, new LinearLayout.LayoutParams(0, wrap(), 1));

        TextView total = title(money(receipt.total));
        total.setGravity(Gravity.END);
        total.setTextSize(17);
        entry.addView(total, new LinearLayout.LayoutParams(dp(116), wrap()));
        return entry;
    }

    private List<Receipt> receiptsForMonth(String yyyyMm) {
        List<Receipt> matches = new ArrayList<>();
        for (Receipt receipt : receiptStore.getReceipts()) {
            if (dateMatchesMonth(receipt.date, yyyyMm)) {
                matches.add(receipt);
            }
        }
        return matches;
    }

    private double totalForMonth(String yyyyMm) {
        double total = 0;
        for (Receipt receipt : receiptStore.getReceipts()) {
            if (dateMatchesMonth(receipt.date, yyyyMm)) {
                total += receipt.total;
            }
        }
        return total;
    }

    private boolean dateMatchesMonth(String date, String yyyyMm) {
        return date != null && date.length() >= 7 && date.startsWith(yyyyMm);
    }

    private String monthKey(int offsetMonths) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MONTH, offsetMonths);
        return new SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(calendar.getTime());
    }

    private String monthLabel(int offsetMonths) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MONTH, offsetMonths);
        return new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(calendar.getTime());
    }

    private LinearLayout receiptEntry(Receipt receipt) {
        LinearLayout entry = row();
        entry.setBackground(withRipple(roundedStroke(palette.panel, dp(16), palette.stroke, 1)));
        installPressFeedback(entry);
        entry.setPadding(dp(10), dp(10), dp(12), dp(10));
        entry.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(14);
        entry.setLayoutParams(params);

        ImageView thumb = new ImageView(this);
        loadPhoto(thumb, new File(receipt.photoPath), 72, 72, ImageView.ScaleType.CENTER_CROP);
        entry.addView(thumb, new LinearLayout.LayoutParams(dp(72), dp(72)));

        TextView date = title(receipt.date);
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(0, wrap(), 1);
        dateParams.leftMargin = dp(14);
        entry.addView(date, dateParams);

        TextView total = title(money(receipt.total));
        total.setGravity(Gravity.END);
        total.setTextSize(18);
        entry.addView(total, new LinearLayout.LayoutParams(dp(120), wrap()));
        return entry;
    }

    private void renderStorageSearchResults(String query, LinearLayout resultsContainer, LinearLayout categoryList) {
        resultsContainer.removeAllViews();
        String trimmedQuery = query == null ? "" : query.trim();
        if (trimmedQuery.isEmpty()) {
            categoryList.setVisibility(View.VISIBLE);
            resultsContainer.setVisibility(View.GONE);
            return;
        }

        categoryList.setVisibility(View.GONE);
        resultsContainer.setVisibility(View.VISIBLE);
        List<Receipt> results = searchReceipts(trimmedQuery);
        resultsContainer.addView(label("Search results"));
        if (results.isEmpty()) {
            resultsContainer.addView(subtitle("No matching receipts."));
            return;
        }

        int resultIndex = 0;
        for (Receipt receipt : results) {
            LinearLayout entry = receiptEntry(receipt);
            entry.setOnClickListener(view -> showReceiptDetail(receipt.id));
            resultsContainer.addView(entry);
            animateListItem(entry, resultIndex++);
        }
    }

    private List<Receipt> searchReceipts(String query) {
        List<Receipt> matches = new ArrayList<>();
        String normalizedQuery = normalizeSearch(query);
        for (Receipt receipt : receiptStore.getReceipts()) {
            if (receiptMatchesSearch(receipt, normalizedQuery)) {
                matches.add(receipt);
            }
        }
        return matches;
    }

    private boolean receiptMatchesSearch(Receipt receipt, String normalizedQuery) {
        if (matchesSearch(receipt.merchant, normalizedQuery)
                || matchesSearch(receipt.date, normalizedQuery)
                || matchesSearch(totalSearchText(receipt.total), normalizedQuery)) {
            return true;
        }

        Category category = receiptStore.getCategory(receipt.categoryId);
        if (category != null && matchesSearch(category.name, normalizedQuery)) {
            return true;
        }

        for (ReceiptItem item : receipt.items) {
            if (matchesSearch(item.name, normalizedQuery)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSearch(String value, String normalizedQuery) {
        return normalizeSearch(value).contains(normalizedQuery);
    }

    private String totalSearchText(double total) {
        String plain = String.format(Locale.US, "%.2f", total);
        return plain + " " + plain.replace('.', ',') + " " + money(total);
    }

    private void showFilterDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        EditText month = input("Month YYYY-MM");
        EditText year = input("Year YYYY");
        EditText start = input("Start YYYY-MM-DD");
        EditText end = input("End YYYY-MM-DD");
        form.addView(month);
        form.addView(year);
        form.addView(start);
        form.addView(end);

        new AlertDialog.Builder(this)
                .setTitle("Filter totals")
                .setView(form)
                .setNegativeButton("All time", (dialog, which) -> {
                    activeFilter = DateFilter.all();
                    showCategories();
                })
                .setPositiveButton("Apply", (dialog, which) -> {
                    if (!month.getText().toString().trim().isEmpty()) {
                        activeFilter = DateFilter.month(month.getText().toString().trim());
                    } else if (!year.getText().toString().trim().isEmpty()) {
                        activeFilter = DateFilter.year(year.getText().toString().trim());
                    } else {
                        activeFilter = DateFilter.custom(start.getText().toString().trim(), end.getText().toString().trim());
                    }
                    showCategories();
                })
                .show();
    }

    private void showCreateCategoryDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        EditText input = input("Category name");
        Button icon = secondaryButton("🧾");
        final String[] selectedIcon = {"🧾"};
        form.addView(input);
        form.addView(icon);
        icon.setOnClickListener(view -> showIconPicker(selectedIcon[0], pickedIcon -> {
            selectedIcon[0] = pickedIcon;
            icon.setText(pickedIcon);
        }));
        new AlertDialog.Builder(this)
                .setTitle("New category")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (dialog, which) -> {
                    if (!input.getText().toString().trim().isEmpty()) {
                        receiptStore.addCategory(input.getText().toString().trim(), selectedIcon[0]);
                        showCategories();
                    }
                })
                .show();
    }

    private void showEditCategoryDialog(String categoryId) {
        Category category = receiptStore.getCategory(categoryId);
        if (category == null) {
            return;
        }

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        EditText input = input("Category name");
        input.setText(category.name);
        Button icon = secondaryButton(category.icon);
        final String[] selectedIcon = {category.icon};
        form.addView(input);
        form.addView(icon);
        icon.setOnClickListener(view -> showIconPicker(selectedIcon[0], pickedIcon -> {
            selectedIcon[0] = pickedIcon;
            icon.setText(pickedIcon);
        }));

        new AlertDialog.Builder(this)
                .setTitle("Edit category")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    receiptStore.updateCategory(categoryId, input.getText().toString(), selectedIcon[0]);
                    showCategory(categoryId);
                })
                .show();
    }

    private void showIconPicker(String currentIcon, IconCallback callback) {
        int checked = 0;
        for (int index = 0; index < CATEGORY_ICONS.length; index++) {
            if (CATEGORY_ICONS[index].equals(currentIcon)) {
                checked = index;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose icon")
                .setSingleChoiceItems(CATEGORY_ICONS, checked, (dialog, which) -> {
                    callback.onIconPicked(CATEGORY_ICONS[which]);
                    dialog.dismiss();
                })
                .show();
    }

    private void showMoveReceiptDialog(String receiptId) {
        Receipt receipt = receiptStore.getReceipt(receiptId);
        if (receipt == null) {
            return;
        }
        String originalCategoryId = receipt.categoryId;
        List<Category> categories = receiptStore.getCategories();
        String[] names = new String[categories.size()];
        for (int index = 0; index < categories.size(); index++) {
            names[index] = categories.get(index).icon + " " + categories.get(index).name;
        }
        new AlertDialog.Builder(this)
                .setTitle("Move receipt")
                .setItems(names, (dialog, which) -> {
                    String newCategoryId = categories.get(which).id;
                    receiptStore.moveReceipt(receiptId, newCategoryId);
                    if (!originalCategoryId.equals(newCategoryId)
                            && receiptStore.deleteEmptyAiSuggestedCategory(originalCategoryId)) {
                        toast("Removed empty AI-suggested category.");
                    }
                    showReceiptDetail(receiptId);
                })
                .show();
    }

    private void showFullscreenPhoto(String receiptId) {
        Receipt receipt = receiptStore.getReceipt(receiptId);
        if (receipt == null) {
            return;
        }

        fullscreenReceiptId = receiptId;
        currentBackAction = () -> {
            fullscreenReceiptId = null;
            showReceiptDetail(receiptId);
        };
        FrameLayout photoRoot = new FrameLayout(this);
        photoRoot.setBackgroundColor(Color.BLACK);
        transitionRootView(photoRoot, Color.BLACK);

        ImageView photo = new ImageView(this);
        loadPhoto(photo, new File(receipt.photoPath), 420, 760, ImageView.ScaleType.MATRIX);
        photoRoot.addView(photo, matchParent());
        installZoomablePhoto(photo);

        ImageButton close = floatingIconButton(R.drawable.ic_close);
        FrameLayout.LayoutParams closeParams = cornerButtonParams(Gravity.TOP | Gravity.END);
        photoRoot.addView(close, closeParams);
        close.setOnClickListener(view -> {
            fullscreenReceiptId = null;
            showReceiptDetail(receiptId);
        });
    }

    private void loadPhoto(ImageView imageView, File photoFile, int fallbackWidthDp, int fallbackHeightDp, ImageView.ScaleType scaleType) {
        imageView.setScaleType(scaleType);
        imageView.setBackgroundColor(palette.dark ? 0xFF0B0F0C : palette.iconWell);
        imageView.clearColorFilter();
        imageView.setImageDrawable(null);
        if (photoFile == null || !photoFile.exists() || !photoFile.isFile()) {
            return;
        }

        String photoId = photoFile.getAbsolutePath() + ":" + photoFile.lastModified();
        imageView.setTag(photoId);
        imageView.post(() -> {
            int targetWidth = imageView.getWidth() > 0 ? imageView.getWidth() : dp(fallbackWidthDp);
            int targetHeight = imageView.getHeight() > 0 ? imageView.getHeight() : dp(fallbackHeightDp);
            String cacheKey = photoId + ":" + Math.max(1, targetWidth) + "x" + Math.max(1, targetHeight);
            imageView.setTag(cacheKey);

            Bitmap cached = imageCache.get(cacheKey);
            if (cached != null) {
                setPhotoBitmap(imageView, cached, false);
                return;
            }

            imageExecutor.execute(() -> {
                Bitmap decoded = decodeSampledPhoto(photoFile, targetWidth, targetHeight);
                if (decoded != null) {
                    imageCache.put(cacheKey, decoded);
                }
                runOnUiThread(() -> {
                    if (!cacheKey.equals(imageView.getTag()) || decoded == null) {
                        return;
                    }
                    setPhotoBitmap(imageView, decoded, true);
                });
            });
        });
    }

    private void setPhotoBitmap(ImageView imageView, Bitmap bitmap, boolean fadeIn) {
        imageView.clearColorFilter();
        if (fadeIn) {
            imageView.setAlpha(0f);
        }
        imageView.setImageBitmap(bitmap);
        if (imageView.getScaleType() == ImageView.ScaleType.MATRIX) {
            imageView.post(() -> fitPhotoToView(imageView));
        }
        imageView.animate().cancel();
        imageView.animate()
                .alpha(1f)
                .setDuration(fadeIn ? 150 : 0)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void installZoomablePhoto(ImageView photo) {
        final float minScale = 1f;
        final float maxScale = 4f;
        final float[] userScale = {minScale};
        final float[] lastX = {0f};
        final float[] lastY = {0f};
        final boolean[] moved = {false};

        ScaleGestureDetector scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float targetScale = Math.max(minScale, Math.min(maxScale, userScale[0] * detector.getScaleFactor()));
                float factor = targetScale / userScale[0];
                if (Math.abs(factor - 1f) < 0.001f) {
                    return true;
                }

                Matrix matrix = new Matrix(photo.getImageMatrix());
                matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                userScale[0] = targetScale;
                applyBoundedPhotoMatrix(photo, matrix);
                return true;
            }
        });

        GestureDetector gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent event) {
                if (userScale[0] > 1.05f) {
                    userScale[0] = minScale;
                    fitPhotoToView(photo);
                    return true;
                }

                float targetScale = 2.25f;
                Matrix matrix = new Matrix(photo.getImageMatrix());
                matrix.postScale(targetScale, targetScale, event.getX(), event.getY());
                userScale[0] = targetScale;
                applyBoundedPhotoMatrix(photo, matrix);
                return true;
            }
        });

        photo.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (userScale[0] == minScale && photo.getDrawable() != null) {
                fitPhotoToView(photo);
            }
        });

        photo.setOnTouchListener((view, event) -> {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);

            if (event.getPointerCount() > 1) {
                moved[0] = true;
                return true;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastX[0] = event.getX();
                lastY[0] = event.getY();
                moved[0] = false;
                return true;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && userScale[0] > minScale) {
                float dx = event.getX() - lastX[0];
                float dy = event.getY() - lastY[0];
                if (Math.abs(dx) > 1f || Math.abs(dy) > 1f) {
                    Matrix matrix = new Matrix(photo.getImageMatrix());
                    matrix.postTranslate(dx, dy);
                    applyBoundedPhotoMatrix(photo, matrix);
                    moved[0] = true;
                }
                lastX[0] = event.getX();
                lastY[0] = event.getY();
                return true;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (userScale[0] <= minScale && moved[0]) {
                    fitPhotoToView(photo);
                }
                return true;
            }

            return userScale[0] > minScale || scaleDetector.isInProgress();
        });
    }

    private void fitPhotoToView(ImageView imageView) {
        Drawable drawable = imageView.getDrawable();
        if (drawable == null || imageView.getWidth() <= 0 || imageView.getHeight() <= 0) {
            return;
        }

        int drawableWidth = drawable.getIntrinsicWidth();
        int drawableHeight = drawable.getIntrinsicHeight();
        if (drawableWidth <= 0 || drawableHeight <= 0) {
            return;
        }

        RectF drawableBounds = new RectF(0, 0, drawableWidth, drawableHeight);
        RectF viewBounds = new RectF(0, 0, imageView.getWidth(), imageView.getHeight());
        Matrix matrix = new Matrix();
        matrix.setRectToRect(drawableBounds, viewBounds, Matrix.ScaleToFit.CENTER);
        imageView.setImageMatrix(matrix);
    }

    private void applyBoundedPhotoMatrix(ImageView imageView, Matrix matrix) {
        RectF bounds = photoBounds(imageView, matrix);
        if (bounds == null) {
            return;
        }

        float dx = 0f;
        float dy = 0f;
        float viewWidth = imageView.getWidth();
        float viewHeight = imageView.getHeight();

        if (bounds.width() <= viewWidth) {
            dx = (viewWidth - bounds.width()) / 2f - bounds.left;
        } else if (bounds.left > 0) {
            dx = -bounds.left;
        } else if (bounds.right < viewWidth) {
            dx = viewWidth - bounds.right;
        }

        if (bounds.height() <= viewHeight) {
            dy = (viewHeight - bounds.height()) / 2f - bounds.top;
        } else if (bounds.top > 0) {
            dy = -bounds.top;
        } else if (bounds.bottom < viewHeight) {
            dy = viewHeight - bounds.bottom;
        }

        matrix.postTranslate(dx, dy);
        imageView.setImageMatrix(matrix);
    }

    private RectF photoBounds(ImageView imageView, Matrix matrix) {
        Drawable drawable = imageView.getDrawable();
        if (drawable == null) {
            return null;
        }

        int drawableWidth = drawable.getIntrinsicWidth();
        int drawableHeight = drawable.getIntrinsicHeight();
        if (drawableWidth <= 0 || drawableHeight <= 0) {
            return null;
        }

        RectF bounds = new RectF(0, 0, drawableWidth, drawableHeight);
        matrix.mapRect(bounds);
        return bounds;
    }

    private Bitmap decodeSampledPhoto(File photoFile, int targetWidth, int targetHeight) {
        int safeWidth = Math.max(dp(64), targetWidth);
        int safeHeight = Math.max(dp(64), targetHeight);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(photoFile.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, safeWidth, safeHeight);
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;
        Bitmap bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath(), options);
        return bitmap == null ? null : rotatePhotoIfNeeded(photoFile, bitmap);
    }

    private int sampleSize(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        int sampleSize = 1;
        int halfWidth = sourceWidth / 2;
        int halfHeight = sourceHeight / 2;
        while ((halfWidth / sampleSize) >= targetWidth && (halfHeight / sampleSize) >= targetHeight) {
            sampleSize *= 2;
        }
        return Math.max(1, sampleSize);
    }

    private Bitmap rotatePhotoIfNeeded(File photoFile, Bitmap bitmap) {
        try {
            ExifInterface exif = new ExifInterface(photoFile.getAbsolutePath());
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int degrees = 0;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                degrees = 90;
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
                degrees = 180;
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                degrees = 270;
            }
            if (degrees == 0) {
                return bitmap;
            }

            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) {
                bitmap.recycle();
            }
            return rotated;
        } catch (Exception ignored) {
            return bitmap;
        }
    }

    private void showLoading(String message) {
        currentBackAction = null;
        settingsVisible = false;
        LinearLayout loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER);
        loading.setBackgroundColor(palette.surface);
        ProgressBar progress = new ProgressBar(this);
        TextView text = subtitle(message);
        text.setGravity(Gravity.CENTER);
        loading.addView(progress);
        loading.addView(text);
        transitionRootView(loading, palette.surface);
    }

    private boolean ensureAiProviderConfigured() {
        if (settingsStore.useOwnOpenAiKey()) {
            if (settingsStore.getOpenAiApiKey().trim().isEmpty()) {
                toast("Add your OpenAI API key in settings first, or turn off own-key mode.");
                return false;
            }
            return true;
        }
        if (settingsStore.getBackendUrl().trim().isEmpty()) {
            toast("Add your AI backend URL in settings first.");
            return false;
        }
        return true;
    }

    private ReceiptExtraction extractReceiptWithConfiguredAi(File imageFile) throws Exception {
        if (settingsStore.useOwnOpenAiKey()) {
            return openAiReceiptExtractor.extract(
                    imageFile,
                    receiptStore.getCategories(),
                    settingsStore.getOpenAiApiKey(),
                    settingsStore.getOpenAiModel(),
                    settingsStore.allowAiNewCategories()
            );
        }
        return receiptExtractor.extract(
                imageFile,
                receiptStore.getCategories(),
                settingsStore.getBackendUrl(),
                settingsStore.allowAiNewCategories(),
                settingsStore.getInstallationId()
        );
    }

    private boolean ensureAiCreditsOrOpenStore(int scansNeeded) {
        if (settingsStore.useOwnOpenAiKey()) {
            return true;
        }
        if (entitlementState.remainingScans >= scansNeeded) {
            return true;
        }
        toast(scansNeeded == 1
                ? "Buy AI scan credits to scan this receipt. Manual entry is still free."
                : "Buy more AI scan credits to scan these receipts. Manual entry is still free.");
        showSettings();
        refreshEntitlements(true);
        return false;
    }

    private void refreshEntitlements(boolean showResult) {
        String backendUrl = settingsStore.getBackendUrl();
        if (backendUrl == null || backendUrl.trim().isEmpty()) {
            return;
        }
        apiExecutor.execute(() -> {
            try {
                EntitlementState refreshed = receiptExtractor.getEntitlements(
                        backendUrl,
                        settingsStore.getInstallationId()
                );
                runOnUiThread(() -> {
                    entitlementState = refreshed;
                    if (showResult) {
                        toast(refreshed.remainingScans + " AI scans remaining.");
                        showSettings();
                    }
                });
            } catch (Exception exception) {
                if (showResult) {
                    runOnUiThread(() -> toast("Could not refresh credits: " + exception.getMessage()));
                }
            }
        });
    }

    @Override
    public void onProductsUpdated() {
        if (settingsVisible) {
            runOnUiThread(this::showSettings);
        }
    }

    @Override
    public void onPurchaseReadyForVerification(Purchase purchase) {
        if (purchase == null || verifyingPurchaseTokens.contains(purchase.getPurchaseToken())) {
            return;
        }
        String productId = null;
        for (String candidate : purchase.getProducts()) {
            if (AiScanPack.find(candidate) != null) {
                productId = candidate;
                break;
            }
        }
        if (productId == null) {
            return;
        }

        verifyingPurchaseTokens.add(purchase.getPurchaseToken());
        showLoading("Verifying purchase...");
        String backendUrl = settingsStore.getBackendUrl();
        String verifiedProductId = productId;
        apiExecutor.execute(() -> {
            try {
                PurchaseVerification verification = receiptExtractor.verifyPurchase(
                        backendUrl,
                        settingsStore.getInstallationId(),
                        verifiedProductId,
                        purchase.getPurchaseToken()
                );
                runOnUiThread(() -> {
                    verifyingPurchaseTokens.remove(purchase.getPurchaseToken());
                    entitlementState = verification.entitlementState;
                    if (verification.ok) {
                        String message = verification.alreadyGranted
                                ? "Purchase already added. "
                                : "Added " + verification.grantedScans + " AI scans. ";
                        toast(message + entitlementState.remainingScans + " remaining.");
                    } else {
                        toast("Purchase could not be verified.");
                    }
                    showSettings();
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    verifyingPurchaseTokens.remove(purchase.getPurchaseToken());
                    toast("Purchase verification failed: " + exception.getMessage());
                    showSettings();
                });
            }
        });
    }

    @Override
    public void onBillingUnavailable(String message) {
        runOnUiThread(() -> toast(message == null || message.trim().isEmpty()
                ? "Google Play billing is unavailable."
                : message));
    }

    private ScrollView page(String titleText, Runnable backAction) {
        currentBackAction = backAction;
        fullscreenReceiptId = null;
        settingsVisible = "Settings".equals(titleText);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(palette.surface);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(34));
        scrollView.addView(content, matchWrap());
        content.addView(topBar(titleText, backAction), matchWrap());
        transitionRootView(scrollView, palette.surface);
        return scrollView;
    }

    private LinearLayout topBar(String titleText, Runnable backAction) {
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(4), 0, dp(16));
        if (backAction != null) {
            ImageButton back = topBarIconButton(R.drawable.ic_back);
            bar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));
            back.setOnClickListener(view -> backAction.run());
        }
        TextView title = title(titleText);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, wrap(), 1);
        if (backAction != null) {
            titleParams.leftMargin = dp(8);
        }
        bar.addView(title, titleParams);
        return bar;
    }

    private LinearLayout cameraHeader() {
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER);
        header.setPadding(dp(14), 0, dp(16), 0);
        header.setBackground(rounded(0xAA000000, dp(999)));

        ImageView receipt = new ImageView(this);
        receipt.setImageResource(R.drawable.ic_receipt);
        receipt.setColorFilter(Color.WHITE);
        header.addView(receipt, new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView title = new TextView(this);
        title.setText("Receiptory");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(wrap(), wrap());
        titleParams.leftMargin = dp(8);
        header.addView(title, titleParams);
        return header;
    }

    private LinearLayout categoryCard(Category category) {
        LinearLayout card = row();
        card.setBackground(withRipple(roundedStroke(palette.panel, dp(18), palette.stroke, 1)));
        installPressFeedback(card);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(14);
        card.setLayoutParams(params);

        TextView icon = new TextView(this);
        icon.setText(category.icon);
        icon.setTextSize(24);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(palette.iconWell, dp(14)));
        card.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, wrap(), 1);
        copyParams.leftMargin = dp(14);
        TextView name = title(category.name);
        name.setTextSize(19);
        TextView total = subtitle(money(receiptStore.totalForCategory(category.id, activeFilter)));
        total.setText("Total spent: " + total.getText());
        copy.addView(name);
        copy.addView(total);
        card.addView(copy, copyParams);

        ImageView receiptIcon = new ImageView(this);
        receiptIcon.setImageResource(R.drawable.ic_receipt);
        receiptIcon.setColorFilter(palette.muted);
        card.addView(receiptIcon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        return card;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(roundedStroke(palette.panel, dp(18), palette.stroke, 1));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(12);
        card.setLayoutParams(params);
        return card;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        return row;
    }

    private TextView title(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(palette.text);
        view.setTextSize(22);
        view.setGravity(Gravity.START);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView subtitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(palette.muted);
        view.setTextSize(16);
        view.setPadding(0, dp(6), 0, dp(6));
        view.setLineSpacing(dp(2), 1.0f);
        return view;
    }

    private TextView label(String text) {
        TextView view = subtitle(text);
        view.setTextColor(palette.text);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(10), 0, dp(8));
        return view;
    }

    private TextView chip(String text) {
        TextView chip = subtitle(text);
        chip.setTextColor(palette.accent);
        chip.setTextSize(14);
        chip.setBackground(rounded(palette.chip, dp(999)));
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(8);
        params.bottomMargin = dp(4);
        chip.setLayoutParams(params);
        return chip;
    }

    private EditText input(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(palette.text);
        input.setHintTextColor(palette.muted);
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setBackground(roundedStroke(palette.input, dp(14), palette.stroke, 1));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(8);
        params.bottomMargin = dp(8);
        input.setLayoutParams(params);
        return input;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(palette.buttonText);
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(withRipple(rounded(palette.accent, dp(16))));
        installPressFeedback(button);
        button.setMinHeight(dp(52));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(16);
        button.setLayoutParams(params);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(palette.text);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setBackground(withRipple(roundedStroke(palette.panel, dp(16), palette.stroke, 1)));
        installPressFeedback(button);
        button.setMinHeight(dp(52));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(10);
        button.setLayoutParams(params);
        return button;
    }

    private Button iconButtonText(int iconRes, String text) {
        Button button = secondaryButton(text);
        button.setPadding(dp(18), 0, dp(18), 0);
        Drawable icon = ContextCompat.getDrawable(this, iconRes);
        if (icon != null) {
            icon.setTint(palette.accent);
            button.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
            button.setCompoundDrawablePadding(dp(12));
        }
        return button;
    }

    private ImageButton floatingIconButton(int iconRes) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconRes);
        button.setColorFilter(Color.WHITE);
        button.setPadding(dp(15), dp(15), dp(15), dp(15));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setBackground(withRipple(rounded(0x99000000, dp(18))));
        installPressFeedback(button);
        return button;
    }

    private ImageButton toolbarIconButton(int iconRes) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconRes);
        button.setColorFilter(palette.accent);
        button.setPadding(dp(14), dp(14), dp(14), dp(14));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setBackground(withRipple(roundedStroke(palette.panel, dp(16), palette.stroke, 1)));
        installPressFeedback(button);
        return button;
    }

    private ImageButton topBarIconButton(int iconRes) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconRes);
        button.setColorFilter(palette.text);
        button.setPadding(dp(14), dp(14), dp(14), dp(14));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setBackground(withRipple(rounded(palette.chip, dp(16))));
        installPressFeedback(button);
        return button;
    }

    private void addThemeOption(RadioGroup group, String theme, String label) {
        android.widget.RadioButton option = new android.widget.RadioButton(this);
        option.setText(label);
        option.setTag(theme);
        option.setTextColor(palette.text);
        option.setId(View.generateViewId());
        group.addView(option);
        if (settingsStore.getTheme().equals(theme)) {
            option.setChecked(true);
        }
    }

    private String money(double amount) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.getDefault());
        try {
            format.setCurrency(Currency.getInstance(settingsStore.getCurrencyCode()));
        } catch (IllegalArgumentException ignored) {
        }
        return format.format(amount);
    }

    private String formatDecimal(double amount) {
        if (amount == (long) amount) {
            return String.valueOf((long) amount);
        }
        return String.valueOf(amount);
    }

    private double parseAmount(String value) {
        String normalized = value == null ? "" : value.trim().replace(',', '.');
        if (normalized.isEmpty()) {
            return 0;
        }
        return Double.parseDouble(normalized);
    }

    private static String normalizeSearch(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value.trim(), Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private void attachSwipe(View view, Runnable swipeLeftAction, Runnable swipeRightAction) {
        final float[] downX = {0};
        final float[] downY = {0};
        view.setOnTouchListener((touchedView, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                downX[0] = event.getX();
                downY[0] = event.getY();
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float deltaX = event.getX() - downX[0];
                float deltaY = event.getY() - downY[0];
                if (Math.abs(deltaX) > dp(90) && Math.abs(deltaX) > Math.abs(deltaY) * 1.4f) {
                    if (deltaX < 0 && swipeLeftAction != null) {
                        swipeLeftAction.run();
                        return true;
                    }
                    if (deltaX > 0 && swipeRightAction != null) {
                        swipeRightAction.run();
                        return true;
                    }
                }
            }
            return false;
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message == null ? "Something went wrong." : message, Toast.LENGTH_LONG).show();
    }

    private void openExternalUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception exception) {
            toast("Could not open link: " + exception.getMessage());
        }
    }

    private void applySystemBars() {
        getWindow().setStatusBarColor(palette.surface);
        getWindow().setNavigationBarColor(palette.surface);
        int flags = 0;
        if (!palette.dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private Drawable withRipple(Drawable content) {
        int rippleColor = Color.argb(
                palette.dark ? 80 : 54,
                Color.red(palette.accent),
                Color.green(palette.accent),
                Color.blue(palette.accent)
        );
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    private void installPressFeedback(View view) {
        view.setClickable(true);
        view.setOnTouchListener((pressedView, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && pressedView.isEnabled()) {
                pressedView.animate().cancel();
                pressedView.animate()
                        .scaleX(0.97f)
                        .scaleY(0.97f)
                        .setDuration(55)
                        .start();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                pressedView.animate().cancel();
                pressedView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(115)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            }
            return false;
        });
    }

    private void transitionRootView(View view, int backgroundColor) {
        root.setBackgroundColor(backgroundColor);
        List<View> oldViews = new ArrayList<>();
        for (int index = 0; index < root.getChildCount(); index++) {
            oldViews.add(root.getChildAt(index));
        }

        view.setAlpha(0f);
        view.setTranslationY(dp(6));
        root.addView(view, matchParent());

        for (View oldView : oldViews) {
            oldView.animate().cancel();
            oldView.setClickable(false);
            oldView.animate()
                    .alpha(0f)
                    .setDuration(120)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> root.removeView(oldView))
                    .start();
        }

        view.animate().cancel();
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(oldViews.isEmpty() ? 120 : 170)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void animateListItem(View view, int index) {
        view.setAlpha(0f);
        view.setTranslationY(dp(8));
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(Math.min(index * 18L, 126L))
                .setDuration(150)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable roundedStroke(int color, int radius, int strokeColor, int strokeDp) {
        GradientDrawable drawable = rounded(color, radius);
        drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showCamera();
        } else {
            toast("Camera permission is needed to photograph receipts.");
        }
    }

    private void requestCameraPermissionWithContext() {
        new AlertDialog.Builder(this)
                .setTitle("Camera access")
                .setMessage("Receiptory uses the camera only to photograph receipts. Saved photos stay on this device unless you choose an AI scan, which uploads the receipt photo to your configured backend.")
                .setNegativeButton("Not now", (dialog, which) ->
                        toast("Camera permission is needed to photograph receipts."))
                .setPositiveButton("Continue", (dialog, which) ->
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST))
                .show();
    }

    private FrameLayout.LayoutParams cornerButtonParams(int gravity) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(58), dp(58), gravity);
        params.topMargin = dp(28);
        params.leftMargin = dp(18);
        params.rightMargin = dp(18);
        return params;
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(54), 1);
        params.leftMargin = dp(6);
        params.rightMargin = dp(6);
        return params;
    }

    private LinearLayout.LayoutParams compactButtonParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(12);
        params.bottomMargin = dp(4);
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(match(), wrap());
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(match(), match());
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int match() {
        return ViewGroup.LayoutParams.MATCH_PARENT;
    }

    private int wrap() {
        return ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    private static class Palette {
        final int surface;
        final int panel;
        final int text;
        final int muted;
        final int accent;
        final int buttonText;
        final int chip;
        final int iconWell;
        final int input;
        final int stroke;
        final int shutter;
        final int shutterRing;
        final boolean dark;

        Palette(
                int surface,
                int panel,
                int text,
                int muted,
                int accent,
                int buttonText,
                int chip,
                int iconWell,
                int input,
                int stroke,
                int shutter,
                int shutterRing,
                boolean dark
        ) {
            this.surface = surface;
            this.panel = panel;
            this.text = text;
            this.muted = muted;
            this.accent = accent;
            this.buttonText = buttonText;
            this.chip = chip;
            this.iconWell = iconWell;
            this.input = input;
            this.stroke = stroke;
            this.shutter = shutter;
            this.shutterRing = shutterRing;
            this.dark = dark;
        }

        static Palette from(MainActivity activity, String mode) {
            boolean dark = SettingsStore.THEME_DARK.equals(mode);
            if (SettingsStore.THEME_SYSTEM.equals(mode)) {
                int uiMode = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                dark = uiMode == Configuration.UI_MODE_NIGHT_YES;
            }
            if (dark) {
                return new Palette(
                        0xFF101411,
                        0xFF1B221D,
                        0xFFF6F2E9,
                        0xFFB7C2B9,
                        0xFF7AC7A4,
                        0xFF07120C,
                        0xFF24332B,
                        0xFF26392F,
                        0xFF141A16,
                        0xFF314038,
                        0xFFF8F3E8,
                        0xFF7AC7A4,
                        true
                );
            }
            return new Palette(
                    0xFFF4F0E7,
                    0xFFFFFCF6,
                    0xFF18231D,
                    0xFF647268,
                    0xFF24745A,
                    0xFFFFFFFF,
                    0xFFE4EFE8,
                    0xFFEAF1E9,
                    0xFFFFFFFF,
                    0xFFE1DACE,
                    0xFFFFFBF1,
                    0xFF24745A,
                    false
            );
        }
    }

    private interface IconCallback {
        void onIconPicked(String icon);
    }

    private static class EditItemRow {
        final EditText name;
        final EditText cost;
        boolean deleted;

        EditItemRow(EditText name, EditText cost) {
            this.name = name;
            this.cost = cost;
        }
    }
}
