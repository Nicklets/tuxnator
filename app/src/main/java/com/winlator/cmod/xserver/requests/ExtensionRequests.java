package com.winlator.cmod.xserver.requests;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import android.util.SparseArray;

import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.XRequestError;
import com.winlator.cmod.xserver.extensions.Extension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public abstract class ExtensionRequests {
    public static void queryExtension(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        short length = inputStream.readShort();
        inputStream.skip(2);
        String name = inputStream.readString8(length);
        Extension extension = client.xServer.getExtensionByName(name);        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);

            if (extension != null) {
                outputStream.writeByte((byte)1);
                outputStream.writeByte(extension.getMajorOpcode());
                outputStream.writeByte(extension.getFirstEventId());
                outputStream.writeByte(extension.getFirstErrorId());
                outputStream.writePad(20);
            }
            else {
                outputStream.writeByte((byte)0);
                outputStream.writePad(23);
            }
        }
    }

    /** QueryBestSize (cursor/tile/stipple): any size is fine for this server, so echo it back. */
    public static void queryBestSize(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(4);
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort(width);
            outputStream.writeShort(height);
            outputStream.writePad(20);
        }
    }

    public static void listExtensions(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        SparseArray<Extension> extensions = client.xServer.extensions;
        // LISTofSTR: each name is a length byte followed by its bytes, padded only at the end
        ByteArrayOutputStream names = new ByteArrayOutputStream();
        for (int i = 0; i < extensions.size(); i++) {
            byte[] name = extensions.valueAt(i).getName().getBytes(XServer.LATIN1_CHARSET);
            names.write(name.length);
            names.write(name, 0, name.length);
        }
        int pad = -names.size() & 3;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)extensions.size());
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((names.size() + pad) / 4);
            outputStream.writePad(24);
            outputStream.write(names.toByteArray());
            outputStream.writePad(pad);
        }
    }
}
