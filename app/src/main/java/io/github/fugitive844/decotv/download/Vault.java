package io.github.fugitive844.decotv.download;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * App 专用加密格式（.dtv）：每个文件随机生成 256 位内容密钥，用 AES-CTR 加密视频（可随机读取，便于拖动进度）；
 * 内容密钥再用 Android Keystore 中不可导出的主密钥（AES-GCM）包裹后写在文件头。
 * 文件拷到别的 App 或别的手机都无法解密；卸载 App 后主密钥随之删除。
 *
 * 文件头：魔数 "DTV1" | GCM IV(12) | 包裹后密钥长度(2) | 包裹后密钥 | CTR IV(16)，其后是密文。
 */
public final class Vault {
    private static final String ALIAS = "decotv_offline_master";
    private static final byte[] MAGIC = "DTV1".getBytes(StandardCharsets.US_ASCII);

    private Vault() {}

    private static SecretKey master() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return gen.generateKey();
    }

    /** 加密 plain 到 out，返回写出的字节数。 */
    public static long encrypt(File plain, File out) throws IOException {
        try {
            byte[] key = new byte[32], ctrIv = new byte[16];
            SecureRandom random = new SecureRandom();
            random.nextBytes(key);
            random.nextBytes(ctrIv);
            Cipher wrap = Cipher.getInstance("AES/GCM/NoPadding");
            wrap.init(Cipher.ENCRYPT_MODE, master());
            byte[] wrapped = wrap.doFinal(key);
            byte[] gcmIv = wrap.getIV();
            Cipher ctr = Cipher.getInstance("AES/CTR/NoPadding");
            ctr.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(ctrIv));
            Arrays.fill(key, (byte) 0);
            long written = 0;
            try (InputStream in = new FileInputStream(plain); OutputStream o = new FileOutputStream(out)) {
                o.write(MAGIC);
                o.write(gcmIv);
                o.write(ByteBuffer.allocate(2).putShort((short) wrapped.length).array());
                o.write(wrapped);
                o.write(ctrIv);
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    byte[] enc = ctr.update(buf, 0, n);
                    if (enc != null) {
                        o.write(enc);
                        written += enc.length;
                    }
                }
                byte[] tail = ctr.doFinal();
                if (tail != null && tail.length > 0) {
                    o.write(tail);
                    written += tail.length;
                }
            }
            return written;
        } catch (GeneralSecurityException e) {
            throw new IOException("加密失败：" + e.getMessage(), e);
        }
    }

    /** 已打开的加密文件，可从任意明文位置开始解密读取。 */
    public static final class Reader implements AutoCloseable {
        private final RandomAccessFile file;
        private final SecretKeySpec key;
        private final byte[] ctrIv;
        private final long header;
        private Cipher cipher;

        public Reader(File f) throws IOException {
            file = new RandomAccessFile(f, "r");
            try {
                byte[] magic = new byte[4];
                file.readFully(magic);
                if (!Arrays.equals(magic, MAGIC)) throw new IOException("不是 DecoTV 加密文件");
                byte[] gcmIv = new byte[12];
                file.readFully(gcmIv);
                int len = file.readShort() & 0xFFFF;
                byte[] wrapped = new byte[len];
                file.readFully(wrapped);
                ctrIv = new byte[16];
                file.readFully(ctrIv);
                header = file.getFilePointer();
                Cipher unwrap = Cipher.getInstance("AES/GCM/NoPadding");
                unwrap.init(Cipher.DECRYPT_MODE, master(), new GCMParameterSpec(128, gcmIv));
                key = new SecretKeySpec(unwrap.doFinal(wrapped), "AES");
            } catch (GeneralSecurityException e) {
                file.close();
                throw new IOException("无法解密，文件可能来自其他设备或 App 已重装", e);
            } catch (IOException e) {
                file.close();
                throw e;
            }
        }

        public long length() throws IOException { return file.length() - header; }

        /** 定位到明文位置 position。CTR 模式下计数器 = 初始 IV + 块序号。 */
        public void seek(long position) throws IOException {
            try {
                long block = position / 16;
                byte[] iv = new BigInteger(1, ctrIv).add(BigInteger.valueOf(block)).toByteArray();
                byte[] counter = new byte[16];
                // BigInteger 可能多出符号位或不足 16 字节，取低 16 字节
                int copy = Math.min(16, iv.length);
                System.arraycopy(iv, iv.length - copy, counter, 16 - copy, copy);
                cipher = Cipher.getInstance("AES/CTR/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(counter));
                int skip = (int) (position % 16);
                if (skip > 0) cipher.update(new byte[skip]);
                file.seek(header + position);
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
        }

        public int read(byte[] buffer, int offset, int length) throws IOException {
            byte[] enc = new byte[length];
            int n = file.read(enc, 0, length);
            if (n <= 0) return -1;
            byte[] plain = cipher.update(enc, 0, n);
            if (plain == null) return 0;
            System.arraycopy(plain, 0, buffer, offset, plain.length);
            return plain.length;
        }

        @Override public void close() throws IOException { file.close(); }
    }
}
