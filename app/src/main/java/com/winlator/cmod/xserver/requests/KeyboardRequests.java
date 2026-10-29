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