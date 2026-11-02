package io.github.fugitive844.decotv;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.fugitive844.decotv.download.CoverCache;
import io.github.fugitive844.decotv.download.DownloadService;
import io.github.fugitive844.decotv.download.DownloadTask;
import io.github.fugitive844.decotv.net.ImageLoader;
import io.github.fugitive844.decotv.net.Relay;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;

@RunWith(AndroidJUnit4.class)
public class CoverCacheFlowTest {
    @Test public void legacyCoversPersistEncryptedOrPlainAndLoadWithoutNetwork() throws Exception {
        DecoApp app = ApplicationProvider.getApplicationContext();
        app.prefs.setMode(Relay.Item.IMAGE, Relay.Mode.DIRECT);
        Bitmap original = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888);
        original.eraseColor(0xFF137A59);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        original.compress(Bitmap.CompressFormat.PNG, 100, encoded);
        for (DownloadTask.Format format : DownloadTask.Format.values()) {
            DownloadTask task = new DownloadTask();
            task.id = UUID.randomUUID().toString(); task.videoKey = "cover-test+" + task.id;
            task.format = format; task.state = DownloadTask.State.DONE;
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(new MockResponse().setHeader("Content-Type", "image/png").setBody(new Buffer().write(encoded.toByteArray())));
                server.start();
                task.cover = server.url("/cover.png").toString();
                app.downloads.add(task);
                // 模拟升级前只有 URL 的任务：首次显示时补存。
                assertFalse(CoverCache.file(app, task).exists());
                Bitmap fetched = CoverCache.load(app, task, new ImageLoader(app.api, app.prefs));
                assertEquals(original.getPixel(0, 0), fetched.getPixel(0, 0));
                assertEquals(1, server.getRequestCount());
                File stored = CoverCache.file(app, task);
                byte[] disk = Files.readAllBytes(stored.toPath());
                if (format == DownloadTask.Format.ENCRYPTED) {
                    assertNull("加密封面不能直接当图片打开", BitmapFactory.decodeByteArray(disk, 0, disk.length));
                    assertEquals("DTV1", new String(disk, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
                    assertFalse(new File(stored.getPath() + ".tmp").exists());
                    assertFalse(new File(stored.getParentFile(), task.id + ".cover.img").exists());
                } else {
                    assertNotNull(BitmapFactory.decodeByteArray(disk, 0, disk.length));
                }
            }
            try {
                // 图片服务已关闭、换全新 ImageLoader：只能从本地文件读取。
                ImageLoader fresh = new ImageLoader(app.api, app.prefs);
                assertEquals(original.getPixel(0, 0), CoverCache.load(app, task, fresh).getPixel(0, 0));
                AtomicReference<ImageView> image = new AtomicReference<>();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    image.set(new ImageView(app));
                    fresh.offlineCover(image.get(), task);
                });
                long end = System.currentTimeMillis() + 10_000;
                AtomicReference<Boolean> visible = new AtomicReference<>(false);
                while (System.currentTimeMillis() < end && !visible.get()) {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> visible.set(image.get().getDrawable() != null));
                    Thread.sleep(50);
                }
                assertTrue("无网络时列表仍应显示封面", visible.get());
            } finally {
                DownloadService.delete(app, task);
                assertFalse("删除缓存同时删除封面", CoverCache.file(app, task).exists());
            }
        }
    }
}
