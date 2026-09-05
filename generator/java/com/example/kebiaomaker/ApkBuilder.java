package com.example.kebiaomaker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import com.android.apksig.ApkSigner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ApkBuilder {

    // 模板里应用名占位：12个 UTF-16 字符（"课表"+10空格），内容共24字节
    private static final byte[] LABEL_MARKER;
    static {
        try { LABEL_MARKER = "课表".getBytes("UTF-16LE"); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static File build(Context ctx, String html, String appName, byte[] avatarBytes, File outDir) throws Exception {
        byte[] template = readAsset(ctx, "template.apk");
        byte[] unsigned = rebuild(template, html, appName, avatarBytes);

        File unsignedFile = new File(outDir, "tmp_unsigned.apk");
        FileOutputStream fos = new FileOutputStream(unsignedFile);
        fos.write(unsigned);
        fos.close();

        // 文件名直接用应用名，去掉时间戳和非法字符
        String safeName = appName == null ? "" : appName.replaceAll("[\\\\/:*?\"<>|]", "").trim();
        if (safeName.length() == 0) safeName = "课表";
        File signedFile = new File(outDir, safeName + ".apk");
        sign(ctx, unsignedFile, signedFile);
        unsignedFile.delete();
        return signedFile;
    }

    private static byte[] rebuild(byte[] template, String html, String appName, byte[] avatarBytes) throws Exception {
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(template));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zout = new ZipOutputStream(bos);

        byte[] htmlBytes = html.getBytes("UTF-8");
        byte[] iconBytes = null;
        if (avatarBytes != null && avatarBytes.length > 0) {
            iconBytes = makeIcon(avatarBytes);
        }

        ZipEntry entry;
        byte[] buf = new byte[8192];
        while ((entry = zin.getNextEntry()) != null) {
            String name = entry.getName();
            byte[] content = readAll(zin);

            if (name.equals("assets/index.html")) {
                content = htmlBytes;
            } else if (name.equals("res/drawable/ic_launcher.png") && iconBytes != null) {
                content = iconBytes;
            } else if (name.equals("AndroidManifest.xml")) {
                content = replaceLabel(content, appName);
            }

            boolean stored = name.equals("AndroidManifest.xml") || name.equals("resources.arsc");
            ZipEntry out = new ZipEntry(name);
            if (stored) {
                out.setMethod(ZipEntry.STORED);
                out.setSize(content.length);
                out.setCompressedSize(content.length);
                out.setCrc(crc32(content));
            } else {
                out.setMethod(ZipEntry.DEFLATED);
            }
            zout.putNextEntry(out);
            zout.write(content);
            zout.closeEntry();
            zin.closeEntry();
        }
        zin.close();
        zout.close();
        return bos.toByteArray();
    }

    private static byte[] replaceLabel(byte[] manifest, String appName) {
        int idx = indexOf(manifest, LABEL_MARKER);
        if (idx < 0) return manifest;
        byte[] newLabel = buildLabel(appName);
        System.arraycopy(newLabel, 0, manifest, idx, 24);
        return manifest;
    }

    // 生成12字符（24字节）UTF-16LE label，不足用空格填充
    private static byte[] buildLabel(String name) {
        byte[] raw;
        try { raw = name.getBytes("UTF-16LE"); } catch (Exception e) { raw = new byte[0]; }
        byte[] out = new byte[24];
        int n = Math.min(raw.length, 24);
        System.arraycopy(raw, 0, out, 0, n);
        for (int i = n; i < 24; i += 2) { out[i] = 0x20; out[i + 1] = 0x00; }
        return out;
    }

    private static byte[] makeIcon(byte[] data) {
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bmp == null) return null;
            int w = bmp.getWidth(), h = bmp.getHeight();
            int size = Math.min(w, h);
            int x = (w - size) / 2, y = (h - size) / 2;
            Bitmap square = Bitmap.createBitmap(bmp, x, y, size, size);
            Bitmap scaled = Bitmap.createScaledBitmap(square, 192, 192, true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 100, bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static void sign(Context ctx, File input, File output) throws Exception {
        PrivateKey key = loadPrivateKey(ctx);
        X509Certificate cert = loadCert(ctx);
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("KeBiao", key, Collections.singletonList(cert)).build();
        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(input)
                .setOutputApk(output)
                .setMinSdkVersion(21)
                .build();
        signer.sign();
    }

    private static PrivateKey loadPrivateKey(Context ctx) throws Exception {
        String pem = readAssetString(ctx, "key.pem");
        pem = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                 .replace("-----END PRIVATE KEY-----", "")
                 .replaceAll("\\s", "");
        byte[] der = Base64.decode(pem, Base64.DEFAULT);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static X509Certificate loadCert(Context ctx) throws Exception {
        String pem = readAssetString(ctx, "cert.pem");
        pem = pem.replace("-----BEGIN CERTIFICATE-----", "")
                 .replace("-----END CERTIFICATE-----", "")
                 .replaceAll("\\s", "");
        byte[] der = Base64.decode(pem, Base64.DEFAULT);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] readAsset(Context ctx, String name) throws Exception {
        InputStream is = ctx.getAssets().open(name);
        byte[] b = readAll(is);
        is.close();
        return b;
    }

    private static String readAssetString(Context ctx, String name) throws Exception {
        return new String(readAsset(ctx, name), "UTF-8");
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static int indexOf(byte[] data, byte[] pat) {
        outer:
        for (int i = 0; i <= data.length - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (data[i + j] != pat[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static long crc32(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return crc.getValue();
    }
}