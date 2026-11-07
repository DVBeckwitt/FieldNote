package com.fieldnote.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Publishes durable local captures through one process worker and a persisted wakeup. */
public final class ProjectPublisher extends JobService {
    public static final String ACTION_STATE = "com.fieldnote.app.PROJECT_PUBLICATION_STATE";
    public static final String ACTION_MEDIA_SAVED = "com.fieldnote.app.MEDIA_SAVED";
    public static final int JOB_ID = 0x464e5055;
    static final String EXTRA_SCHEDULE_ID = "com.fieldnote.app.PROJECT_SCHEDULE_ID";
    private static final long RETRY_DELAY_MS = 30_000L;
    private static final long MAX_RETRY_DELAY_MS = 30L * 60L * 1_000L;
    private static final Object GATE = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Fieldnote-project-publisher");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final Map<String, JobToken> JOBS = new HashMap<>();
    private static volatile Snapshot state = new Snapshot(false, 0, 0, 0, "", 0);
    private static Context application;
    private static Run active;
    private static long generation;
    private static long handledGeneration;
    private static long retryEpoch;
    private static long stagedEpoch;
    private static long retryAt;
    private static long nextRetryDelayMs = RETRY_DELAY_MS;
    private static boolean reserved;
    private static boolean finishingJobs;
    private static boolean replaceFinishedJob;
    private static boolean scheduledRegular;
    private static boolean countRefreshQueued;
    private static long scheduledRetryAt = -1;
    private static String publicationError = "";
    private static String schedulingError = "";

    public static final class Snapshot {
        public final boolean running;
        public final int pendingCount;
        public final int finalizedCount;
        public final int unfinishedCount;
        public final String error;
        public final long generation;

        private Snapshot(boolean running, int pendingCount, int finalizedCount,
                         int unfinishedCount, String error, long generation) {
            this.running = running;
            this.pendingCount = pendingCount;
            this.finalizedCount = finalizedCount;
            this.unfinishedCount = unfinishedCount;
            this.error = error;
            this.generation = generation;
        }
    }

    public static Snapshot snapshot() { return state; }

    /** Best effort: a scheduling failure never invalidates an already-staged capture. */
    public static void kick(Context context) { request(context, false, false); }

    /** Explicit user retry grants a fresh attempt even during automatic backoff. */
    public static void retry(Context context) { request(context, true, false); }

    /**
     * Newly durable work gets an immediate pass even if an unrelated project failed.
     * Unlike explicit retry, it does not reset the delay for recurring failures.
     */
    public static void captureStaged(Context context) { request(context, false, true); }

    private static void request(Context context, boolean explicit, boolean staged) {
        try {
            Context app = appContext(context);
            synchronized (GATE) {
                application = app;
                generation++;
                if (explicit) {
                    retryEpoch++;
                    retryAt = 0;
                    nextRetryDelayMs = RETRY_DELAY_MS;
                }
                if (staged) {
                    stagedEpoch++;
                    retryAt = 0;
                }
                if (explicit || SystemClock.elapsedRealtime() >= retryAt) reserved = true;
                updateSnapshotLocked(state.pendingCount, state.finalizedCount);
            }
            // Reserve synchronously so callers cannot mistake a not-yet-started pass for idle.
            if (!MAIN.post(() -> pump(app))) {
                synchronized (GATE) { reserved = false; }
                reportSchedulingFailure(app, "Android could not queue the project publisher.");
            }
        } catch (RuntimeException failure) {
            reportSchedulingFailure(null, "Background saving could not be scheduled: " + message(failure));
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        Context app = appContext(this);
        synchronized (GATE) {
            application = app;
            String identity = scheduleIdentity(params.getJobId(), params.getExtras());
            JobToken token = JOBS.get(identity);
            if (token == null) {
                token = new JobToken(this, params);
                JOBS.put(identity, token);
            }
            scheduledRetryAt = -1;
            replaceFinishedJob = false;
            if (active != null && !active.stopRequested && active.owner == null) active.owner = token;
            if (active == null) {
                generation++;
                if (SystemClock.elapsedRealtime() >= retryAt) reserved = true;
            }
            updateSnapshotLocked(state.pendingCount, state.finalizedCount);
        }
        pump(app);
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        Thread interrupted = null;
        Context app;
        synchronized (GATE) {
            app = application;
            JobToken token = JOBS.remove(scheduleIdentity(params.getJobId(), params.getExtras()));
            if (token != null) {
                token.stopped = true;
                // A delayed stop callback must never interrupt a newer run or job owner.
                if (active != null && active.owner == token) {
                    active.stopRequested = true;
                    interrupted = active.thread;
                    retryAt = Math.max(retryAt, SystemClock.elapsedRealtime() + RETRY_DELAY_MS);
                    publicationError = "Android paused project saving. Originals remain queued for retry.";
                }
            }
            updateSnapshotLocked(state.pendingCount, state.finalizedCount);
        }
        if (interrupted != null) interrupted.interrupt();
        broadcast(app, ACTION_STATE);
        return true;
    }

    /** Main-thread scheduling only; queue scans and publication run on WORKER. */
    private static void pump(Context app) {
        refreshCounts(app);
        boolean cooling;
        boolean hasJobs;
        boolean needsPass;
        boolean occupied;
        synchronized (GATE) {
            occupied = active != null || finishingJobs;
            cooling = SystemClock.elapsedRealtime() < retryAt;
            hasJobs = hasLiveJobsLocked();
            needsPass = generation > handledGeneration || hasJobs;
            if (!occupied && (cooling || !needsPass)) reserved = false;
            updateSnapshotLocked(state.pendingCount, state.finalizedCount);
        }
        if (occupied) { broadcast(app, ACTION_STATE); return; }
        if (cooling) {
            if (hasJobs) finishJobs(app, true);
            else ensureScheduled(app, false, true);
            broadcast(app, ACTION_STATE);
            return;
        }
        if (!needsPass) {
            broadcast(app, ACTION_STATE);
            return;
        }
        // Arrange process-death/reboot recovery before beginning the immediate attempt.
        if (!hasJobs) ensureScheduled(app, true, false);
        Run run;
        synchronized (GATE) {
            if (active != null || finishingJobs) return;
            run = new Run(generation, retryEpoch, stagedEpoch);
            for (JobToken token : JOBS.values()) {
                if (!token.stopped && !token.finished) { run.owner = token; break; }
            }
            active = run;
            reserved = false;
            updateSnapshotLocked(state.pendingCount, state.finalizedCount);
        }
        broadcast(app, ACTION_STATE);
        try { WORKER.execute(() -> publish(app, run)); }
        catch (RuntimeException failure) { complete(app, run, "The project worker could not start: " + message(failure), false, null); }
    }

    private static void publish(Context app, Run run) {
        String error = "";
        long before = ProjectStore.publicationGeneration();
        synchronized (GATE) { run.thread = Thread.currentThread(); }
        try {
            if (run.stopRequested) throw new java.io.InterruptedIOException("Project saving was paused.");
            new ProjectStore(app).retryPending();
        } catch (Exception failure) { error = message(failure); }
        finally {
            synchronized (GATE) {
                run.thread = null;
                if (run.stopRequested && error.isEmpty()) error = "Android paused project saving. Originals remain queued for retry.";
            }
            // Executor threads are reused; cancellation belongs to this run only.
            Thread.interrupted();
        }
        boolean published = ProjectStore.publicationGeneration() != before;
        final String result = error;
        ProjectStore.PendingCounts counts = readCounts(app);
        MAIN.post(() -> complete(app, run, result, published, counts));
    }

    private static void complete(Context app, Run run, String error, boolean published,
                                 ProjectStore.PendingCounts counts) {
        boolean anotherPass;
        boolean failed;
        synchronized (GATE) {
            if (active != run) return;
            // onStopJob may arrive after the worker posted this completion callback.
            if (run.stopRequested && error.isEmpty()) error = "Android paused project saving. Originals remain queued for retry.";
            failed = !error.isEmpty();
            active = null;
            handledGeneration = Math.max(handledGeneration, run.generation);
            publicationError = error;
            boolean explicitRetry = retryEpoch > run.retryEpoch;
            // A capture staged after this run began may not have been in its journal
            // snapshot. Reserve one more pass even if another project made it fail.
            boolean newWork = stagedEpoch > run.stagedEpoch;
            if (failed && !explicitRetry) {
                // Replacing a finished platform job starts a new scheduler backoff
                // history, so retain the bounded exponential delay ourselves.
                retryAt = newWork ? 0 : SystemClock.elapsedRealtime() + nextRetryDelayMs;
                nextRetryDelayMs = Math.min(MAX_RETRY_DELAY_MS, nextRetryDelayMs * 2);
            } else {
                retryAt = 0;
                nextRetryDelayMs = RETRY_DELAY_MS;
            }
            anotherPass = generation > handledGeneration && (!failed || explicitRetry || newWork);
            reserved = anotherPass;
            updateSnapshotLocked(counts == null ? state.pendingCount : counts.pendingCount,
                    counts == null ? state.finalizedCount : counts.finalizedCount);
        }
        if (published) broadcast(app, ACTION_MEDIA_SAVED);
        broadcast(app, ACTION_STATE);
        if (anotherPass && !failed) {
            // Keep the same live job/wakelock while draining captures staged during this pass.
            pump(app);
            return;
        }
        if (finishJobs(app, failed || state.finalizedCount > 0)) return;
        if (failed) ensureScheduled(app, false, true);
        if (anotherPass) pump(app);
    }

    /** Ends only live tokens; the following main-loop turn handles the asynchronous finish. */
    private static boolean finishJobs(Context app, boolean needsReschedule) {
        List<JobToken> finished = new ArrayList<>();
        synchronized (GATE) {
            if (active != null || finishingJobs) return false;
            for (JobToken token : JOBS.values()) {
                if (!token.stopped && !token.finished) {
                    token.finished = true;
                    finished.add(token);
                }
            }
            JOBS.clear();
            if (finished.isEmpty()) return false;
            finishingJobs = true;
            replaceFinishedJob = true;
        }
        for (JobToken token : finished) token.service.jobFinished(token.params, needsReschedule);
        // JobServiceEngine posts jobFinished to this looper. Do not replace the job before it.
        MAIN.post(() -> {
            synchronized (GATE) { finishingJobs = false; }
            pump(app);
        });
        return true;
    }

    private static void ensureScheduled(Context app, boolean expedited, boolean replace) {
        long target;
        synchronized (GATE) {
            if (finishingJobs || hasLiveJobsLocked() || (active != null && active.owner != null && !active.owner.stopped)) return;
            target = retryAt;
        }
        try {
            JobScheduler scheduler = app.getSystemService(JobScheduler.class);
            if (scheduler == null) throw new IllegalStateException("Android's job scheduler is unavailable.");
            JobInfo existing = scheduler.getPendingJob(JOB_ID);
            synchronized (GATE) {
                if (existing != null && !replaceFinishedJob
                        && (!replace || (scheduledRegular && scheduledRetryAt == target))) {
                    schedulingError = "";
                    updateSnapshotLocked(state.pendingCount, state.finalizedCount);
                    return;
                }
            }
            long delay = Math.max(0, target - SystemClock.elapsedRealtime());
            boolean useExpedited = expedited && delay == 0 && Build.VERSION.SDK_INT >= 31;
            int result = JobScheduler.RESULT_FAILURE;
            if (useExpedited) {
                try { result = scheduler.schedule(job(app, true, 0)); }
                catch (RuntimeException ignored) { /* Try the compatible persisted fallback once. */ }
            }
            if (result != JobScheduler.RESULT_SUCCESS) {
                useExpedited = false;
                result = scheduler.schedule(job(app, false, delay));
            }
            if (result != JobScheduler.RESULT_SUCCESS) throw new IllegalStateException("Android deferred the scheduling request; reopen the app or use Retry.");
            synchronized (GATE) {
                scheduledRegular = !useExpedited;
                scheduledRetryAt = target;
                replaceFinishedJob = false;
                schedulingError = "";
                updateSnapshotLocked(state.pendingCount, state.finalizedCount);
            }
        } catch (RuntimeException failure) {
            reportSchedulingFailure(app, "Background saving could not be scheduled: " + message(failure));
        }
    }

    /** Binder reconstructs start/stop parameters separately; Java object identity is not stable. */
    static String scheduleIdentity(int jobId, PersistableBundle extras) {
        String schedule = extras == null ? null : extras.getString(EXTRA_SCHEDULE_ID);
        // A job persisted by an older beta has no token. New replacement jobs always
        // receive a UUID, so a late legacy stop cannot cancel their worker owner.
        return jobId + ":" + (schedule == null ? "legacy" : schedule);
    }

    private static JobInfo job(Context app, boolean expedited, long delay) {
        PersistableBundle extras = new PersistableBundle();
        extras.putString(EXTRA_SCHEDULE_ID, UUID.randomUUID().toString());
        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, new ComponentName(app, ProjectPublisher.class))
                .setExtras(extras).setPersisted(true)
                .setBackoffCriteria(RETRY_DELAY_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        if (expedited && Build.VERSION.SDK_INT >= 31) builder.setExpedited(true);
        else builder.setMinimumLatency(delay); // API26–28 require at least one constraint.
        return builder.build();
    }

    private static void refreshCounts(Context app) {
        synchronized (GATE) {
            if (countRefreshQueued) return;
            countRefreshQueued = true;
        }
        try {
            WORKER.execute(() -> {
                ProjectStore.PendingCounts counts = readCounts(app);
                if (!MAIN.post(() -> {
                    boolean changed;
                    synchronized (GATE) {
                        countRefreshQueued = false;
                        changed = counts != null && (counts.pendingCount != state.pendingCount
                                || counts.finalizedCount != state.finalizedCount);
                        if (changed) updateSnapshotLocked(counts.pendingCount, counts.finalizedCount);
                    }
                    if (changed) broadcast(app, ACTION_STATE);
                })) {
                    synchronized (GATE) { countRefreshQueued = false; }
                }
            });
        } catch (RuntimeException failure) {
            synchronized (GATE) { countRefreshQueued = false; }
            reportSchedulingFailure(app, "Pending capture status could not be queued: " + message(failure));
        }
    }

    /** Called on the same serial worker as publication so snapshots reach MAIN in order. */
    private static ProjectStore.PendingCounts readCounts(Context app) {
        try {
            return new ProjectStore(app).pendingCounts();
        } catch (RuntimeException failure) {
            reportSchedulingFailure(app, "Pending capture status is unavailable: " + message(failure));
            return null;
        }
    }

    private static void updateSnapshotLocked(int pending, int finalized) {
        String error = publicationError;
        if (!schedulingError.isEmpty()) error = error.isEmpty() ? schedulingError : error + "\n" + schedulingError;
        state = new Snapshot(active != null || reserved, pending, finalized, Math.max(0, pending - finalized), error, generation);
    }

    private static boolean hasLiveJobsLocked() {
        for (JobToken token : JOBS.values()) if (!token.stopped && !token.finished) return true;
        return false;
    }

    private static Context appContext(Context context) {
        if (context == null) throw new IllegalArgumentException("An application context is required.");
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }

    private static void reportSchedulingFailure(Context app, String error) {
        synchronized (GATE) {
            schedulingError = error;
            updateSnapshotLocked(state.pendingCount, state.finalizedCount);
        }
        broadcast(app, ACTION_STATE);
    }

    private static String message(Exception failure) {
        String text = failure.getMessage();
        return text == null || text.trim().isEmpty() ? failure.getClass().getSimpleName() : text;
    }

    private static void broadcast(Context app, String action) {
        if (app == null) return;
        try { app.sendBroadcast(new Intent(action).setPackage(app.getPackageName())); }
        catch (RuntimeException ignored) { /* The durable queue does not depend on a UI receiver. */ }
    }

    private static final class Run {
        final long generation;
        final long retryEpoch;
        final long stagedEpoch;
        volatile boolean stopRequested;
        Thread thread;
        JobToken owner;
        Run(long generation, long retryEpoch, long stagedEpoch) {
            this.generation = generation;
            this.retryEpoch = retryEpoch;
            this.stagedEpoch = stagedEpoch;
        }
    }

    private static final class JobToken {
        final ProjectPublisher service;
        final JobParameters params;
        boolean stopped;
        boolean finished;
        JobToken(ProjectPublisher service, JobParameters params) { this.service = service; this.params = params; }
    }
}
