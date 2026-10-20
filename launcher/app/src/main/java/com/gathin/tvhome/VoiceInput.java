package com.gathin.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Voice input from the remote's mic button (docs/specs/voice-input.md): hold, speak, let go, and the words are typed
 * into the text field in focus; with none, the card shows them. A tap of the button switches the language, among
 * those whose model is installed; the choice is kept. HomeKeyService hands it the button. Main thread.
 */
final class VoiceInput {

    /** Let go sooner, and the press was a tap. The card waits as long, so a tap doesn't flash "Listening". */
    private static final long TAP_MS = 400;
    private static final long SHOW_TYPED_MS = 1500, SHOW_UNTYPED_MS = 5000, SHOW_NOTHING_MS = 2000,
            SHOW_LANGUAGE_MS = 1500;
    private static final String PREF_LANGUAGE = "voice_language";

    /** The text field in focus, through accessibility (HomeKeyService). */
    interface Typist {
        /** The editable field in focus now, or null. */
        AccessibilityNodeInfo field();

        /** Adds the text to {@code field} if it still has focus; false otherwise. */
        boolean type(AccessibilityNodeInfo field, String text);
    }

    private final Context context;
    private final Typist typist;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final VoiceRecorder recorder;
    private final SpeechEngine engine;
    private final VoiceOverlay overlay;
    /** Bumped by every press, and by a tap: a result from an older press is dropped. Read by the worker too. */
    private volatile int press;
    /** The press whose card is up, listening; 0 before it shows. */
    private int listening;
    private long pressedAt;
    private Runnable listen;
    /** After a tap, loads the language it switched to, once the taps have stopped. */
    private final Runnable warm = this::prepareChosen;
    private volatile boolean closed;

    VoiceInput(Context service, Typist typist) {
        context = service;
        this.typist = typist;
        prefs = service.getSharedPreferences("home", Context.MODE_PRIVATE);
        recorder = new VoiceRecorder(service);
        engine = new SpeechEngine(service);
        overlay = new VoiceOverlay(service);
    }

    /** A model is installed and the mic allowed; otherwise the button is left to the firmware. */
    boolean available() {
        return ready() != null;
    }

    /** A text field got focus: dictation may come next, so the model starts loading now, as that takes seconds. */
    void warmUp() {
        String language = ready();
        if (language != null) engine.prepare(language);
    }

    /** The language to dictate in, or null when voice input can't run. */
    private String ready() {
        if (closed || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return null;
        return language();
    }

    /** The button went down. */
    void press() {
        if (closed || recorder.isRecording()) return;
        int id = ++press;
        String language = language();
        pressedAt = SystemClock.uptimeMillis();
        listen = () -> {
            if (id != press || !recorder.isRecording()) return;   // the recording already ended
            listening = id;
            overlay.language(code(language));
            overlay.listening(0);
            engine.prepare(language);
        };
        main.postDelayed(listen, TAP_MS);
        recorder.start(new VoiceRecorder.Listener() {
            @Override
            public void onLevel(float level) {
                main.post(() -> {
                    if (id == press && id == listening && !closed && recorder.isRecording()) overlay.listening(level);
                });
            }

            @Override
            public void onDone(float[] samples) {
                main.post(() -> finish(id, language, samples));
            }
        });
    }

    /** The button came up. */
    void release() {
        if (closed) return;
        main.removeCallbacks(listen);
        recorder.stop();
        if (SystemClock.uptimeMillis() - pressedAt < TAP_MS) {
            press++;   // drops the recording
            switchLanguage();
        }
    }

    void dismiss() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        recorder.stop();
        overlay.dismiss();
        engine.shutdown();
    }

    private void finish(int id, String language, float[] samples) {
        if (id != press || closed) return;
        main.removeCallbacks(listen);
        float[] speech = VoiceClip.trim(samples);
        if (speech.length == 0) {
            overlay.result(context.getString(R.string.voice_nothing), SHOW_NOTHING_MS);
            return;
        }
        overlay.working();
        AccessibilityNodeInfo field = typist.field();   // where the words go, unless focus has moved by then
        engine.transcribe(language, speech, () -> id != press || closed, heard -> main.post(() -> {
            if (id != press || closed) return;
            String text = heard == null ? "" : VoiceText.tidy(heard);
            if (text.isEmpty()) overlay.result(context.getString(R.string.voice_nothing), SHOW_NOTHING_MS);
            else overlay.result(text, typist.type(field, text) ? SHOW_TYPED_MS : SHOW_UNTYPED_MS);
        }));
    }

    /** The next installed language, shown by its name; its model loads for the next hold once the taps stop. */
    private void switchLanguage() {
        String now = language();
        if (now == null) return;
        List<String> all = Arrays.asList(SpeechEngine.LANGUAGES);
        String next = now;
        for (int i = 1; i < all.size(); i++) {
            String l = all.get((all.indexOf(now) + i) % all.size());
            if (engine.usable(l)) {
                next = l;
                break;
            }
        }
        prefs.edit().putString(PREF_LANGUAGE, next).apply();
        overlay.result(name(next), SHOW_LANGUAGE_MS);
        main.removeCallbacks(warm);
        main.postDelayed(warm, SHOW_LANGUAGE_MS);
    }

    private void prepareChosen() {
        String language = language();
        if (language != null) engine.prepare(language);
    }

    /** The chosen language, at first the system's, if its model is installed; else the first that is, or null. */
    private String language() {
        String chosen = prefs.getString(PREF_LANGUAGE, Locale.getDefault().getLanguage());
        if (engine.usable(chosen)) return chosen;
        for (String l : SpeechEngine.LANGUAGES) {
            if (engine.usable(l)) return l;
        }
        return null;
    }

    /** "EN". */
    private static String code(String language) {
        return language.toUpperCase(Locale.ROOT);
    }

    /** The language's name in itself: "English", "Русский". */
    private static String name(String language) {
        Locale l = new Locale(language);
        String name = l.getDisplayLanguage(l);
        return name.substring(0, 1).toUpperCase(l) + name.substring(1);
    }
}
