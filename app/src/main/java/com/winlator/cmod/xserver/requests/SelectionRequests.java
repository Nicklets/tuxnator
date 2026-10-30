package com.winlator.cmod.xserver.requests;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Atom;
import com.winlator.cmod.xserver.SelectionManager;
import com.winlator.cmod.xserver.events.SelectionRequest;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.errors.BadAtom;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class SelectionRequests {
    public static void setSelectionOwner(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int atom = inputStream.readInt();
        int timestamp = inputStream.readInt();

        // None gives the selection up (GTK does it when clearing the clipboard)
        Window owner = null;
        if (windowId != 0) {
            owner = client.xServer.windowManager.getWindow(windowId);
            if (owner == null) throw new BadWindow(windowId);
        }
        if (!Atom.isValid(atom)) throw new BadAtom(atom);

        client.xServer.selectionManager.setSelection(atom, owner, client, timestamp);
    }

    /**
     * ConvertSelection: asks the owner for the selection in a format (SelectionRequest), or tells
     * the requestor right away that there is nothing to convert (SelectionNotify, property None).
     */
    public static void convertSelection(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int requestorId = inputStream.readInt();
        int atom = inputStream.readInt();
        int target = inputStream.readInt();
        int property = inputStream.readInt();
        int timestamp = inputStream.readInt();

        Window requestor = client.xServer.windowManager.getWindow(requestorId);
        if (requestor == null) throw new BadWindow(requestorId);
        if (!Atom.isValid(atom)) throw new BadAtom(atom);
        if (!Atom.isValid(target)) throw new BadAtom(target);

        SelectionManager.Selection selection = client.xServer.selectionManager.getSelection(atom);
        XClient ownerClient = selection.getClient();
        if (selection.owner != null && ownerClient != null) {
            ownerClient.sendEvent(SelectionRequest.request(timestamp, selection.owner.id, requestorId, atom, target, property));
        }
        else client.sendEvent(SelectionRequest.refusedNotify(timestamp, requestorId, atom, target));
    }

    public static void getSelectionOwner(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int atom = inputStream.readInt();
        if (!Atom.isValid(atom)) throw new BadAtom(atom);
        Window owner = client.xServer.selectionManager.getSelection(atom).owner;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(owner != null ? owner.id : 0);
            outputStream.writePad(20);
        }
    }
}
