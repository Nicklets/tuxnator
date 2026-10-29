package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;

/**
 * Minimal XFIXES. Mesa's X11 WSI needs the extension to exist for its DRI3 path (it creates
 * regions for Present's update areas, and xcb drops the connection when a request targets an
 * absent extension). Regions, selection/cursor notifications and pointer barriers are accepted
 * and ignored: Present here always updates the whole window.
 */
public class XFixesExtension implements Extension {
    public static final byte MAJOR_OPCODE = -109;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte GET_CURSOR_IMAGE = 4;
        private static final byte FETCH_REGION = 19;
        private static final byte GET_CURSOR_NAME = 24;
        private static final byte GET_CURSOR_IMAGE_AND_NAME = 25;
    }

    @Override
    public String getName() {
        return "XFIXES";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    // BadRegion; must not overlap GLX's errors (see GLXError.BASE_ERROR_CODE)
    @Override
    public byte getFirstErrorId() {
        return (byte)150;
    }

    // SelectionNotify/CursorNotify; must not overlap other extensions (see GLXExtension.getFirstEventId)
    @Override
    public byte getFirstEventId() {
        return 103;
    }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        client.skipRequest();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(5);
            outputStream.writeInt(0);
            outputStream.writePad(16);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        switch (client.getRequestData()) {
            case ClientOpcodes.QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_CURSOR_IMAGE:
            case ClientOpcodes.FETCH_REGION:
            case ClientOpcodes.GET_CURSOR_NAME:
            case ClientOpcodes.GET_CURSOR_IMAGE_AND_NAME:
                client.skipRequest();
                throw new BadImplementation();
            default:
                // Every other request has no reply
                client.skipRequest();
                break;
        }
    }
}
