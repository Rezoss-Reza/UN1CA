package io.mesalabs.rezoss.qwen;

import android.content.Context;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Downloads the user-selected MLC model into app-private storage. */
final class MlcModelStore {
    static final String MODEL_ID = "gemma-2b-it-q4f16_0-MLC";
    private static final String MODEL_URL =
            "https://huggingface.co/mlc-ai/gemma-2b-it-q4f16_0-MLC/resolve/main/";
    private static final long APPROX_MODEL_BYTES = 1500000000L;
    private static final long APPROX_TOKENIZER_BYTES = 24000000L;
    private static final String MODEL_CONFIG = "mlc-chat-config.json";
    private static final String TENSOR_CACHE = "tensor-cache.json";
    private static final String READY_MARKER = ".ready";
    private static final int BUFFER_SIZE = 65536;
    private static final ExecutorService DOWNLOADS = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean();
    private static final AtomicBoolean CANCEL = new AtomicBoolean();

    interface Listener {
        void onProgress(long completedBytes, long totalBytes);
        void onComplete(String error);
    }

    private static final class FileSpec {
        final String name;
        final long size;
        final String md5;
        FileSpec(String name, long size, String md5) {
            this.name = safeName(name);
            this.size = size;
            this.md5 = md5;
        }
    }

    private MlcModelStore() { }

    static File modelDirectory(Context context) {
        return new File(new File(context.getFilesDir(), "mlc"), MODEL_ID);
    }

    static boolean isDownloading() { return DOWNLOADING.get(); }

    static boolean isReady(Context context) {
        File directory = modelDirectory(context);
        File marker = new File(directory, READY_MARKER);
        File modelConfigFile = new File(directory, MODEL_CONFIG);
        File tensorCacheFile = new File(directory, TENSOR_CACHE);
        if (!marker.isFile() || !modelConfigFile.isFile() || !tensorCacheFile.isFile()) return false;
        try {
            if (!MODEL_ID.equals(readText(marker).trim())) return false;
            JSONObject modelConfig = new JSONObject(readText(modelConfigFile));
            JSONArray tokenizers = modelConfig.optJSONArray("tokenizer_files");
            if (tokenizers == null || tokenizers.length() == 0) return false;
            for (int i = 0; i < tokenizers.length(); i++) {
                if (!new File(directory, safeName(tokenizers.getString(i))).isFile()) return false;
            }
            JSONArray records = new JSONObject(readText(tensorCacheFile)).optJSONArray("records");
            if (records == null || records.length() == 0) return false;
            for (int i = 0; i < records.length(); i++) {
                JSONObject record = records.getJSONObject(i);
                File shard = new File(directory, safeName(record.getString("dataPath")));
                if (!shard.isFile() || shard.length() != record.getLong("nbytes")) return false;
            }
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }

    static String modelLibrary(Context context) throws Exception {
        String config = readText(context.getAssets().open("mlc-app-config.json"));
        JSONArray records = new JSONObject(config).optJSONArray("model_list");
        if (records == null) throw new IOException("MLC model is not packaged");
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            if (MODEL_ID.equals(record.optString("model_id"))) {
                String modelLib = record.optString("model_lib", "").trim();
                if (!modelLib.isEmpty()) return modelLib;
            }
        }
        throw new IOException("MLC model library is not packaged");
    }

    static void download(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        if (!DOWNLOADING.compareAndSet(false, true)) {
            if (listener != null) listener.onComplete("A model download is already running.");
            return;
        }
        CANCEL.set(false);
        DOWNLOADS.execute(() -> {
            String error = null;
            try {
                downloadModel(app, listener);
            } catch (Exception failure) {
                error = CANCEL.get() ? "Download cancelled." : safeError(failure);
            } finally {
                DOWNLOADING.set(false);
                CANCEL.set(false);
                if (listener != null) listener.onComplete(error);
            }
        });
    }

    static void cancelDownload() { CANCEL.set(true); }

    private static void downloadModel(Context context, Listener listener) throws Exception {
        File directory = modelDirectory(context);
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create model directory");
        long required = Math.max(0, APPROX_MODEL_BYTES - downloadedModelBytes(directory));
        if (directory.getFreeSpace() < required + 100000000L)
            throw new IOException("At least 100 MB of free space beyond the model download is required");

        File modelConfigFile = new File(directory, MODEL_CONFIG);
        File tensorCacheFile = new File(directory, TENSOR_CACHE);
        transfer(directory, MODEL_CONFIG, -1, null, 0, listener, APPROX_MODEL_BYTES);
        transfer(directory, TENSOR_CACHE, -1, null, 0, listener, APPROX_MODEL_BYTES);

        JSONObject modelConfig = new JSONObject(readText(modelConfigFile));
        JSONArray tokenizerFiles = modelConfig.optJSONArray("tokenizer_files");
        JSONArray records = new JSONObject(readText(tensorCacheFile)).optJSONArray("records");
        if (tokenizerFiles == null || tokenizerFiles.length() == 0 || records == null || records.length() == 0)
            throw new IOException("The model repository returned an invalid file list");

        List<FileSpec> files = new ArrayList<>();
        for (int i = 0; i < tokenizerFiles.length(); i++) {
            files.add(new FileSpec(tokenizerFiles.getString(i), -1, null));
        }
        long modelWeightBytes = 0;
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            long size = record.getLong("nbytes");
            modelWeightBytes += size;
            files.add(new FileSpec(record.getString("dataPath"), size, record.optString("md5sum", null)));
        }
        long total = Math.max(APPROX_MODEL_BYTES, modelWeightBytes + APPROX_TOKENIZER_BYTES);
        long complete = modelConfigFile.length() + tensorCacheFile.length();
        for (FileSpec file : files) {
            checkCancelled();
            File target = new File(directory, file.name);
            if (validExisting(target, file)) {
                complete += target.length();
                notifyProgress(listener, complete, total);
                continue;
            }
            complete += transfer(directory, file.name, file.size, file.md5, complete, listener, total);
        }

        checkCancelled();
        File marker = new File(directory, READY_MARKER);
        try (OutputStream output = new FileOutputStream(marker)) {
            output.write(MODEL_ID.getBytes("UTF-8"));
            output.flush();
            ((FileOutputStream) output).getFD().sync();
        }
        notifyProgress(listener, total, total);
    }

    private static boolean validExisting(File file, FileSpec spec) {
        if (!file.isFile()) return false;
        if (spec.size >= 0 && file.length() != spec.size) return false;
        if (spec.md5 == null || spec.md5.isEmpty()) return file.length() > 0;
        try { return spec.md5.equalsIgnoreCase(md5(file)); }
        catch (Exception invalid) { return false; }
    }

    private static long transfer(File directory, String rawName, long expectedSize, String expectedMd5,
            long completedBefore, Listener listener, long total) throws Exception {
        String name = safeName(rawName);
        File target = new File(directory, name);
        File partial = new File(directory, name + ".part");
        FileSpec spec = new FileSpec(name, expectedSize, expectedMd5);
        if (validExisting(target, spec)) return target.length();

        long existing = partial.isFile() ? partial.length() : 0;
        if (expectedSize >= 0 && existing > expectedSize) {
            if (!partial.delete()) throw new IOException("Cannot reset an incomplete model file");
            existing = 0;
        }
        MessageDigest digest = MessageDigest.getInstance("MD5");
        if (existing > 0) updateDigest(digest, partial, existing);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(MODEL_URL + name).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/octet-stream");
            connection.setRequestProperty("User-Agent", "Rezoss-AI-MLC/1.0");
            if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");
            int status = connection.getResponseCode();
            long declaredSize = totalFileSize(connection, status, existing);
            if (status == 416
                    && declaredSize > 0 && existing == declaredSize) {
                if (expectedMd5 != null && !expectedMd5.equalsIgnoreCase(md5(partial)))
                    throw new IOException("Model shard checksum did not match");
                finishPartial(partial, target);
                return target.length();
            }
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL)
                throw new IOException("Model download returned HTTP " + status);
            long resolvedSize = expectedSize >= 0 ? expectedSize : declaredSize;
            if (expectedSize >= 0 && declaredSize > 0 && expectedSize != declaredSize)
                throw new IOException("The model server returned an unexpected file size");
            boolean append = status == HttpURLConnection.HTTP_PARTIAL && existing > 0;
            if (!append && existing > 0) {
                existing = 0;
                digest = MessageDigest.getInstance("MD5");
            }
            long downloadedThisFile = existing;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(partial, append), BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled();
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    downloadedThisFile += count;
                    if ((downloadedThisFile & 0x3fffff) < count)
                        notifyProgress(listener, completedBefore + downloadedThisFile, total);
                }
                output.flush();
            }
            if (resolvedSize >= 0 && partial.length() != resolvedSize)
                throw new IOException("A model file was incomplete; retry the download");
            if (expectedMd5 != null && !expectedMd5.isEmpty()
                    && !expectedMd5.equalsIgnoreCase(hex(digest.digest()))) {
                if (!partial.delete()) partial.deleteOnExit();
                throw new IOException("A model shard failed its checksum; retry the download");
            }
            finishPartial(partial, target);
            notifyProgress(listener, completedBefore + target.length(), total);
            return target.length();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void finishPartial(File partial, File target) throws IOException {
        if (target.exists() && !target.delete()) throw new IOException("Cannot replace an old model file");
        if (!partial.renameTo(target)) throw new IOException("Cannot save a downloaded model file");
    }

    private static long totalFileSize(HttpURLConnection connection, int status, long existing) {
        String range = connection.getHeaderField("Content-Range");
        if (range != null) {
            int slash = range.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < range.length()) {
                try { return Long.parseLong(range.substring(slash + 1).trim()); }
                catch (NumberFormatException ignored) { }
            }
        }
        long responseLength = connection.getContentLengthLong();
        if (responseLength < 0) return -1;
        return status == HttpURLConnection.HTTP_PARTIAL ? existing + responseLength : responseLength;
    }

    private static long downloadedModelBytes(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return 0;
        long bytes = 0;
        for (File file : files) {
            if (file.isFile() && !READY_MARKER.equals(file.getName())) bytes += file.length();
        }
        return bytes;
    }

    private static void updateDigest(MessageDigest digest, File file, long length) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long left = length;
            int count;
            while (left > 0 && (count = input.read(buffer, 0, (int) Math.min(buffer.length, left))) != -1) {
                digest.update(buffer, 0, count);
                left -= count;
            }
            if (left != 0) throw new IOException("Cannot resume an incomplete model file");
        }
    }

    private static String md5(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static String readText(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) { return readText(input); }
    }

    private static String readText(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = source.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), "UTF-8");
        }
    }

    private static String safeName(String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")
                || name.equals(".") || name.equals(".."))
            throw new IllegalArgumentException("Invalid model filename");
        return name;
    }

    private static void checkCancelled() throws IOException {
        if (CANCEL.get() || Thread.currentThread().isInterrupted()) throw new IOException("download_cancelled");
    }

    private static void notifyProgress(Listener listener, long complete, long total) {
        if (listener != null) listener.onProgress(Math.max(0, complete), Math.max(1, total));
    }

    private static String safeError(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isEmpty() || "download_cancelled".equals(message)) return "Could not download the local model.";
        return message;
    }
}
