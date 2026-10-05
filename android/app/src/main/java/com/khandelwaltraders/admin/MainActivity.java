package com.khandelwaltraders.admin;

import android.Manifest;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.getcapacitor.BridgeActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends BridgeActivity {

    private static final int REQ_INSTALL_PERMISSION = 1002;
    private static final int REQ_LOCATION = 1001;
    private static final int REQ_CAMERA = 1003;
    private long downloadId = -1;
    private BroadcastReceiver onDownloadComplete;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Live Firebase app: keep WebView fresh.
        try {
            WebView mainWebView = this.bridge.getWebView();
            if (mainWebView != null) {
                mainWebView.clearCache(true);
                WebSettings webSettings = mainWebView.getSettings();
                webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
                webSettings.setJavaScriptEnabled(true);
                webSettings.setDomStorageEnabled(true);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // APK download completion -> installer.
        onDownloadComplete = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    launchApkInstaller();
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                onDownloadComplete,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            );
        } else {
            registerReceiver(
                onDownloadComplete,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            );
        }

        // JS -> Android bridge.
        this.bridge.getWebView().addJavascriptInterface(new Object() {

            // Download and install latest Admin APK.
            @JavascriptInterface
            public void downloadAndInstallApk(String apkUrl) {
                runOnUiThread(() -> {
                    try {
                        File downloadFolder =
                            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                        if (downloadFolder == null) {
                            throw new Exception("Download folder unavailable");
                        }

                        File targetFile =
                            new File(downloadFolder, "KTAdmin_Update.apk");

                        if (targetFile.exists()) {
                            targetFile.delete();
                        }

                        Toast.makeText(
                            MainActivity.this,
                            "Downloading Admin update...",
                            Toast.LENGTH_SHORT
                        ).show();

                        DownloadManager.Request request =
                            new DownloadManager.Request(Uri.parse(apkUrl));

                        request.setTitle("KT Admin App Update");
                        request.setDescription("Downloading latest version...");
                        request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        );
                        request.setDestinationInExternalFilesDir(
                            MainActivity.this,
                            Environment.DIRECTORY_DOWNLOADS,
                            "KTAdmin_Update.apk"
                        );
                        request.setMimeType(
                            "application/vnd.android.package-archive"
                        );

                        DownloadManager manager =
                            (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

                        if (manager != null) {
                            downloadId = manager.enqueue(request);
                        }
                    } catch (Exception e) {
                        Toast.makeText(
                            MainActivity.this,
                            "Download error: " + e.getMessage(),
                            Toast.LENGTH_LONG
                        ).show();
                    }
                });
            }

            // Location permission for admin web app.
            @JavascriptInterface
            public void askLocationPermission() {
                runOnUiThread(() -> {
                    if (ContextCompat.checkSelfPermission(
                            MainActivity.this,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED) {

                        ActivityCompat.requestPermissions(
                            MainActivity.this,
                            new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            },
                            REQ_LOCATION
                        );
                    } else {
                        bridge.getWebView()
                            .evaluateJavascript(
                                "if(typeof fetchExactGPS==='function'){fetchExactGPS();}",
                                null
                            );
                    }
                });
            }

            // Camera permission.
            @JavascriptInterface
            public void askCameraPermission() {
                runOnUiThread(() -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        ContextCompat.checkSelfPermission(
                            MainActivity.this,
                            Manifest.permission.CAMERA
                        ) != PackageManager.PERMISSION_GRANTED) {

                        ActivityCompat.requestPermissions(
                            MainActivity.this,
                            new String[]{Manifest.permission.CAMERA},
                            REQ_CAMERA
                        );
                    } else {
                        bridge.getWebView()
                            .evaluateJavascript(
                                "if(typeof onCameraPermissionGranted==='function'){onCameraPermissionGranted();}",
                                null
                            );
                    }
                });
            }

            // Open Android app settings.
            @JavascriptInterface
            public void openAppSettings() {
                Intent intent =
                    new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                Uri uri =
                    Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivity(intent);
            }

            // Save a base64 image/receipt into the Downloads folder.
            @JavascriptInterface
            public void saveReceiptToGallery(
                    String base64Data,
                    String fileName
            ) {
                runOnUiThread(() -> {
                    try {
                        String cleanBase64 = base64Data;
                        if (cleanBase64.contains(",")) {
                            cleanBase64 = cleanBase64.split(",")[1];
                        }

                        byte[] decodedBytes =
                            Base64.decode(cleanBase64, Base64.DEFAULT);

                        Bitmap bitmap =
                            BitmapFactory.decodeByteArray(
                                decodedBytes,
                                0,
                                decodedBytes.length
                            );

                        if (bitmap == null) {
                            throw new Exception("Invalid image data");
                        }

                        String finalName =
                            fileName + "_" +
                            System.currentTimeMillis() + ".jpg";

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            ContentValues values = new ContentValues();
                            values.put(
                                MediaStore.Downloads.DISPLAY_NAME,
                                finalName
                            );
                            values.put(
                                MediaStore.Downloads.MIME_TYPE,
                                "image/jpeg"
                            );
                            values.put(
                                MediaStore.Downloads.RELATIVE_PATH,
                                Environment.DIRECTORY_DOWNLOADS
                            );

                            Uri uri = getContentResolver().insert(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                values
                            );

                            if (uri == null) {
                                throw new Exception("Unable to create download file");
                            }

                            OutputStream fos =
                                getContentResolver().openOutputStream(uri);

                            if (fos == null) {
                                throw new Exception("Unable to open output stream");
                            }

                            bitmap.compress(
                                Bitmap.CompressFormat.JPEG,
                                100,
                                fos
                            );
                            fos.close();

                        } else {
                            File downloadDir =
                                Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOWNLOADS
                                );

                            if (!downloadDir.exists()) {
                                downloadDir.mkdirs();
                            }

                            File imageFile =
                                new File(downloadDir, finalName);

                            FileOutputStream fos =
                                new FileOutputStream(imageFile);

                            bitmap.compress(
                                Bitmap.CompressFormat.JPEG,
                                100,
                                fos
                            );
                            fos.close();
                        }

                        Toast.makeText(
                            MainActivity.this,
                            "Saved to Download Folder!",
                            Toast.LENGTH_SHORT
                        ).show();

                        bridge.getWebView().evaluateJavascript(
                            "if(typeof showToast==='function'){showToast('Saved to Downloads!');}",
                            null
                        );

                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(
                            MainActivity.this,
                            "Save Failed: " + e.getMessage(),
                            Toast.LENGTH_LONG
                        ).show();
                    }
                });
            }

            // Base64 APK installer fallback.
            @JavascriptInterface
            public void installDownloadedApk(String base64ApkData) {
                runOnUiThread(() -> {
                    try {
                        String cleanBase64 = base64ApkData;
                        if (cleanBase64.contains(",")) {
                            cleanBase64 = cleanBase64.split(",")[1];
                        }

                        byte[] apkBytes =
                            Base64.decode(cleanBase64, Base64.DEFAULT);

                        File apkFile =
                            new File(getCacheDir(), "KTAdmin_Update.apk");

                        if (apkFile.exists()) {
                            apkFile.delete();
                        }

                        FileOutputStream fos =
                            new FileOutputStream(apkFile);
                        fos.write(apkBytes);
                        fos.flush();
                        fos.close();

                        launchFileProviderInstall(apkFile);

                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(
                            MainActivity.this,
                            "Installer Error: " + e.getMessage(),
                            Toast.LENGTH_LONG
                        ).show();
                    }
                });
            }

        }, "AndroidLocationBridge");

        this.bridge.getWebView().setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin,
                    GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        cleanupOldDownloadedApk();
    }

    private void cleanupOldDownloadedApk() {
        try {
            File downloadFolder =
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);

            if (downloadFolder != null && downloadFolder.exists()) {
                File targetFile =
                    new File(downloadFolder, "KTAdmin_Update.apk");
                if (targetFile.exists()) {
                    targetFile.delete();
                }
            }

            File cacheApk =
                new File(getCacheDir(), "KTAdmin_Update.apk");
            if (cacheApk.exists()) {
                cacheApk.delete();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void launchApkInstaller() {
        try {
            File targetFile =
                new File(
                    getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "KTAdmin_Update.apk"
                );

            if (targetFile.exists()) {
                launchFileProviderInstall(targetFile);
            }

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(
                this,
                "Installer error: " + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void launchFileProviderInstall(File apkFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(
                    this,
                    "Please allow installation from this source",
                    Toast.LENGTH_LONG
                ).show();

                Intent intent =
                    new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
                    );

                intent.setData(
                    Uri.parse("package:" + getPackageName())
                );

                startActivityForResult(
                    intent,
                    REQ_INSTALL_PERMISSION
                );
                return;
            }
        }

        Uri apkUri;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            apkUri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                apkFile
            );
        } else {
            apkUri = Uri.fromFile(apkFile);
        }

        Intent installIntent =
            new Intent(Intent.ACTION_VIEW);

        installIntent.setDataAndType(
            apkUri,
            "application/vnd.android.package-archive"
        );

        installIntent.setFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK |
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        );

        startActivity(installIntent);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        );

        if (requestCode == REQ_LOCATION) {
            if (grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                this.bridge.getWebView().evaluateJavascript(
                    "if(typeof fetchExactGPS==='function'){fetchExactGPS();}",
                    null
                );
            } else {
                this.bridge.getWebView().evaluateJavascript(
                    "if(typeof onPermissionDeniedBySystem==='function'){onPermissionDeniedBySystem();}",
                    null
                );
            }
        }

        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                this.bridge.getWebView().evaluateJavascript(
                    "if(typeof onCameraPermissionGranted==='function'){onCameraPermissionGranted();}",
                    null
                );
            } else {
                this.bridge.getWebView().evaluateJavascript(
                    "if(typeof onCameraPermissionDenied==='function'){onCameraPermissionDenied();}",
                    null
                );
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (onDownloadComplete != null) {
            try {
                unregisterReceiver(onDownloadComplete);
            } catch (Exception ignored) {}
        }
    }
}
