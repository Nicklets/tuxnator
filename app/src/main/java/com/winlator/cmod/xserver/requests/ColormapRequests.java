package com.winlator.cmod.xserver.requests;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.XClient;

import java.io.IOException;

/**
 * Colormap requests for the only visual, 24-bit TrueColor (0xRRGGBB pixels): every color is
 * "allocated" by computing its pixel, nothing is stored. Window managers like Openbox allocate
 * all their theme colors this way.
 */
public abstract class ColormapRequests {
    public static void allocColor(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(4); // colormap
        int red = inputStream.readUnsignedShort();
        int green = inputStream.readUnsignedShort();
        int blue = inputStream.readUnsignedShort();
        inputStream.skip(2);

        // The closest TrueColor value, with each 8-bit channel scaled back to 16 bits
        int r = red >> 8, g = green >> 8, b = blue >> 8;
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)(r * 0x101));
            outputStream.writeShort((short)(g * 0x101));
            outputStream.writeShort((short)(b * 0x101));
            outputStream.writePad(2);
            outputStream.writeInt((r << 16) | (g << 8) | b);
            outputStream.writePad(12);
        }
    }

    public static void queryColors(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(4); // colormap
        int count = client.getRemainingRequestLength() / 4;
        int[] pixels = new int[count];
        for (int i = 0; i < count; i++) pixels[i] = inputStream.readInt();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(count * 2);
            outputStream.writeShort((short)count);
            outputStream.writePad(22);
            for (int pixel : pixels) {
                outputStream.writeShort((short)(((pixel >> 16) & 0xff) * 0x101));
                outputStream.writeShort((short)(((pixel >> 8) & 0xff) * 0x101));
                outputStream.writeShort((short)((pixel & 0xff) * 0x101));
                outputStream.writePad(2);
            }
        }
    }
}
