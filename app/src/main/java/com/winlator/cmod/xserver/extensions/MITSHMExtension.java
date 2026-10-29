package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.GraphicsContext;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadDrawable;
import com.winlator.cmod.xserver.errors.BadGraphicsContext;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadSHMSegment;
import com.winlator.cmod.xserver.errors.BadValue;
import com.winlator.cmod.xserver.requests.DrawRequests;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;
import java.nio.ByteBuffer;

public class MITSHMExtension implements Extension {
    public static final byte MAJOR_OPCODE = -101;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte ATTACH = 1;
        private static final byte DETACH = 2;
        private static final byte PUT_IMAGE = 3;
        private static final byte GET_IMAGE = 4;
    }

    @Override
    public String getName() {
        return "MIT-SHM";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return Byte.MIN_VALUE;
    }

    @Override
    public byte getFirstEventId() {
        return 64;
    }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)1);
            outputStream.writeShort((short)1);
            outputStream.writeShort((short)0);
            outputStream.writeShort((short)0);
            outputStream.writeByte((byte)0);
            // Replies are at least 32 bytes: without the padding, xcb clients (GTK) wait forever
            outputStream.writePad(15);
        }
    }

    private static void attach(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int xid = inputStream.readInt();
        int shmid = inputStream.readInt();
        inputStream.skip(4);
        client.xServer.getSHMSegmentManager().attach(xid, shmid);
    }

    private static void detach(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        client.xServer.getSHMSegmentManager().detach(inputStream.readInt());
    }

    private static void putImage(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        short totalWidth = inputStream.readShort();
        short totalHeight = inputStream.readShort();
        short srcX = inputStream.readShort();
        short srcY = inputStream.readShort();
        short srcWidth = inputStream.readShort();
        short srcHeight = inputStream.readShort();
        short dstX = inputStream.readShort();
        short dstY = inputStream.readShort();
        byte depth = inputStream.readByte();
        inputStream.skip(1); // format
        boolean sendEvent = inputStream.readByte() != 0;
        inputStream.skip(1);
        int shmseg = inputStream.readInt();
        int offset = inputStream.readInt();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        ByteBuffer data = client.xServer.getSHMSegmentManager().getData(shmseg);
        if (data == null) throw new BadSHMSegment(shmseg);

        if (graphicsContext.getFunction() != GraphicsContext.Function.COPY) {
            throw new UnsupportedOperationException("GC Function other than COPY is not supported.");
        }

        // The image starts at offset in the segment (cairo packs several images in one)
        drawable.drawImage(srcX, srcY, dstX, dstY, srcWidth, srcHeight, depth, slice(data, offset), totalWidth, totalHeight);
        if (sendEvent) sendCompletion(client, drawableId, PUT_IMAGE_MINOR, shmseg, offset);
    }

    private static final short PUT_IMAGE_MINOR = ClientOpcodes.PUT_IMAGE;

    private static ByteBuffer slice(ByteBuffer data, int offset) {
        ByteBuffer view = data.duplicate();
        view.position(offset);
        return view.slice().order(data.order());
    }

    /** ShmCompletion, which clients wait for before reusing the segment */
    private static void sendCompletion(XClient client, int drawableId, short minorOpcode, int shmseg, int offset) throws IOException {
        XOutputStream outputStream = client.getOutputStream();
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte((byte)64);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(drawableId);
            outputStream.writeShort(minorOpcode);
            outputStream.writeByte(MAJOR_OPCODE);
            outputStream.writeByte((byte)0);
            outputStream.writeInt(shmseg);
            outputStream.writeInt(offset);
            outputStream.writePad(12);
        }
    }

    private static void getImage(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        short x = inputStream.readShort();
        short y = inputStream.readShort();
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        inputStream.skip(4); // plane mask
        inputStream.skip(4); // format and padding
        int shmseg = inputStream.readInt();
        int offset = inputStream.readInt();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        ByteBuffer segment = client.xServer.getSHMSegmentManager().getData(shmseg);
        if (segment == null) throw new BadSHMSegment(shmseg);

        boolean isWindow = client.xServer.pixmapManager.getPixmap(drawableId) == null;
        ByteBuffer image = drawable.getImage(x, y, width, height);
        if (isWindow) DrawRequests.makeOpaque(image);
        int size = image.limit();
        ByteBuffer dst = slice(segment, offset);
        if (size > dst.capacity()) throw new BadValue(size);
        dst.put(image);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(drawable.visual.depth);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(isWindow ? drawable.visual.id : 0);
            outputStream.writeInt(size);
            outputStream.writePad(16);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.ATTACH :
                try (XLock lock = client.xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER)) {
                    attach(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.DETACH :
                try (XLock lock = client.xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER)) {
                    detach(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.PUT_IMAGE :
                try (XLock lock = client.xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                    putImage(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.GET_IMAGE :
                try (XLock lock = client.xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                    getImage(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }
}
