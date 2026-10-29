package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadValue;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;

/**
 * XTEST: synthetic input from clients (xdotool, on-screen keyboards, test automation), injected
 * like the app's own touch and keyboard input.
 */
public class XTestExtension implements Extension {
    public static final byte MAJOR_OPCODE = -110;

    private static abstract class ClientOpcodes {
        private static final byte GET_VERSION = 0;
        private static final byte COMPARE_CURSOR = 1;
        private static final byte FAKE_INPUT = 2;
        private static final byte GRAB_CONTROL = 3;
    }

    private static final byte KEY_PRESS = 2;
    private static final byte KEY_RELEASE = 3;
    private static final byte BUTTON_PRESS = 4;
    private static final byte BUTTON_RELEASE = 5;
    private static final byte MOTION_NOTIFY = 6;

    private final XServer xServer;

    public XTestExtension(XServer xServer) {
        this.xServer = xServer;
    }

    @Override public String getName() { return "XTEST"; }
    @Override public byte getMajorOpcode() { return MAJOR_OPCODE; }
    @Override public byte getFirstErrorId() { return 0; }
    @Override public byte getFirstEventId() { return 0; }

    private static void getVersion(XClient client, XInputStream in, XOutputStream out) throws IOException {
        in.skip(4);
        try (XStreamLock lock = out.lock()) {
            out.writeByte(RESPONSE_CODE_SUCCESS);
            out.writeByte((byte)2);
            out.writeShort(client.getSequenceNumber());
            out.writeInt(0);
            out.writeShort((short)2);
            out.writePad(22);
        }
    }

    private static void compareCursor(XClient client, XInputStream in, XOutputStream out) throws IOException {
        in.skip(8);
        try (XStreamLock lock = out.lock()) {
            out.writeByte(RESPONSE_CODE_SUCCESS);
            out.writeByte((byte)0);
            out.writeShort(client.getSequenceNumber());
            out.writeInt(0);
            out.writePad(24);
        }
    }

    private void fakeInput(XClient client, XInputStream in) throws XRequestError {
        byte type = in.readByte();
        int detail = in.readUnsignedByte();
        in.skip(2 + 4 + 4 + 8); // pad, time (always "now" here), root, pad
        short rootX = in.readShort();
        short rootY = in.readShort();
        client.skipRequest();

        switch (type) {
            case KEY_PRESS:
            case KEY_RELEASE:
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                    if (type == KEY_PRESS) xServer.keyboard.setKeyPress((byte)detail, 0);
                    else xServer.keyboard.setKeyRelease((byte)detail);
                }
                break;
            case BUTTON_PRESS:
            case BUTTON_RELEASE: {
                if (detail < 1 || detail > Pointer.Button.values().length) throw new BadValue(detail);
                Pointer.Button button = Pointer.Button.values()[detail - 1];
                if (type == BUTTON_PRESS) xServer.injectPointerButtonPress(button);
                else xServer.injectPointerButtonRelease(button);
                break;
            }
            case MOTION_NOTIFY:
                // detail 0 moves to the root coordinates, 1 moves by them
                if (detail == 0) xServer.injectPointerMove(rootX, rootY);
                else xServer.injectPointerMoveDelta(rootX, rootY);
                break;
            default:
                throw new BadValue(type);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream in, XOutputStream out) throws IOException, XRequestError {
        switch (client.getRequestData()) {
            case ClientOpcodes.GET_VERSION:
                getVersion(client, in, out);
                break;
            case ClientOpcodes.COMPARE_CURSOR:
                compareCursor(client, in, out);
                break;
            case ClientOpcodes.FAKE_INPUT:
                fakeInput(client, in);
                break;
            case ClientOpcodes.GRAB_CONTROL:
                client.skipRequest();
                break;
            default:
                throw new BadImplementation();
        }
    }
}
