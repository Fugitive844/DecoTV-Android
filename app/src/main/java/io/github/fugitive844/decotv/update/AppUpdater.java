package io.github.fugitive844.decotv.update;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import io.github.fugitive844.decotv.BuildConfig;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 通过 Zealot 自升级，参照 QwenPaw：独立的 HTTPS 客户端、只跟随同域名跳转，
 * 下载后校验大小、SHA-256、包名、版本与签名，再交给系统安装器由用户确认。
 */
public final class AppUpdater {
    private static final String TAG = "AppUpdater";
    /** 每个进程只在启动时自动检查一次。 */
    private static final AtomicBoolean startupChecked = new AtomicBoolean();
    private static volatile UpdateRelease requiredUpdate;

    private final AppCompatActivity activity;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES)
            .followRedirects(false).followSslRedirects(false).build();
    private final File apk;
    private volatile Call call;
    private volatile boolean closed, busy, cancelled;
    private AlertDialog dialog;
    private boolean awaitingPermission;
    private UpdateRelease pendingRelease;

    public AppUpdater(AppCompatActivity activity) {
        this.activity = activity;
        prefs = activity.getSharedPreferences("app_updates", 0);
        File directory = new File(activity.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) Log.w(TAG, "无法创建更新目录");
        apk = new File(directory, "update.apk");
        awaitingPermission = prefs.getBoolean("awaiting_install_permission", false);
    }

    public static boolean configured() { return !BuildConfig.UPDATE_CHANNEL.isEmpty() && !BuildConfig.UPDATE_ORIGIN.isEmpty(); }

    private PackageInfo installed() throws Exception { return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0); }

    static long code(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }

    private String version() {
        try {
            return installed().versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private boolean alive() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }

    private boolean resumed() { return activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED); }

    private void ui(Runnable action) { activity.runOnUiThread(() -> { if (alive()) action.run(); }); }

    private void toast(String text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }

    /** manual=false 为启动时自动检查，每个进程一次；manual=true 为设置页手动检查。 */
    public void check(boolean manual) {
        if (!alive()) return;
        if (!resumed()) {
            // 在 onResume 回调里调用时生命周期还没进入 RESUMED，等界面就绪后再检查
            activity.getWindow().getDecorView().post(() -> { if (alive() && resumed()) check(manual); });
            return;
        }
        if (busy) {
            if (manual) toast("正在检查或下载更新，请稍候");
            return;
        }
        if (!configured()) {
            if (manual) toast("此版本未配置升级服务");
            return;
        }
        if (!manual) {
            if (requiredUpdate != null) {
                showUpdate(requiredUpdate, version(), false);
                return;
            }
            if (!startupChecked.compareAndSet(false, true)) return;
        }
        busy = true;
        cancelled = false;
        if (manual) toast("正在检查新版本…");
        io.execute(() -> {
            try {
                PackageInfo local = installed();
                HttpUrl url = HttpUrl.get(BuildConfig.UPDATE_ORIGIN + "/api/apps/latest").newBuilder()
                        .addQueryParameter("channel_key", BuildConfig.UPDATE_CHANNEL)
                        .addQueryParameter("bundle_id", activity.getPackageName())
                        .addQueryParameter("release_version", local.versionName)
                        .addQueryParameter("build_version", String.valueOf(code(local))).build();
                UpdateRelease release;
                try (Response response = get(url.toString())) {
                    if (!response.isSuccessful() || response.body() == null) throw new IOException("更新服务暂时不可用，请稍后重试");
                    ByteArrayOutputStream content = new ByteArrayOutputStream();
                    byte[] bytes = new byte[8192];
                    int n;
                    try (InputStream in = response.body().byteStream()) {
                        while ((n = in.read(bytes)) != -1) {
                            if (content.size() + n > 1024 * 1024) throw new IOException("更新信息过大");
                            content.write(bytes, 0, n);
                        }
                    }
                    release = UpdateRelease.latest(new JSONObject(content.toString("UTF-8")), BuildConfig.UPDATE_ORIGIN,
                            activity.getPackageName(), code(local));
                }
                if (release != null && release.mode == UpdateRelease.Mode.REQUIRED) {
                    requiredUpdate = release;
                    cacheRequired(release);
                } else {
                    requiredUpdate = null;
                    clearRequired();
                }
                Log.i(TAG, "update check done, latest=" + (release == null ? "none" : release.name + "/" + release.code));
                ui(() -> {
                    if (!resumed()) return;
                    if (release == null) {
                        if (manual) toast("当前已是最新版本 " + local.versionName);
                        return;
                    }
                    showUpdate(release, local.versionName, manual);
                });
            } catch (Exception e) {
                Log.w(TAG, "update check failed: " + e.getMessage());
                UpdateRelease cached = cachedRequired();
                if (cached != null) {
                    requiredUpdate = cached;
                    ui(() -> showUpdate(cached, version(), false));
                } else if (manual) {
                    ui(() -> toast(message(e, "检查更新失败，请检查网络后重试")));
                }
            } finally {
                busy = false;
            }
        });
    }

    private void showUpdate(UpdateRelease release, String localVersion, boolean manual) {
        if (!alive() || (dialog != null && dialog.isShowing())) return;
        String title = release.mode == UpdateRelease.Mode.REQUIRED ? "必须升级到 " + release.name
                : release.mode == UpdateRelease.Mode.RECOMMENDED ? "强烈建议升级到 " + release.name : "发现新版本 " + release.name;
        String policy = release.policyMessage;
        if (policy.isEmpty() && release.mode == UpdateRelease.Mode.RECOMMENDED) policy = "服务器接口已经更新，继续使用旧版本可能导致部分功能不可用。";
        if (policy.isEmpty() && release.mode == UpdateRelease.Mode.REQUIRED) policy = "当前版本已停止支持，必须升级后才能继续使用。";
        String message = "当前版本 " + localVersion + "\n安装包 " + String.format(Locale.ROOT, "%.1f MB", release.size / 1048576.0)
                + (policy.isEmpty() ? "" : "\n\n" + policy) + "\n\n本次更新：\n" + release.notes.trim() + "\n\n下载完成后由系统提示确认安装，保留登录与设置。";
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(message)
                .setPositiveButton(release.mode == UpdateRelease.Mode.NORMAL ? "下载更新" : "立即升级", (d, w) -> download(release));
        if (release.mode == UpdateRelease.Mode.REQUIRED) builder.setNegativeButton("退出应用", (d, w) -> activity.finishAffinity()).setCancelable(false);
        else if (release.mode == UpdateRelease.Mode.RECOMMENDED) builder.setNegativeButton("暂不升级", null).setCancelable(false);
        else builder.setNegativeButton("稍后", null);
        dialog = builder.show();
    }

    private Response get(String initial) throws IOException {
        String url = initial;
        for (int i = 0; i < 6; i++) {
            if (cancelled || closed) throw new IOException("已取消");
            if (!UpdateRelease.safeUrl(url, BuildConfig.UPDATE_ORIGIN)) throw new IOException("更新下载地址不受信任");
            call = http.newCall(new Request.Builder().url(url).header("Accept", "*/*").build());
            Response response = call.execute();
            if (!response.isRedirect()) return response;
            String location = response.header("Location");
            HttpUrl next = location == null ? null : response.request().url().resolve(location);
            response.close();
            if (next == null) throw new IOException("下载链接无效");
            url = next.toString();
        }
        throw new IOException("下载重定向次数过多");
    }

    private void cacheRequired(UpdateRelease release) {
        prefs.edit().putLong("required_code", release.code).putLong("required_size", release.size)
                .putString("required_name", release.name).putString("required_url", release.url)
                .putString("required_sha256", release.sha256).putString("required_notes", release.notes)
                .putString("required_message", release.policyMessage).apply();
    }

    private UpdateRelease cachedRequired() {
        long requiredCode = prefs.getLong("required_code", 0);
        try {
            if (requiredCode <= code(installed())) {
                clearRequired();
                return null;
            }
            String url = prefs.getString("required_url", ""), hash = prefs.getString("required_sha256", "");
            long size = prefs.getLong("required_size", 0);
            if (!UpdateRelease.safeUrl(url, BuildConfig.UPDATE_ORIGIN) || !hash.matches("(?i)[a-f0-9]{64}") || size <= 0 || size > UpdateRelease.MAX_APK_BYTES) {
                clearRequired();
                return null;
            }
            return new UpdateRelease(requiredCode, size, prefs.getString("required_name", String.valueOf(requiredCode)), url, hash,
                    prefs.getString("required_notes", ""), UpdateRelease.Mode.REQUIRED, prefs.getString("required_message", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private void clearRequired() {
        prefs.edit().remove("required_code").remove("required_size").remove("required_name").remove("required_url")
                .remove("required_sha256").remove("required_notes").remove("required_message").apply();
    }

    private void download(UpdateRelease release) {
        if (busy) return;
        busy = true;
        cancelled = false;
        pendingRelease = release;
        ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity).setTitle("正在下载 " + release.name).setView(progress)
                .setMessage("下载完成后会校验安装包并调起系统安装。").setCancelable(false);
        if (release.mode == UpdateRelease.Mode.REQUIRED) builder.setNegativeButton("退出应用", (d, w) -> {
            cancelled = true;
            if (call != null) call.cancel();
            activity.finishAffinity();
        });
        else builder.setNegativeButton("取消", (d, w) -> {
            cancelled = true;
            if (call != null) call.cancel();
        });
        dialog = builder.show();
        io.execute(() -> {
            File partial = new File(apk.getParentFile(), "update.part");
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long total = 0;
                int last = -1;
                try (Response response = get(release.url)) {
                    if (!response.isSuccessful() || response.body() == null) throw new IOException("安装包下载失败，请重试");
                    if (response.body().contentLength() > UpdateRelease.MAX_APK_BYTES) throw new IOException("安装包过大");
                    try (InputStream in = response.body().byteStream(); OutputStream out = new FileOutputStream(partial)) {
                        byte[] bytes = new byte[32768];
                        int n;
                        while ((n = in.read(bytes)) != -1) {
                            if (closed || cancelled) throw new IOException("已取消");
                            total += n;
                            if (total > release.size || total > UpdateRelease.MAX_APK_BYTES) throw new IOException("安装包大小与发布信息不一致");
                            out.write(bytes, 0, n);
                            digest.update(bytes, 0, n);
                            int percent = (int) (total * 100 / release.size);
                            if (percent != last) {
                                last = percent;
                                ui(() -> progress.setProgress(percent));
                            }
                        }
                    }
                }
                if (total != release.size || !hex(digest.digest()).equalsIgnoreCase(release.sha256)) throw new IOException("安装包校验失败，请重新下载");
                validatePackage(partial, release.code);
                if (apk.exists() && !apk.delete()) throw new IOException("无法替换旧下载文件");
                if (!partial.renameTo(apk)) throw new IOException("无法保存安装包");
                prefs.edit().putLong("pending_code", release.code).putString("pending_sha256", release.sha256)
                        .putBoolean("pending_required", release.mode == UpdateRelease.Mode.REQUIRED).apply();
                Log.i(TAG, "update downloaded and verified: " + release.name);
                ui(() -> {
                    if (dialog != null) dialog.dismiss();
                    offerInstall();
                });
            } catch (Exception e) {
                Log.w(TAG, "Update download or verification failed", e);
                ui(() -> {
                    if (dialog != null) dialog.dismiss();
                    if (!cancelled) showFailure(release, e);
                });
            } finally {
                if (partial.exists() && !partial.delete()) Log.w(TAG, "无法删除临时文件");
                busy = false;
            }
        });
    }

    private void showFailure(UpdateRelease release, Exception error) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity).setTitle("更新未完成")
                .setMessage(message(error, "下载失败，请检查网络后重试"));
        if (release.mode == UpdateRelease.Mode.REQUIRED) builder.setNegativeButton("退出应用", (d, w) -> activity.finishAffinity())
                .setPositiveButton("重试", (d, w) -> download(release)).setCancelable(false);
        else builder.setPositiveButton("知道了", null);
        dialog = builder.show();
    }

    private void offerInstall() {
        boolean required = prefs.getBoolean("pending_required", false);
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity).setTitle("允许安装更新")
                    .setMessage("请在系统设置中允许 DecoTV 安装应用，返回后会打开安装确认页。").setPositiveButton("前往设置", (d, w) -> {
                        awaitingPermission = true;
                        prefs.edit().putBoolean("awaiting_install_permission", true).apply();
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
                        } catch (Exception e) {
                            awaitingPermission = false;
                            prefs.edit().remove("awaiting_install_permission").apply();
                            toast("请在系统设置中手动允许安装应用");
                        }
                    });
            if (required) builder.setNegativeButton("退出应用", (d, w) -> activity.finishAffinity()).setCancelable(false);
            else builder.setNegativeButton("稍后", null);
            dialog = builder.show();
        } else {
            install();
        }
    }

    /** 在 Activity 的 onResume 中调用：从「允许安装」设置页返回后继续安装。 */
    public void resume() {
        boolean required = prefs.getBoolean("pending_required", false);
        if (awaitingPermission) {
            awaitingPermission = false;
            prefs.edit().remove("awaiting_install_permission").apply();
            if (activity.getPackageManager().canRequestPackageInstalls()) install();
            else if (required) offerInstall();
            else toast("尚未允许安装更新，可稍后重新检查更新");
            return;
        }
        if (required && apk.exists()) {
            try {
                if (prefs.getLong("pending_code", 0) > code(installed()) && activity.getPackageManager().canRequestPackageInstalls()) install();
            } catch (Exception ignored) {
                // 下次启动再检查
            }
        }
    }

    private void install() {
        if (closed) return;
        io.execute(() -> {
            try {
                String expected = prefs.getString("pending_sha256", "");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = new FileInputStream(apk)) {
                    byte[] bytes = new byte[32768];
                    int n;
                    while ((n = in.read(bytes)) != -1) digest.update(bytes, 0, n);
                }
                if (expected.isEmpty() || !expected.equalsIgnoreCase(hex(digest.digest()))) throw new IOException("下载文件已变化，请重新下载");
                validatePackage(apk, prefs.getLong("pending_code", 0));
                ui(() -> {
                    try {
                        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", apk);
                        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        activity.startActivity(intent);
                        Log.i(TAG, "system installer opened");
                    } catch (Exception e) {
                        toast("无法打开系统安装器，请稍后重试");
                    }
                });
            } catch (Exception e) {
                ui(() -> {
                    UpdateRelease required = pendingRelease != null ? pendingRelease : cachedRequired();
                    if (prefs.getBoolean("pending_required", false) && required != null) showFailure(required, e);
                    else toast(message(e, "安装包无法验证，请重新下载"));
                });
            }
        });
    }

    private void validatePackage(File file, long expectedCode) throws Exception {
        // Android 9/10 解析安装包时，只有同时带 GET_SIGNATURES 才会读出证书
        PackageManager pm = activity.getPackageManager();
        int flags = PackageManager.GET_SIGNATURES;
        if (Build.VERSION.SDK_INT >= 28) flags |= PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags), local = pm.getPackageInfo(activity.getPackageName(), flags);
        if (archive == null || !activity.getPackageName().equals(archive.packageName) || code(archive) != expectedCode || expectedCode <= code(local))
            throw new IOException("安装包名称或版本不匹配");
        if (!signatures(archive).equals(signatures(local))) throw new IOException("安装包签名不匹配，已停止安装");
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signatures(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null ? info.signingInfo.getApkContentsSigners() : info.signatures;
        if (signatures == null || signatures.length == 0) throw new IOException("无法读取安装包签名，请重新下载或联系发布者");
        Set<String> result = new HashSet<>();
        for (Signature signature : signatures) result.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        return result;
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    private static String message(Exception e, String fallback) {
        return e instanceof IOException && e.getMessage() != null && !e.getMessage().contains("http") ? e.getMessage() : fallback;
    }

    public void close() {
        closed = true;
        cancelled = true;
        if (call != null) call.cancel();
        if (dialog != null) dialog.dismiss();
        io.shutdownNow();
    }
}
