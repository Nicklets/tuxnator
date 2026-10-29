package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Window;

import java.io.IOException;

/** FocusIn (9) / FocusOut (10). SDL2 uses them to track keyboard focus. */
public class FocusChange extends Event {
    public static final byte NOTIFY_NONLINEAR = 3;
    public static final byte NOTIFY_POINTER_ROOT = 6;
    public static final byte MODE_NORMAL = 0;

    private final Window window;
    private final byte detail;

    public FocusChange(boolean focusIn, Window window, byte detail) {
        super(focusIn ? 9 : 10);
        this.window = window;
        this.detail = detail;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writeByte(detail);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(window.id);
            outputStream.writeByte(MODE_NORMAL);
            outputStream.writePad(23);
        }
    }
}
