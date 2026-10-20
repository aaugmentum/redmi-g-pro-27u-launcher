package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Speech to text on the monitor: sherpa-onnx with one NVIDIA NeMo CTC model per language. English is Parakeet
 * TDT-CTC 110M; Russian is the FastConformer for ten European languages, which also writes accented English in
 * Cyrillic, hence the English model. Each runs at about 0.35x real time in 250 MB on the Redmi G Pro 27U. The
 * install script puts each language's {@code model.int8.onnx} and {@code tokens.txt} in the app's external files
 * dir, under asr/en/ and asr/ru/. One model is loaded at a time, on one background thread: loading takes six to ten
 * seconds, and key events time out after 500 ms. The model is released after ten idle minutes.
 */
final class SpeechEngine {

    private static final String TAG = "TvHomeVoice";
    private static final long IDLE_MS = 10 * 60 * 1000;
    /** The languages there can be a model for, in the order a tap of the mic button goes through them. */
    static final String[] LANGUAGES = {"en", "ru"};

    private final Context context;
    private final Handler worker;
    private final Runnable release = this::releaseNow;
    /** Worker thread only: the loaded model and its language. */
    private OfflineRecognizer recognizer;
    private String loaded;
    /** The language prepare() was last asked for: loads still queued all load this one. */
    private volatile String wanted;
    /** Languages whose model (or the native library) failed to load: not tried again until the service restarts. */
    private final Set<String> broken = ConcurrentHashMap.newKeySet();

    SpeechEngine(Context context) {
        this.context = context;
        HandlerThread thread = new HandlerThread("voice");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    /** The language's model is on the monitor and loads (as far as is known yet). */
    boolean usable(String language) {
        return !broken.contains(language) && dir() != null && model(language).isFile() && tokens(language).isFile();
    }

    /** Starts loading the language's model, so it's ready by the time the button is let go. */
    void prepare(String language) {
        wanted = language;
        worker.post(() -> load(wanted));
    }

    /**
     * The text, or null when the model couldn't be loaded; {@code done} runs on the worker thread, and not at all
     * if {@code dropped} says the result is no longer wanted by the time the worker gets to it.
     * With a file asr/debug on the monitor, the recording is kept as asr/last.wav and the text logged, to compare
     * with sherpa-onnx's command line tools.
     */
    void transcribe(String language, float[] samples, BooleanSupplier dropped, Consumer<String> done) {
        worker.post(() -> {
            if (dropped.getAsBoolean()) return;
            load(language);
            String text = null;
            if (language.equals(loaded)) {
                OfflineStream stream = null;
                try {
                    stream = recognizer.createStream();
                    stream.acceptWaveform(samples, VoiceRecorder.RATE);
                    recognizer.decode(stream);
                    text = recognizer.getResult(stream).getText();
                } catch (RuntimeException e) {
                    Log.w(TAG, "recognition failed", e);
                } finally {
                    if (stream != null) stream.release();
                }
            }
            File dir = dir();
            if (dir != null && new File(dir, "debug").isFile()) {
                saveWav(new File(dir, "last.wav"), samples);
                Log.i(TAG, "heard: " + text);
            }
            done.accept(text);
        });
    }

    void shutdown() {
        worker.removeCallbacksAndMessages(null);
        worker.post(() -> {
            releaseNow();
            worker.getLooper().quit();
        });
    }

    /** asr/ in the app's external files dir; null while shared storage isn't there (early at boot). */
    private File dir() {
        File files = context.getExternalFilesDir(null);
        return files == null ? null : new File(files, "asr");
    }

    private File model(String language) {
        return new File(new File(dir(), language), "model.int8.onnx");
    }

    private File tokens(String language) {
        return new File(new File(dir(), language), "tokens.txt");
    }

    private void load(String language) {
        worker.removeCallbacks(release);
        worker.postDelayed(release, IDLE_MS);
        if (language.equals(loaded) || broken.contains(language) || dir() == null) return;
        releaseNow();   // two models don't fit next to a video app
        long t0 = System.currentTimeMillis();
        try {
            OfflineModelConfig modelConfig = new OfflineModelConfig();
            modelConfig.setNemo(new OfflineNemoEncDecCtcModelConfig(model(language).getPath()));
            modelConfig.setTokens(tokens(language).getPath());
            modelConfig.setNumThreads(4);
            modelConfig.setProvider("cpu");
            OfflineRecognizerConfig config = new OfflineRecognizerConfig();
            config.setFeatConfig(new FeatureConfig(VoiceRecorder.RATE, 80, 0f));
            config.setModelConfig(modelConfig);
            config.setDecodingMethod("greedy_search");
            recognizer = new OfflineRecognizer(null, config);   // no AssetManager: the paths are files
            loaded = language;
            Log.i(TAG, language + " speech model loaded in " + (System.currentTimeMillis() - t0) + " ms");
        } catch (Throwable t) {   // UnsatisfiedLinkError, ExceptionInInitializerError, a bad model file
            Log.w(TAG, "can't load the " + language + " speech model", t);
            broken.add(language);
        }
    }

    private static void saveWav(File file, float[] samples) {
        ByteBuffer b = ByteBuffer.allocate(44 + samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + samples.length * 2).put("WAVEfmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(VoiceRecorder.RATE).putInt(VoiceRecorder.RATE * 2)
                .putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(samples.length * 2);
        for (float v : samples) b.putShort((short) Math.max(-32768, Math.min(32767, Math.round(v * 32768))));
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(b.array());
        } catch (IOException e) {
            Log.w(TAG, "can't save " + file, e);
        }
    }

    private void releaseNow() {
        if (recognizer == null) return;
        recognizer.release();
        recognizer = null;
        Log.i(TAG, loaded + " speech model released");
        loaded = null;
    }
}
