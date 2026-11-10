package com.fieldnote.app;

import android.app.ActivityManager;
import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/** Decodes saved audio into short PCM blocks for an installed speech model. */
final class OfflineTranscriber implements AutoCloseable {
    private static final long BUFFER_WAIT_US = 10_000L;
    private static final long DECODER_STALL_MS = 30_000L;
    private static final long MAX_CHECKPOINT_WALL_MS = 90_000L;
    private static final long SEEK_PREROLL_US = 250_000L;
    static final int MAX_TRANSCRIPT_CHARS = 1_000_000;

    private final Model model;
    private final long checkpointAfterUs;
    private final long forceCheckpointAfterUs;

    OfflineTranscriber(Context context) throws IOException {
        this(context, SpeechModel.SMALL);
    }

    OfflineTranscriber(Context context, SpeechModel speechModel) throws IOException {
        this(context, speechModel, 60_000_000L, 180_000_000L);
    }

    OfflineTranscriber(Context context, long checkpointAfterUs, long forceCheckpointAfterUs) throws IOException {
        this(context, SpeechModel.SMALL, checkpointAfterUs, forceCheckpointAfterUs);
    }

    private OfflineTranscriber(Context context, SpeechModel speechModel,
                               long checkpointAfterUs, long forceCheckpointAfterUs) throws IOException {
        this.checkpointAfterUs = checkpointAfterUs;
        this.forceCheckpointAfterUs = forceCheckpointAfterUs;
        checkInterrupted();
        File directory = SpeechModels.directory(context, speechModel);
        if (speechModel.isLarge()) checkLargeModelMemory(context, speechModel);
        checkInterrupted();
        try {
            model = new Model(directory.getAbsolutePath());
        } catch (LinkageError error) {
            throw new IOException("The offline speech engine could not load.", error);
        }
    }

    private static void checkLargeModelMemory(Context context, SpeechModel model) throws IOException {
        if (!SpeechModels.isSupported(context, model)) {
            throw new IOException("The selected speech model needs a 64-bit device with more memory.");
        }
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) throw new IOException("Available memory could not be checked.");
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        // The unpacked model size gives a conservative headroom estimate for native
        // graphs and rescoring data. Leave Android's low-memory reserve untouched.
        if (memory.lowMemory || memory.availMem - memory.threshold < model.installedBytes) {
            throw new IOException("There is not enough available memory to load the selected speech model.");
        }
    }

    String transcribe(File original) throws IOException {
        return transcribe(original, 0L, "", null);
    }

    interface Checkpoint {
        /** Persists the transcript through the last processed frame and the PTS of the next frame. */
        void save(long nextTimeUs, String text) throws IOException;
    }

    String transcribe(File original, long resumeAtUs, String priorText, Checkpoint checkpoint) throws IOException {
        checkInterrupted();
        MediaExtractor extractor = new MediaExtractor();
        try (FileInputStream source = new FileInputStream(original)) {
            extractor.setDataSource(source.getFD());
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                MediaFormat format = extractor.getTrackFormat(track);
                String mimeType = format.getString(MediaFormat.KEY_MIME);
                if (mimeType != null && mimeType.startsWith("audio/")) {
                    extractor.selectTrack(track);
                    if (resumeAtUs > 0) {
                        // Feed the AAC decoder several preceding packets to restore its overlap state.
                        extractor.seekTo(Math.max(0L, resumeAtUs - SEEK_PREROLL_US), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    }
                    return decode(extractor, format, mimeType, resumeAtUs, priorText, checkpoint);
                }
            }
            throw new IOException("The recording has no audio track.");
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw new IOException("The recording could not be decoded for transcription.", error);
        } finally {
            extractor.release();
        }
    }

    private String decode(MediaExtractor extractor, MediaFormat format, String mimeType, long resumeAtUs,
                          String priorText, Checkpoint checkpoint) throws IOException {
        MediaCodec decoder = MediaCodec.createDecoderByType(mimeType);
        try (SpeechBuffer speech = new SpeechBuffer(priorText, checkpoint)) {
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            decoder.configure(format, null, null, 0);
            decoder.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEnded = false;
            boolean resumed = resumeAtUs == 0;
            long lastFrameUs = Long.MIN_VALUE;
            long lastProgress = SystemClock.elapsedRealtime();
            while (true) {
                checkInterrupted();
                if (!inputEnded) {
                    int input = decoder.dequeueInputBuffer(BUFFER_WAIT_US);
                    if (input >= 0) {
                        int bytes = extractor.readSampleData(decoder.getInputBuffer(input), 0);
                        if (bytes < 0) {
                            decoder.queueInputBuffer(input, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            decoder.queueInputBuffer(input, 0, bytes, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                        lastProgress = SystemClock.elapsedRealtime();
                    }
                }

                int output = decoder.dequeueOutputBuffer(info, BUFFER_WAIT_US);
                if (output >= 0) {
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                                && (resumed || info.presentationTimeUs >= resumeAtUs)) {
                            if (!resumed && info.presentationTimeUs != resumeAtUs) {
                                throw new IOException("The decoder could not resume at the saved audio frame.");
                            }
                            resumed = true;
                            if (speech.recognizer == null) speech.configure(decoder.getOutputFormat(output));
                            if (info.presentationTimeUs > lastFrameUs) speech.checkpointBefore(info.presentationTimeUs);
                            ByteBuffer buffer = decoder.getOutputBuffer(output);
                            buffer.limit(info.offset + info.size);
                            buffer.position(info.offset);
                            speech.accept(buffer.order(ByteOrder.nativeOrder()).asShortBuffer());
                            lastFrameUs = Math.max(lastFrameUs, info.presentationTimeUs);
                        }
                    } finally {
                        decoder.releaseOutputBuffer(output, false);
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (!resumed) throw new IOException("The saved audio frame is missing from the recording.");
                        return speech.finish();
                    }
                    lastProgress = SystemClock.elapsedRealtime();
                } else if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    speech.configure(decoder.getOutputFormat());
                    lastProgress = SystemClock.elapsedRealtime();
                }
                if (SystemClock.elapsedRealtime() - lastProgress > DECODER_STALL_MS) {
                    throw new IOException("The recording's audio decoder stopped responding.");
                }
            }
        } finally {
            decoder.release();
        }
    }

    private final class SpeechBuffer implements AutoCloseable {
        private final short[] samples = new short[4_096];
        private final StringBuilder text;
        private final Checkpoint checkpoint;
        private Recognizer recognizer;
        private int channels;
        private int sampleRate;
        private int buffered;
        private boolean atEndpoint;
        private long checkpointStartUs = Long.MIN_VALUE;
        private long checkpointStartMs;

        SpeechBuffer(String priorText, Checkpoint checkpoint) {
            text = new StringBuilder(priorText);
            this.checkpoint = checkpoint;
        }

        void configure(MediaFormat format) throws IOException {
            int outputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            boolean isPcm16 = !format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    || format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT;
            if (!isPcm16 || outputRate < 16_000 || outputChannels < 1 || outputChannels > 2) {
                throw new IOException("The recording uses an unsupported PCM audio format.");
            }
            if (recognizer == null) {
                recognizer = new Recognizer(model, outputRate);
                channels = outputChannels;
                sampleRate = outputRate;
            } else if (channels != outputChannels || sampleRate != outputRate) {
                throw new IOException("The recording's audio format changed during transcription.");
            }
        }

        void accept(ShortBuffer pcm) throws IOException {
            if (pcm.remaining() % channels != 0) throw new IOException("Incomplete PCM audio frame.");
            while (pcm.hasRemaining()) {
                int frames = Math.min(samples.length - buffered, pcm.remaining() / channels);
                if (channels == 1) {
                    pcm.get(samples, buffered, frames);
                } else {
                    for (int i = 0; i < frames; i++) {
                        samples[buffered + i] = (short) ((pcm.get() + pcm.get()) / 2);
                    }
                }
                buffered += frames;
                atEndpoint = false;
                if (buffered == samples.length) flush();
            }
        }

        void checkpointBefore(long nextTimeUs) throws IOException {
            if (checkpoint == null) return;
            long now = SystemClock.elapsedRealtime();
            if (checkpointStartUs == Long.MIN_VALUE) {
                checkpointStartUs = nextTimeUs;
                checkpointStartMs = now;
                return;
            }
            if (nextTimeUs <= 0) return;
            long elapsedUs = nextTimeUs - checkpointStartUs;
            boolean naturalBoundary = atEndpoint && elapsedUs >= checkpointAfterUs;
            boolean deadline = elapsedUs >= forceCheckpointAfterUs || now - checkpointStartMs >= MAX_CHECKPOINT_WALL_MS;
            if (!naturalBoundary && !deadline) return;

            checkpoint.save(nextTimeUs, finish());
            recognizer.close();
            recognizer = null;
            recognizer = new Recognizer(model, sampleRate);
            atEndpoint = false;
            checkpointStartUs = nextTimeUs;
            checkpointStartMs = now;
        }

        private void flush() throws IOException {
            checkInterrupted();
            atEndpoint = recognizer.acceptWaveForm(samples, buffered);
            if (atEndpoint) append(recognizer.getResult());
            buffered = 0;
        }

        String finish() throws IOException {
            if (recognizer == null) throw new IOException("The recording produced no decoded audio.");
            if (buffered > 0) flush();
            append(recognizer.getFinalResult());
            checkInterrupted();
            return text.toString();
        }

        private void append(String result) throws IOException {
            String segment;
            try {
                segment = new JSONObject(result).getString("text").trim();
            } catch (JSONException error) {
                throw new IOException("The speech engine returned an invalid transcript.", error);
            }
            if (segment.isEmpty()) return;
            if (text.length() + (long) segment.length() + (text.length() == 0 ? 0 : 1) > MAX_TRANSCRIPT_CHARS) {
                throw new IOException("The transcript exceeded the text size limit.");
            }
            if (text.length() > 0) text.append('\n');
            text.append(segment);
        }

        @Override public void close() {
            if (recognizer != null) recognizer.close();
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Transcription was interrupted.");
    }

    @Override public void close() {
        model.close();
    }
}
