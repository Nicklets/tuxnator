package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Window;

import java.io.IOException;

/**
 * XI2 KeyPress/KeyRelease/ButtonPress/ButtonRelease/Motion (xXIDeviceEvent): what GTK4 takes its
 * input from, since it has no core event path. No valuators, only the event coordinates.
 */
public class XIDeviceEvent extends Event {
    private static final int GENERIC_EVENT_CODE = 35;

    private final byte extensionOpcode;
    private final short evtype;
    private final int deviceId;
    private final int sourceId;
    private final int detail;
    private final Window root;
    private final Window event;
    private final Window child;
    private final short rootX;
    private final short rootY;
    private final short eventX;
    private final short eventY;
    private final int buttons;
    private final int modifiers;
    private final int flags;
    private final int timestamp;

    public XIDeviceEvent(byte extensionOpcode, int evtype, int deviceId, int sourceId, int detail, Window root, Window event, Window child,
                         short rootX, short rootY, short eventX, short eventY, int buttons, int modifiers, int flags) {
        super(GENERIC_EVENT_CODE);
        this.extensionOpcode = extensionOpcode;
        this.evtype = (short)evtype;
        this.deviceId = deviceId;
        this.sourceId = sourceId;
        this.detail = detail;
        this.root = root;
        this.event = event;
        this.child = child;
        this.rootX = rootX;
        this.rootY = rootY;
        this.eventX = eventX;
        this.eventY = eventY;
        this.buttons = buttons;
        this.modifiers = modifiers;
        this.flags = flags;
        this.timestamp = (int)System.currentTimeMillis();
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writeByte(extensionOpcode);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(13); // 84 bytes in all: (84 - 32) / 4
            outputStream.writeShort(evtype);
            outputStream.writeShort((short)deviceId);
            outputStream.writeInt(timestamp);
            outputStream.writeInt(detail);
            outputStream.writeInt(root.id);
            outputStream.writeInt(event.id);
            outputStream.writeInt(child != null ? child.id : 0);
            // FP16.16 coordinates
            outputStream.writeInt(rootX << 16);
            outputStream.writeInt(rootY << 16);
            outputStream.writeInt(eventX << 16);
            outputStream.writeInt(eventY << 16);
            outputStream.writeShort((short)1); // buttons_len
            outputStream.writeShort((short)0); // valuators_len
            outputStream.writeShort((short)sourceId);
            outputStream.writeShort((short)0);
            outputStream.writeInt(flags);
            // modifiers: base, latched, locked, effective
            outputStream.writeInt(modifiers);
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            outputStream.writeInt(modifiers);
            // group: base, latched, locked, effective
            outputStream.writeInt(0);
            outputStream.writeInt(buttons);
        }
    }
}
