package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Window;

import java.io.IOException;

/** XI2 Enter/Leave/FocusIn/FocusOut (xXIEnterEvent) */
public class XIEnterEvent extends Event {
    private static final int GENERIC_EVENT_CODE = 35;
    public static final byte MODE_NORMAL = 0;
    public static final byte MODE_GRAB = 1;
    public static final byte MODE_UNGRAB = 2;
    public static final byte DETAIL_ANCESTOR = 0;
    public static final byte DETAIL_INFERIOR = 2;
    public static final byte DETAIL_NONLINEAR = 3;

    private final byte extensionOpcode;
    private final short evtype;
    private final int deviceId;
    private final int sourceId;
    private final byte mode;
    private final byte detail;
    private final Window root;
    private final Window event;
    private final short rootX;
    private final short rootY;
    private final short eventX;
    private final short eventY;
    private final boolean focus;
    private final int buttons;
    private final int modifiers;
    private final int timestamp;

    public XIEnterEvent(byte extensionOpcode, int evtype, int deviceId, int sourceId, byte mode, byte detail, Window root, Window event,
                        short rootX, short rootY, short eventX, short eventY, boolean focus, int buttons, int modifiers) {
        super(GENERIC_EVENT_CODE);
        this.extensionOpcode = extensionOpcode;
        this.evtype = (short)evtype;
        this.deviceId = deviceId;
        this.sourceId = sourceId;
        this.mode = mode;
        this.detail = detail;
        this.root = root;
        this.event = event;
        this.rootX = rootX;
        this.rootY = rootY;
        this.eventX = eventX;
        this.eventY = eventY;
        this.focus = focus;
        this.buttons = buttons;
        this.modifiers = modifiers;
        this.timestamp = (int)System.currentTimeMillis();
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writeByte(extensionOpcode);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(11); // 76 bytes in all: (76 - 32) / 4
            outputStream.writeShort(evtype);
            outputStream.writeShort((short)deviceId);
            outputStream.writeInt(timestamp);
            outputStream.writeShort((short)sourceId);
            outputStream.writeByte(mode);
            outputStream.writeByte(detail);
            outputStream.writeInt(root.id);
            outputStream.writeInt(event.id);
            outputStream.writeInt(0); // child
            outputStream.writeInt(rootX << 16);
            outputStream.writeInt(rootY << 16);
            outputStream.writeInt(eventX << 16);
            outputStream.writeInt(eventY << 16);
            outputStream.writeByte((byte)1); // same_screen
            outputStream.writeByte((byte)(focus ? 1 : 0));
            outputStream.writeShort((short)1); // buttons_len
            outputStream.writeInt(modifiers);
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            outputStream.writeInt(modifiers);
            outputStream.writeInt(0);
            outputStream.writeInt(buttons);
        }
    }
}
