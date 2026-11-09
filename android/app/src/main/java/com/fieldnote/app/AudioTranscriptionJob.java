package com.fieldnote.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Transcribes durable audio off the capture and publication workers. */
public final class AudioTranscriptionJob extends JobService {
    static final String ACTION_TRANSCRIPT_UPDATED = "com.fieldnote.app.TRANSCRIPT_UPDATED";
    static final int JOB_ID = 0x464e5458;
    private static final String EXTRA_TOKEN = "token";
    private static final int MAX_RECORD_BYTES = 6 * OfflineTranscriber.MAX_TRANSCRIPT_CHARS + 16 * 1024;
    private static final long PREFERRED_START_WINDOW_MS = 5_000L;
    private static final Object QUEUE = new Object();
    private static final Object SCHEDULER = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Fieldnote-transcription");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static Run active;
    private static boolean finishing;
    private static boolean backingOff;
    private static long generation;

    static void enqueue(Context context, Uri project, String projectId, String mediaId, String sha256)
            throws IOException {
        Context app = context.getApplicationContext();
        Request request = new Request(project, projectId, mediaId, sha256.toLowerCase(Locale.ROOT));
        File path = recordPath(app, request);
        boolean newlyQueued;
        synchronized (QUEUE) {
            newlyQueued = !path.isFile() && !backup(path).isFile();
            if (newlyQueued) write(path, request);
            else {
                if (!request.sameIdentity(read(path))) throw new IOException("Transcription queue identity mismatch.");
            }
        }
        synchronized (SCHEDULER) {
            if (newlyQueued) generation++;
            // A recording in an inaccessible folder must not delay a new recording.
            if (active == null && !finishing) schedule(app, newlyQueued && backingOff);
        }
    }

    /** Startup fallback for work left in private storage after process or system interruption. */
    static void kick(Context context) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            synchronized (SCHEDULER) {
                if (active != null || finishing) return;
                try { schedule(app, false); }
                catch (IOException ignored) { /* The next capture or startup can retry the durable queue. */ }
            }
        });
    }

    private static void schedule(Context app, boolean replace) throws IOException {
        try {
            JobScheduler scheduler = app.getSystemService(JobScheduler.class);
            if (scheduler == null) throw new IOException("Android's job scheduler is unavailable.");
            if (!replace && scheduler.getPendingJob(JOB_ID) != null) return;
            PersistableBundle extras = new PersistableBundle();
            extras.putString(EXTRA_TOKEN, UUID.randomUUID().toString());
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(app, AudioTranscriptionJob.class))
                    .setPersisted(true).setExtras(extras).setMinimumLatency(0)
                    .setOverrideDeadline(PREFERRED_START_WINDOW_MS)
                    .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                throw new IOException("Android could not schedule transcription.");
            }
            backingOff = false;
        } catch (RuntimeException failure) {
            throw new IOException("Android could not schedule transcription.", failure);
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        synchronized (SCHEDULER) {
            if (active != null) active.work.cancel(true);
            active = new Run(params, generation);
            start(active);
        }
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        synchronized (SCHEDULER) {
            if (active == null || !active.token.equals(params.getExtras().getString(EXTRA_TOKEN, ""))) return false;
            active.work.cancel(true);
            active = null;
            backingOff = true;
            return true;
        }
    }

    private void start(Run run) {
        Context app = getApplicationContext();
        run.work = WORKER.submit(() -> {
            boolean retry = process(app);
            MAIN.post(() -> finish(run, retry));
        });
    }

    private void finish(Run run, boolean retry) {
        synchronized (SCHEDULER) {
            if (active != run) return;
            if (generation != run.generation) {
                run.generation = generation;
                start(run);
                return;
            }
            finishing = true;
            active = null;
            backingOff = retry;
            jobFinished(run.params, retry);
        }
        // JobService posts its finish message to the same looper. A recording may
        // have queued work between that message and the end of the finishing turn.
        MAIN.post(() -> {
            synchronized (SCHEDULER) {
                finishing = false;
                if (active != null || generation == run.generation) return;
                try { schedule(getApplicationContext(), true); }
                catch (IOException ignored) { /* The durable request is available for startup recovery. */ }
            }
        });
    }

    private static boolean process(Context app) {
        ProjectStore store = new ProjectStore(app);
        File original = new File(app.getCacheDir(), "offline-transcription-input.m4a");
        OfflineTranscriber engine = null;
        boolean retry = false;
        try {
            for (File path : records(app)) {
                try {
                    checkInterrupted();
                    Request request;
                    synchronized (QUEUE) { request = read(path); }
                    if (!path.equals(recordPath(app, request))) throw new IOException("Unexpected transcription record identity.");
                    if (request.result == null) {
                        if (!store.copyTranscriptInput(request, original)) {
                            remove(path);
                            broadcast(app, request.project);
                            continue;
                        }
                        try {
                            checkInterrupted();
                            if (engine == null) engine = new OfflineTranscriber(app);
                            String text = engine.transcribe(original, request.resumeAtUs, request.priorText,
                                    (nextTimeUs, recognized) -> checkpoint(path, request, nextTimeUs, recognized)).trim();
                            request.result = result(text.isEmpty() ? "no_speech" : "complete", text);
                        } catch (InterruptedIOException stopped) { return true; }
                        catch (CheckpointWriteFailure failure) { throw failure; }
                        catch (IOException | RuntimeException | LinkageError | OutOfMemoryError failed) {
                            request.result = result("failed", "");
                        } finally { original.delete(); }
                        checkInterrupted();
                        synchronized (QUEUE) { write(path, request); }
                    }
                    checkInterrupted();
                    store.saveTranscript(request, request.result);
                    broadcast(app, request.project);
                    remove(path);
                } catch (InterruptedIOException paused) { return true; }
                catch (IOException | JSONException | RuntimeException failure) {
                    // Other recordings can finish while this request waits for storage.
                    retry = true;
                }
            }
            return retry;
        } finally {
            original.delete();
            if (engine != null) {
                try { engine.close(); }
                catch (RuntimeException | LinkageError ignored) { /* The recorded result was already handled. */ }
            }
        }
    }

    /** One atomic record ties the next unprocessed sample to its exact recognized prefix. */
    private static void checkpoint(File path, Request request, long nextTimeUs, String recognized) throws IOException {
        checkInterrupted();
        if (nextTimeUs <= request.resumeAtUs || recognized.length() > OfflineTranscriber.MAX_TRANSCRIPT_CHARS) {
            throw new IOException("Transcription reported invalid progress.");
        }
        request.resumeAtUs = nextTimeUs;
        request.priorText = recognized;
        try {
            synchronized (QUEUE) { write(path, request); }
        } catch (IOException failure) { throw new CheckpointWriteFailure(failure); }
    }

    private static JSONObject result(String status, String text) throws JSONException {
        JSONObject result = new JSONObject().put("status", status);
        if ("complete".equals(status)) result.put("text", text);
        return result;
    }

    private static void broadcast(Context app, Uri project) {
        try {
            app.sendBroadcast(new Intent(ACTION_TRANSCRIPT_UPDATED).setPackage(app.getPackageName())
                    .putExtra("projectUri", project.toString()), "com.fieldnote.app.INTERNAL");
        } catch (RuntimeException ignored) { /* The next verified read still contains the result. */ }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Android paused transcription.");
    }

    private static File directory(Context app) { return new File(app.getFilesDir(), "pending-transcripts"); }

    private static File recordPath(Context app, Request request) {
        String key = request.project + "\n" + request.projectId + "\n" + request.mediaId + "\n" + request.sha256;
        UUID id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        return new File(directory(app), id + ".json");
    }

    private static File backup(File path) { return new File(path.getPath() + ".bak"); }

    private static Set<File> records(Context app) {
        synchronized (QUEUE) {
            File dir = directory(app);
            File[] paths = dir.listFiles((ignored, name) -> name.endsWith(".json") || name.endsWith(".json.bak"));
            Set<File> records = new LinkedHashSet<>();
            if (paths == null) return records;
            for (File path : paths) {
                String name = path.getName();
                records.add(name.endsWith(".bak") ? new File(dir, name.substring(0, name.length() - 4)) : path);
            }
            return records;
        }
    }

    private static Request read(File path) throws IOException {
        try {
            if (path.length() > MAX_RECORD_BYTES || backup(path).length() > MAX_RECORD_BYTES) {
                throw new IOException("Transcription queue record exceeds its size limit.");
            }
            byte[] bytes = new AtomicFile(path).readFully();
            JSONObject json = ProjectJson.parseObject(new String(bytes, StandardCharsets.UTF_8));
            Request request = new Request(Uri.parse(json.getString("projectUri")), json.getString("projectId"),
                    json.getString("mediaId"), json.getString("sha256"));
            if (json.has("result")) request.result = json.getJSONObject("result");
            else if (json.has("resumeAtUs") || json.has("priorText")) {
                request.resumeAtUs = json.getLong("resumeAtUs");
                request.priorText = json.getString("priorText");
                if (request.resumeAtUs <= 0 || request.priorText.length() > OfflineTranscriber.MAX_TRANSCRIPT_CHARS) {
                    throw new IOException("Transcription checkpoint is invalid.");
                }
            }
            return request;
        } catch (JSONException invalid) { throw new IOException("Transcription queue record is invalid.", invalid); }
    }

    private static void write(File path, Request request) throws IOException {
        File parent = path.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Transcription queue is unavailable.");
        byte[] encoded;
        try {
            JSONObject record = new JSONObject().put("projectUri", request.project.toString())
                    .put("projectId", request.projectId).put("mediaId", request.mediaId).put("sha256", request.sha256);
            if (request.result != null) record.put("result", request.result);
            else if (request.resumeAtUs > 0) record.put("resumeAtUs", request.resumeAtUs).put("priorText", request.priorText);
            encoded = record.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException invalid) { throw new IOException("Transcription queue record could not be created.", invalid); }
        if (encoded.length > MAX_RECORD_BYTES) throw new IOException("Transcription queue record exceeds its size limit.");
        AtomicFile atomic = new AtomicFile(path);
        FileOutputStream output = atomic.startWrite();
        try {
            output.write(encoded);
            output.getFD().sync();
        } catch (IOException failure) {
            atomic.failWrite(output);
            throw failure;
        }
        atomic.finishWrite(output);
        if (!Arrays.equals(encoded, atomic.readFully())) throw new IOException("Transcription queue record could not be verified.");
    }

    private static void remove(File path) throws IOException {
        synchronized (QUEUE) {
            new AtomicFile(path).delete();
            if (path.exists() || backup(path).exists()) throw new IOException("Completed transcription record could not be removed.");
        }
    }

    static final class Request {
        final Uri project;
        final String projectId, mediaId, sha256;
        private JSONObject result;
        private long resumeAtUs;
        private String priorText = "";

        private Request(Uri project, String projectId, String mediaId, String sha256) {
            this.project = project;
            this.projectId = projectId;
            this.mediaId = mediaId;
            this.sha256 = sha256;
        }

        private boolean sameIdentity(Request other) {
            return project.equals(other.project) && projectId.equals(other.projectId)
                    && mediaId.equals(other.mediaId) && sha256.equals(other.sha256);
        }
    }

    private static final class CheckpointWriteFailure extends IOException {
        private CheckpointWriteFailure(IOException cause) { super("Transcription checkpoint could not be saved.", cause); }
    }

    private static final class Run {
        final JobParameters params;
        final String token;
        long generation;
        Future<?> work;

        private Run(JobParameters params, long generation) {
            this.params = params;
            this.generation = generation;
            token = params.getExtras().getString(EXTRA_TOKEN, "");
        }
    }
}
