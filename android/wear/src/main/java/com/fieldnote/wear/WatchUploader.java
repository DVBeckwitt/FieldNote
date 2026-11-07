package com.fieldnote.wear;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.fieldnote.watchlink.WatchTransfer;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class WatchUploader {
    private static final String PREFERENCES = "watch_link";
    private static final String PHONE_ADDRESS = "phone_address";
    private static final int TRANSFER_OVERHEAD_SECONDS = 90;
    private static final int TRANSFER_BYTES_PER_SECOND = 32 * 1024;
    private static final ExecutorService UPLOADS = Executors.newSingleThreadExecutor();
    private static final ScheduledThreadPoolExecutor TIMEOUTS = new ScheduledThreadPoolExecutor(1);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static {
        TIMEOUTS.setRemoveOnCancelPolicy(true);
    }

    record Phone(String address, String name) {}

    record Result(int sent, int pending, String error) {}

    private WatchUploader() {}

    static boolean hasPermission(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission") // Checked below, with revocation handled as well.
    static List<Phone> bondedPhones(Context context) {
        BluetoothAdapter adapter = adapter(context);
        if (adapter == null || !hasPermission(context)) return List.of();
        List<Phone> phones = new ArrayList<>();
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                String name = device.getName();
                String address = device.getAddress();
                phones.add(new Phone(address, name == null || name.trim().isEmpty() ? address : name));
            }
        } catch (SecurityException denied) {
            return List.of();
        }
        phones.sort(Comparator.comparing(Phone::name, String.CASE_INSENSITIVE_ORDER));
        return phones;
    }

    static String phoneAddress(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(PHONE_ADDRESS, null);
    }

    static void setPhone(Context context, String address) {
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            throw new IllegalArgumentException("Invalid Bluetooth device address");
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(PHONE_ADDRESS, address).apply();
    }

    static void sendCapture(Context context, WatchRecordingQueue.Capture capture, Consumer<Result> callback) {
        Context app = context.getApplicationContext();
        String address = phoneAddress(app);
        UPLOADS.execute(() -> {
            Result result = upload(app, address, capture);
            MAIN.post(() -> callback.accept(result));
        });
    }

    @SuppressLint("MissingPermission") // Checked before touching Bluetooth and handled if revoked.
    private static Result upload(Context context, String address, WatchRecordingQueue.Capture capture) {
        int pending = WatchRecordingQueue.pending(context).size();
        // Each send handles one chosen clip. An older clip can wait if the project changed.
        if (!capture.file().isFile()) return new Result(0, pending, null);
        if (!hasPermission(context)) {
            return new Result(0, pending, "Allow Nearby devices to send recordings.");
        }
        if (address == null) {
            return new Result(0, pending, "Choose your paired phone to send recordings.");
        }
        if (!address.equals(phoneAddress(context))) {
            return new Result(0, pending, "Phone changed. Choose the recording to retry.");
        }
        try {
            BluetoothAdapter adapter = adapter(context);
            if (adapter == null || !adapter.isEnabled()) {
                return new Result(0, pending, "Turn on Bluetooth to send recordings.");
            }
            BluetoothDevice phone = adapter.getRemoteDevice(address);
            if (phone.getBondState() != BluetoothDevice.BOND_BONDED) {
                return new Result(0, pending, "Choose a phone paired to this watch.");
            }
            if (!send(phone, capture)) {
                return new Result(0, pending, "Phone could not save the recording. Check Fieldnote and retry.");
            }
        } catch (SecurityException denied) {
            return new Result(0, pending, "Allow Nearby devices to send recordings.");
        } catch (IOException | IllegalArgumentException failed) {
            return new Result(0, pending, "Could not send to Fieldnote. Check the phone connection and retry.");
        }
        if (!capture.file().delete() && capture.file().exists()) {
            return new Result(1, pending, "Phone saved the recording. The watch copy is still queued.");
        }
        return new Result(1, pending - 1, null);
    }

    @SuppressLint("MissingPermission") // The caller checks the permission and handles revocation.
    private static boolean send(BluetoothDevice phone, WatchRecordingQueue.Capture capture) throws IOException {
        try (FileInputStream file = new FileInputStream(capture.file());
             BluetoothSocket socket = phone.createRfcommSocketToServiceRecord(WatchTransfer.SERVICE_ID)) {
            long size = file.getChannel().size();
            long deadlineSeconds = TRANSFER_OVERHEAD_SECONDS + size / TRANSFER_BYTES_PER_SECOND;
            ScheduledFuture<?> timeout = TIMEOUTS.schedule(() -> {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Closing a broken connection can fail after the reader has already stopped.
                }
            }, deadlineSeconds, TimeUnit.SECONDS);
            try {
                socket.connect();
                DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                WatchTransfer.writeHeader(output, new WatchTransfer.Header(capture.id(), capture.startedAtMillis(),
                        capture.endedAtMillis(), size));
                byte[] buffer = new byte[8192];
                long remaining = size;
                while (remaining > 0) {
                    int read = file.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                    if (read == -1) throw new EOFException("The watch recording ended before its reported length");
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
                output.flush();
                return WatchTransfer.readResult(new DataInputStream(socket.getInputStream()));
            } finally {
                timeout.cancel(false);
            }
        }
    }

    private static BluetoothAdapter adapter(Context context) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }
}
