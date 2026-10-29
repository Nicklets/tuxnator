package com.winlator.cmod.xserver.requests;

import static com.winlator.cmod.xserver.Keyboard.KEYSYMS_PER_KEYCODE;
import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Keyboard;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class KeyboardRequests {
    public static void getKeyboardMapping(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int firstKeycode = inputStream.readUnsignedByte();
        int count = inputStream.readUnsignedByte();
        inputStream.skip(2);

        // KEYSYMS_PER_KEYCODE keysyms for each of the count keycodes; the table doesn't cover
        // the whole keycode range (window managers ask for all of it), the rest is NoSymbol
        int[] keysyms = client.xServer.keyboard.keysyms;
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(KEYSYMS_PER_KEYCODE);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(count * KEYSYMS_PER_KEYCODE);
            outputStream.writePad(24);

            int i = (firstKeycode - Keyboard.MIN_KEYCODE) * KEYSYMS_PER_KEYCODE;
            for (int n = count * KEYSYMS_PER_KEYCODE; n > 0; n--, i++) {
                outputStream.writeInt(i >= 0 && i < keysyms.length ? keysyms[i] : 0);
            }
        }
    }

    // Fixed values: auto-repeat on, no LEDs, the X server's default bell
    public static void getKeyboardControl(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)1); // global auto-repeat
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(5);
            outputStream.writeInt(0); // LED mask
            outputStream.writeByte((byte)0); // key click percent
            outputStream.writeByte((byte)50); // bell percent
            outputStream.writeShort((short)400); // bell pitch
            outputStream.writeShort((short)100); // bell duration
            outputStream.writePad(2);
            for (int i = 0; i < 32; i++) outputStream.writeByte((byte)0xff); // auto-repeating keys
        }
    }

    public static void getPointerControl(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)2); // acceleration numerator
            outputStream.writeShort((short)1); // acceleration denominator
            outputStream.writeShort((short)4); // threshold
            outputStream.writePad(18);
        }
    }

    public static void getModifierMapping(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)1);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(2);
            outputStream.writePad(24);
            outputStream.writePad(8);
        }
    }
}