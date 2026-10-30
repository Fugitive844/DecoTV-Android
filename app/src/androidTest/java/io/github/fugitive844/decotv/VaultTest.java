package io.github.fugitive844.decotv;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import io.github.fugitive844.decotv.download.Vault;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;

/** 加密缓存：任意位置开始解密都与原文一致（拖动进度），密文中不含明文。 */
@RunWith(AndroidJUnit4.class)
public class VaultTest {
    @Test public void randomAccessDecryptMatchesPlain() throws Exception {
        File dir = ApplicationProvider.getApplicationContext().getCacheDir();
        File plain = new File(dir, "vault-plain.bin"), enc = new File(dir, "vault-enc.dtv");
        byte[] data = new byte[3 * 1024 * 1024 + 123];
        Random random = new Random(7);
        random.nextBytes(data);
        try (FileOutputStream o = new FileOutputStream(plain)) {
            o.write(data);
        }
        assertEquals(data.length, Vault.encrypt(plain, enc));
        byte[] cipher = Files.readAllBytes(enc.toPath());
        assertFalse("密文不应包含明文片段", indexOf(cipher, Arrays.copyOfRange(data, 1000, 1064)) >= 0);
        try (Vault.Reader r = new Vault.Reader(enc)) {
            assertEquals(data.length, r.length());
            for (long pos : new long[]{0, 1, 15, 16, 17, 4095, 1048575, 2000001, data.length - 5}) {
                r.seek(pos);
                byte[] buf = new byte[(int) Math.min(70000, data.length - pos)];
                int got = 0;
                while (got < buf.length) {
                    int n = r.read(buf, got, buf.length - got);
                    if (n < 0) break;
                    got += n;
                }
                assertEquals(buf.length, got);
                assertArrayEquals("位置 " + pos, Arrays.copyOfRange(data, (int) pos, (int) pos + buf.length), buf);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        plain.delete();
        //noinspection ResultOfMethodCallIgnored
        enc.delete();
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
