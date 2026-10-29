package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;

import java.io.IOException;

/** An event from a SendEvent request, delivered as the client wrote it. */
public class RawEvent extends Event {
    private static final byte KEYMAP_NOTIFY = 11;
    private final byte[] data;

    public RawEvent(byte[] data) {
        super(data[0]);
        this.data = data;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            // The server marks it as sent by a client and, like for any event, fills in the
            // receiver's sequence number: Xlib aborts on the sender's stale one
            outputStream.writeByte((byte)(data[0] | 0x80));
            if ((data[0] & 0x7f) == KEYMAP_NOTIFY) {
                outputStream.write(data, 1, 31);
            }
            else {
                outputStream.writeByte(data[1]);
                outputStream.writeShort(sequenceNumber);
                outputStream.write(data, 4, 28);
            }
        }
    }
}
