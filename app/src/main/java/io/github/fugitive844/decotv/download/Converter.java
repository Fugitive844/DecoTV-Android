package io.github.fugitive844.decotv.download;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.InAppMuxer;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 转成标准 MP4：音视频编码 MP4 支持时只换封装（很快，画质不变）；
 * 不支持的编码（如 MPEG-2、AC-3）由 Media3 Transformer 自动转码为 H.264 / AAC（较慢、耗电）。
 */
@OptIn(markerClass = UnstableApi.class)
final class Converter {
    interface Progress { void percent(int p); }

    private Converter() {}

    /** 在后台线程调用，阻塞直到完成。 */
    static void toMp4(Context context, File in, File out, Progress progress, java.util.function.BooleanSupplier cancelled) throws IOException {
        Handler main = new Handler(Looper.getMainLooper());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<Transformer> ref = new AtomicReference<>();
        main.post(() -> {
            // 用 Media3 自带的封装器：系统 MediaMuxer 在部分机型上遇到不规范的 TS 会在原生层直接崩溃整个进程
            Transformer t = new Transformer.Builder(context).setMuxerFactory(new InAppMuxer.Factory.Builder().build())
                    .addListener(new Transformer.Listener() {
                @Override public void onCompleted(@NonNull Composition composition, @NonNull ExportResult result) { latch.countDown(); }

                @Override public void onError(@NonNull Composition composition, @NonNull ExportResult result, @NonNull ExportException e) {
                    error.set(e);
                    latch.countDown();
                }
            }).build();
            ref.set(t);
            try {
                t.start(MediaItem.fromUri(Uri.fromFile(in)), out.getPath());
            } catch (RuntimeException e) {
                error.set(e);
                latch.countDown();
            }
        });
        ProgressHolder holder = new ProgressHolder();
        try {
            while (!latch.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
                if (cancelled.getAsBoolean()) {
                    main.post(() -> { if (ref.get() != null) ref.get().cancel(); });
                    throw new IOException("已暂停");
                }
                CountDownLatch polled = new CountDownLatch(1);
                int[] percent = {-1};
                main.post(() -> {
                    Transformer t = ref.get();
                    if (t != null && t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) percent[0] = holder.progress;
                    polled.countDown();
                });
                polled.await(1, java.util.concurrent.TimeUnit.SECONDS);
                if (percent[0] >= 0) progress.percent(percent[0]);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("已中断");
        }
        if (error.get() != null) throw new IOException("转 MP4 失败：" + error.get().getMessage(), error.get());
    }
}
