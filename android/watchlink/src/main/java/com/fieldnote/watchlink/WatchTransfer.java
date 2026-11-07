package com.fieldnote.watchlink;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/** One recording per Bluetooth connection. The phone replies after durable acceptance. */
public final class WatchTransfer {
    public static final UUID SERVICE_ID = UUID.fromString("fe6e7385-a424-43ae-9517-2f53f66fcf19");
    public static final String SERVICE_NAME = "Fieldnote Voice";
    public static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final int VERSION_MAGIC = 0x464E5731; // FNW1

    private WatchTransfer() {}

    public record Header(UUID id, long startedAtMillis, long endedAtMillis, long byteCount) {}

    public static void writeHeader(DataOutputStream output, Header header) throws IOException {
        validate(header);
        output.writeInt(VERSION_MAGIC);
        output.writeLong(header.id().getMostSignificantBits());
        output.writeLong(header.id().getLeastSignificantBits());
        output.writeLong(header.startedAtMillis());
        output.writeLong(header.endedAtMillis());
        output.writeLong(header.byteCount());
    }

    public static Header readHeader(DataInputStream input) throws IOException {
        if (input.readInt() != VERSION_MAGIC) throw new IOException("Unrecognized Fieldnote voice format.");
        Header header = new Header(new UUID(input.readLong(), input.readLong()),
                input.readLong(), input.readLong(), input.readLong());
        validate(header);
        return header;
    }

    private static void validate(Header header) throws IOException {
        if (header.id() == null || header.startedAtMillis() <= 0 || header.endedAtMillis() < header.startedAtMillis()
                || header.byteCount() <= 0 || header.byteCount() > MAX_BYTES) {
            throw new IOException("Invalid Fieldnote voice recording header.");
        }
    }

    public static void writeResult(DataOutputStream output, boolean accepted) throws IOException {
        output.writeByte(accepted ? 1 : 0);
        output.flush();
    }

    public static boolean readResult(DataInputStream input) throws IOException {
        int result = input.readUnsignedByte();
        if (result > 1) throw new IOException("Invalid Fieldnote voice reply.");
        return result == 1;
    }
}
