package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;

import java.io.IOException;

/** SelectionRequest (30), to a selection's owner, or SelectionNotify (31) back to the requestor */
public class SelectionRequest extends Event {
    private final int timestamp;
    private final int owner;
    private final int requestor;
    private final int selection;
    private final int target;
    private final int property;

    private SelectionRequest(int code, int timestamp, int owner, int requestor, int selection, int target, int property) {
        super(code);
        this.timestamp = timestamp;
        this.owner = owner;
        this.requestor = requestor;
        this.selection = selection;
        this.target = target;
        this.property = property;
    }

    public static SelectionRequest request(int timestamp, int owner, int requestor, int selection, int target, int property) {
        return new SelectionRequest(30, timestamp, owner, requestor, selection, target, property);
    }

    /** The owner didn't convert it (there is none): property None */
    public static SelectionRequest refusedNotify(int timestamp, int requestor, int selection, int target) {
        return new SelectionRequest(31, timestamp, 0, requestor, selection, target, 0);
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(timestamp);
            if (code == 30) outputStream.writeInt(owner);
            outputStream.writeInt(requestor);
            outputStream.writeInt(selection);
            outputStream.writeInt(target);
            outputStream.writeInt(property);
            outputStream.writePad(code == 30 ? 4 : 8);
        }
    }
}
