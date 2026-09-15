package ir.golesahra.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * اکتیویتی اصلی اپ گل صحرا: یک WebView تمام‌صفحه که سایت golesahra.ir را نمایش می‌دهد.
 * قابلیت‌ها: کش/DOM Storage، آپلود فایل از گالری، هندل کردن لینک‌های تلفن/ایمیل/پیامک و
 * schemeهای اختصاصی درگاه‌های بانکی، مدیریت دکمه بازگشت، و صفحه‌ی خطا در قطعی اینترنت.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "GolesahraApp";
    private static final String SITE_URL = "https://golesahra.ir";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final long BACK_EXIT_INTERVAL_MS = 2000;

    private WebView webView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ProgressBar progressBar;
    private View errorLayout;
    private TextView errorText;
    private Button retryButton;

    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;

    private long lastBackPressTime = 0;
    private Toast exitToast;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        progressBar = findViewById(R.id.progressBar);
        errorLayout = findViewById(R.id.errorLayout);
        errorText = findViewById(R.id.errorText);
        retryButton = findViewById(R.id.retryButton);

        requestNeededPermissions();
        setupFileChooserLauncher();
        setupWebView();
        setupSwipeRefresh();

        retryButton.setOnClickListener(v -> reloadSite());

        if (savedInstanceState == null) {
            webView.loadUrl(SITE_URL);
        }
    }

    // ---------------------------------------------------------------------
    // دسترسی‌ها (دوربین/حافظه) — روی اندروید ۶ به بالا باید در زمان اجرا درخواست بشن
    // ---------------------------------------------------------------------
    private void requestNeededPermissions() {
        List<String> needed = new ArrayList<>();
        String[] permissions;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // از اندروید ۱۰ به بعد نوشتن روی حافظه با Scoped Storage انجام می‌شه و
            // WRITE_EXTERNAL_STORAGE عملاً لازم نیست؛ فقط دوربین رو درخواست می‌کنیم.
            permissions = new String[]{Manifest.permission.CAMERA};
        } else {
            permissions = new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
        }

        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                needed.add(permission);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    // ---------------------------------------------------------------------
    // تنظیمات WebView: جاوااسکریپت، DOM Storage (که LocalStorage رو فعال می‌کنه)، و کش
    // ---------------------------------------------------------------------
    @SuppressWarnings("deprecation")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // یه User-Agent اختصاصی اضافه می‌کنیم تا اگه لازم شد سمت سرور بشه اپ رو تشخیص داد
        settings.setUserAgentString(settings.getUserAgentString() + " GolesahraApp/1.0");

        webView.setWebViewClient(new GolesahraWebViewClient());
        webView.setWebChromeClient(new GolesahraWebChromeClient());
    }

    private void setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener(this::reloadSite);
    }

    private void reloadSite() {
        errorLayout.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.reload();
    }

    // ---------------------------------------------------------------------
    // WebViewClient: کنترل ناوبری، لودینگ، خطا و لینک‌های خارج از وب (تلفن/ایمیل/درگاه بانکی)
    // ---------------------------------------------------------------------
    private class GolesahraWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUri(request.getUrl());
        }

        // برای سازگاری با WebViewهای قدیمی‌تر (API زیر ۲۴) که متد بالا رو صدا نمی‌زنن
        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUri(Uri.parse(url));
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            progressBar.setVisibility(View.GONE);
            swipeRefreshLayout.setRefreshing(false);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request.isForMainFrame()) {
                showErrorPage();
            }
        }

        // برای سازگاری با WebViewهای قدیمی‌تر
        @Override
        @SuppressWarnings("deprecation")
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            super.onReceivedError(view, errorCode, description, failingUrl);
            showErrorPage();
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            super.onReceivedHttpError(view, request, errorResponse);
            // خطای HTTP (مثل ۴۰۴ روی یه زیرمسیر) لزوماً یعنی قطعی اینترنت نیست،
            // پس فقط برای خطاهای شبکه‌ای واقعی (onReceivedError) صفحه‌ی خطا رو نشون می‌دیم.
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            // گواهی نامعتبر SSL رو هرگز به‌طور خودکار قبول نمی‌کنیم — برای صفحات پرداخت حیاتیه.
            Log.e(TAG, "SSL error: " + error.toString());
            handler.cancel();
            showErrorPage();
        }
    }

    /**
     * لینک‌هایی که باید خارج از WebView مدیریت بشن: شماره تلفن، ایمیل، پیامک، و مهم‌تر از همه
     * schemeهای اختصاصی اپ‌های بانکی/درگاه پرداخت (زرین‌پال و بقیه) که موقع پرداخت باز می‌شن.
     * @return true اگه خودمون هندلش کردیم (WebView نباید باز کنه)، false اگه باید تو WebView باز بشه.
     */
    private boolean handleUri(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) return false;

        // آدرس‌های عادی وب (http/https) — همون داخل WebView باز بشن، خودمون کاری نمی‌کنیم
        if (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) {
            return false;
        }

        if (scheme.equalsIgnoreCase("tel")) {
            return openExternal(new Intent(Intent.ACTION_DIAL, uri));
        }
        if (scheme.equalsIgnoreCase("mailto")) {
            return openExternal(new Intent(Intent.ACTION_SENDTO, uri));
        }
        if (scheme.equalsIgnoreCase("sms") || scheme.equalsIgnoreCase("smsto")) {
            return openExternal(new Intent(Intent.ACTION_SENDTO, uri));
        }

        // مسیرهایی که با intent:// شروع می‌شن (بعضی درگاه‌ها/اپ‌های بانکی این‌طوری لینک می‌دن)
        if (scheme.equalsIgnoreCase("intent")) {
            try {
                Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                } else {
                    openInBazaar(intent.getPackage());
                }
            } catch (Exception e) {
                Log.e(TAG, "intent scheme parse error", e);
            }
            return true;
        }

        // هر scheme اختصاصی دیگه (zarinpal://, بانک ملی، سامان، ملت، پارسیان، شاپرک و ...) —
        // اگه اپ مربوطه نصب باشه باز می‌شه، وگرنه کاربر رو به کافه‌بازار (برای نصبش) می‌فرستیم.
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                openInBazaar(uri.getHost());
                Toast.makeText(this, "برنامه‌ی مربوط به این درگاه پرداخت روی گوشی شما نصب نیست", Toast.LENGTH_LONG).show();
            }
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "برنامه‌ی مربوطه روی گوشی شما نصب نیست", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private boolean openExternal(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "برنامه‌ی مناسب برای این کار پیدا نشد", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    /** اگه بسته‌ی اپ بانکی/پرداخت روی گوشی نصب نبود، صفحه‌ش رو تو کافه‌بازار باز می‌کنیم. */
    private void openInBazaar(@Nullable String packageName) {
        if (packageName == null) return;
        try {
            Intent marketIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("bazaar://details?id=" + packageName));
            marketIntent.setPackage("com.farsitel.bazaar");
            startActivity(marketIntent);
        } catch (ActivityNotFoundException ignored) {
            // اگه کافه‌بازار هم نصب نبود، دیگه کاری نمی‌کنیم؛ کاربر خودش می‌دونه باید چیکار کنه
        }
    }

    // ---------------------------------------------------------------------
    // WebChromeClient: نوار پیشرفت لودینگ + انتخاب فایل از گالری (input type="file")
    // ---------------------------------------------------------------------
    private class GolesahraWebChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            progressBar.setProgress(newProgress);
            progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public boolean onShowFileChooser(WebView webViewParam, ValueCallback<Uri[]> callback, FileChooserParams fileChooserParams) {
            // اگه یه انتخاب قبلی هنوز باز بود، لغوش می‌کنیم که WebView قفل نمونه
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
                filePathCallback = null;
            }
            filePathCallback = callback;

            Intent intent;
            try {
                // این متد خودش بر اساس accept="..." تو HTML، اینتنت مناسب (عکس/فایل عمومی) می‌سازه
                intent = fileChooserParams.createIntent();
            } catch (Exception e) {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
            }

            try {
                fileChooserLauncher.launch(intent);
            } catch (ActivityNotFoundException e) {
                filePathCallback = null;
                Toast.makeText(MainActivity.this, "امکان باز کردن گالری وجود ندارد", Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }
    }

    private void setupFileChooserLauncher() {
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                this::onFileChooserResult
        );
    }

    private void onFileChooserResult(ActivityResult result) {
        if (filePathCallback == null) return;

        Uri[] resultUris = null;
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            Intent data = result.getData();
            if (data.getClipData() != null) {
                // چند فایل انتخاب شده
                int count = data.getClipData().getItemCount();
                resultUris = new Uri[count];
                for (int i = 0; i < count; i++) {
                    resultUris[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                // یک فایل انتخاب شده
                resultUris = new Uri[]{data.getData()};
            }
        }

        filePathCallback.onReceiveValue(resultUris);
        filePathCallback = null;
    }

    // ---------------------------------------------------------------------
    // صفحه‌ی خطا (قطعی اینترنت / عدم دسترسی به سرور)
    // ---------------------------------------------------------------------
    private void showErrorPage() {
        webView.setVisibility(View.GONE);
        errorLayout.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        swipeRefreshLayout.setRefreshing(false);
    }

    // ---------------------------------------------------------------------
    // دکمه‌ی فیزیکی بازگشت: اول تو تاریخچه‌ی خودِ سایت برگرد؛ اگه چیزی نمونده، با دو بار کلیک خارج شو
    // ---------------------------------------------------------------------
    @Override
    public void onBackPressed() {
        if (errorLayout.getVisibility() == View.VISIBLE) {
            super.onBackPressed();
            return;
        }
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastBackPressTime < BACK_EXIT_INTERVAL_MS) {
            if (exitToast != null) exitToast.cancel();
            super.onBackPressed();
            return;
        }
        lastBackPressTime = now;
        exitToast = Toast.makeText(this, "برای خروج از برنامه، دوباره دکمه بازگشت را بزنید", Toast.LENGTH_SHORT);
        exitToast.show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // نتیجه رو خاص هندل نمی‌کنیم؛ اگه کاربر رد کنه، خودِ سایت (مثلاً موقع باز کردن دوربین)
        // دوباره درخواست می‌ده و سیستم‌عامل دیالوگ رو نشون می‌ده.
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            swipeRefreshLayout.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
