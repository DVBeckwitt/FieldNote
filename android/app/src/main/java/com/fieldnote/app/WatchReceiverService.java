package com.fieldnote.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.fieldnote.watchlink.WatchTransfer;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class WatchReceiverService extends Service {
    public static final String ACTION_STATUS_CHANGED = "com.fieldnote.app.WATCH_RECEIVER_STATUS";

    private static final String CHANNEL = "fieldnote_watch_receiver";
    private static final int NOTIFICATION_ID = 1005;
    private static final String ADDRESS = "watchAddress";
    private static final String STOP = "com.fieldnote.app.WATCH_RECEIVER_STOP";
    private static final String INTERNAL = "com.fieldnote.app.INTERNAL";
    private static final String OFF = "Watch capture is off.";
    private static final String STARTING = "Starting watch capture…";
    private static final String READY = "Ready to receive recordings from your watch.";
    private static volatile String status = OFF;
    private static volatile boolean listening;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;

    public static void start(Context context, String address) {
        if (!hasBluetoothPermission(context)) {
            updateStatus(context, "Allow Nearby Devices in Android settings to receive watch recordings.");
            throw new SecurityException("Nearby Devices permission is required.");
        }
        if (!listening) updateStatus(context, STARTING);
        try {
            context.startForegroundService(new Intent(context, WatchReceiverService.class)
                    .putExtra(ADDRESS, address));
        } catch (SecurityException | IllegalStateException error) {
            updateStatus(context, "Android could not start watch capture. Check Nearby Devices permission.");
            throw error;
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, WatchReceiverService.class));
        if (!listening) updateStatus(context, OFF);
    }

    public static String status() { return status; }

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                CHANNEL, "Watch recordings", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @SuppressLint("MissingPermission") // Check below and handle revocation during setup or transfer.
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!hasBluetoothPermission(this)) {
            fail("Allow Nearby Devices in Android settings to receive watch recordings.");
            return START_NOT_STICKY;
        }
        String address = intent.getStringExtra(ADDRESS);
        if (session != null && session.address.equals(address)) return START_REDELIVER_INTENT;
        try {
            Notification notification = notification(STARTING);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (SecurityException | IllegalStateException error) {
            fail("Android could not start the watch receiver. Open Fieldnote and try again.");
            return START_NOT_STICKY;
        }

        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        try {
            if (adapter == null || !adapter.isEnabled()) {
                fail("Turn on Bluetooth to receive watch recordings.");
                return START_NOT_STICKY;
            }
            if (!BluetoothAdapter.checkBluetoothAddress(address)
                    || adapter.getRemoteDevice(address).getBondState() != BluetoothDevice.BOND_BONDED) {
                fail("Select a watch already paired in Android Bluetooth settings.");
                return START_NOT_STICKY;
            }
        } catch (SecurityException error) {
            fail("Allow Nearby Devices in Android settings to receive watch recordings.");
            return START_NOT_STICKY;
        }

        if (session != null) session.close();
        session = new Session(adapter, address);
        listening = true;
        updateStatus(this, STARTING);
        new Thread(session, "Fieldnote watch receiver").start();
        return START_REDELIVER_INTENT;
    }

    @Override public void onDestroy() {
        if (session != null) {
            session.close();
            session = null;
            listening = false;
            updateStatus(this, OFF);
        }
        super.onDestroy();
    }

    private static boolean hasBluetoothPermission(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void updateStatus(Context context, String text) {
        status = text;
        context.sendBroadcast(new Intent(ACTION_STATUS_CHANGED).setPackage(context.getPackageName()), INTERNAL);
    }

    private void fail(String message) {
        if (session != null) session.close();
        session = null;
        listening = false;
        updateStatus(this, message);
        stopSelf();
    }

    private Notification notification(String text) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION_ID,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);
        PendingIntent turnOff = PendingIntent.getService(this, NOTIFICATION_ID,
                new Intent(this, WatchReceiverService.class).setAction(STOP), flags);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_shortcut_record)
                .setContentTitle("Fieldnote watch capture")
                .setContentText(text)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Turn off", turnOff).build())
                .build();
    }

    private final class Session implements Runnable, Closeable {
        private final BluetoothAdapter adapter;
        private final String address;
        private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1);
        private BluetoothServerSocket listener;
        private BluetoothSocket peer;
        private boolean closed;

        Session(BluetoothAdapter adapter, String address) {
            this.adapter = adapter;
            this.address = address;
            deadlines.setRemoveOnCancelPolicy(true);
        }

        @SuppressLint("MissingPermission") // The service checks BLUETOOTH_CONNECT before starting.
        @Override public void run() {
            try (BluetoothServerSocket server = adapter.listenUsingRfcommWithServiceRecord(
                    WatchTransfer.SERVICE_NAME, WatchTransfer.SERVICE_ID)) {
                synchronized (this) {
                    if (closed) return;
                    listener = server;
                }
                report(READY);
                while (true) {
                    BluetoothSocket socket = server.accept();
                    try {
                        synchronized (this) {
                            if (closed) return;
                            peer = socket;
                        }
                        BluetoothDevice device = socket.getRemoteDevice();
                        if (address.equals(device.getAddress()) && device.getBondState() == BluetoothDevice.BOND_BONDED) {
                            receive(socket);
                        }
                    } finally {
                        closeQuietly(socket);
                        synchronized (this) { peer = null; }
                    }
                }
            } catch (SecurityException error) {
                reportFailure("Nearby Devices permission is required to receive watch recordings.");
            } catch (IOException error) {
                reportFailure("Bluetooth watch receiver stopped. Check Bluetooth and enable watch capture again.");
            } finally {
                deadlines.shutdownNow();
            }
        }

        private void receive(BluetoothSocket socket) {
            ScheduledFuture<?> deadline = closeAfter(socket, 15);
            try {
                DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                WatchTransfer.Header header = WatchTransfer.readHeader(input);
                deadline.cancel(false);
                deadline = closeAfter(socket, 45 + header.byteCount() / (32 * 1024));
                try {
                    WatchRecordingImporter.importRecording(WatchReceiverService.this, header.id().toString(),
                            header.startedAtMillis(), header.endedAtMillis(), header.byteCount(), input);
                } catch (IOException | SecurityException error) {
                    report("Watch recording wasn’t accepted. Open Fieldnote and check the current project.");
                    try { WatchTransfer.writeResult(output, false); }
                    catch (IOException ignored) { }
                    return;
                }
                report("Latest watch recording received.");
                try { WatchTransfer.writeResult(output, true); }
                catch (IOException ignored) { /* A retry will be acknowledged by its durable receipt. */ }
            } catch (IOException error) {
                report("Watch transfer interrupted. The watch can retry the recording.");
            } finally {
                deadline.cancel(false);
            }
        }

        private ScheduledFuture<?> closeAfter(BluetoothSocket socket, long seconds) {
            return deadlines.schedule(() -> closeQuietly(socket), seconds, TimeUnit.SECONDS);
        }

        private void report(String message) {
            main.post(() -> {
                if (session != this) return;
                updateStatus(WatchReceiverService.this, message);
                if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED) {
                    getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(message));
                }
            });
        }

        private void reportFailure(String message) {
            main.post(() -> {
                if (session == this) fail(message);
            });
        }

        @Override public void close() {
            BluetoothServerSocket server;
            BluetoothSocket socket;
            synchronized (this) {
                closed = true;
                server = listener;
                socket = peer;
            }
            closeQuietly(server);
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(Closeable resource) {
        if (resource == null) return;
        try { resource.close(); }
        catch (IOException ignored) { }
    }
}
