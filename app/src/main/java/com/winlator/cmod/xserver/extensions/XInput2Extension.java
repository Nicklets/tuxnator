package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Keyboard;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadValue;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;
import com.winlator.cmod.xserver.events.XIDeviceEvent;
import com.winlator.cmod.xserver.events.XIEnterEvent;
import com.winlator.cmod.xserver.events.XIRawButtonPressNotify;
import com.winlator.cmod.xserver.events.XIRawButtonReleaseNotify;
import com.winlator.cmod.xserver.events.XIRawMotionNotify;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * XInput 2.2 with one master pointer/keyboard pair (and a slave device for each). Clients that
 * select XI2 device events on a window get them there instead of core events: GTK4 has no core
 * input path at all. Raw events go to every client that selected them, as before.
 */
public class XInput2Extension implements Extension {
    public static final byte MAJOR_OPCODE = -107;
    // XI 1.x reserves 17 event codes; 66 collided with RANDR's RRNotify (65+1)
    private static final byte FIRST_EVENT_ID = 67;
    private static final byte FIRST_ERROR_ID = (byte)144;

    private static final int XI_MAJOR = 2;
    private static final int XI_MINOR = 2;
    private static final int XI_ALL_DEVICES = 0;
    private static final int XI_ALL_MASTER_DEVICES = 1;
    public static final int MASTER_POINTER_ID = 2;
    public static final int MASTER_KEYBOARD_ID = 3;
    private static final int SLAVE_POINTER_ID = 4;
    private static final int SLAVE_KEYBOARD_ID = 5;
    private static final int XI_MASTER_POINTER = 1;
    private static final int XI_MASTER_KEYBOARD = 2;
    private static final int XI_SLAVE_POINTER = 3;
    private static final int XI_SLAVE_KEYBOARD = 4;
    private static final int XI_KEY_CLASS = 0;
    private static final int XI_BUTTON_CLASS = 1;
    private static final int XI_VALUATOR_CLASS = 2;

    public static final int XI_KEY_PRESS = 2;
    public static final int XI_KEY_RELEASE = 3;
    public static final int XI_BUTTON_PRESS = 4;
    public static final int XI_BUTTON_RELEASE = 5;
    public static final int XI_MOTION = 6;
    public static final int XI_ENTER = 7;
    public static final int XI_LEAVE = 8;
    public static final int XI_FOCUS_IN = 9;
    public static final int XI_FOCUS_OUT = 10;
    private static final int XI_RAW_BUTTON_PRESS = 15;
    private static final int XI_RAW_BUTTON_RELEASE = 16;
    private static final int XI_RAW_MOTION = 17;

    private final List<Selection> selections = new CopyOnWriteArrayList<>();
    // Active pointer/keyboard grabs (XIGrabDevice, and the implicit one of a button press)
    private Grab pointerGrab;
    private Grab keyboardGrab;

    private static abstract class ClientOpcodes {
        private static final byte GET_EXTENSION_VERSION = 1;
        private static final byte QUERY_POINTER = 40;
        private static final byte WARP_POINTER = 41;
        private static final byte CHANGE_CURSOR = 42;
        private static final byte CHANGE_HIERARCHY = 43;
        private static final byte SET_CLIENT_POINTER = 44;
        private static final byte GET_CLIENT_POINTER = 45;
        private static final byte SELECT_EVENTS = 46;
        private static final byte QUERY_VERSION = 47;
        private static final byte QUERY_DEVICE = 48;
        private static final byte SET_FOCUS = 49;
        private static final byte GET_FOCUS = 50;
        private static final byte GRAB_DEVICE = 51;
        private static final byte UNGRAB_DEVICE = 52;
        private static final byte ALLOW_EVENTS = 53;
        private static final byte PASSIVE_GRAB_DEVICE = 54;
        private static final byte PASSIVE_UNGRAB_DEVICE = 55;
        private static final byte LIST_PROPERTIES = 56;
        private static final byte CHANGE_PROPERTY = 57;
        private static final byte DELETE_PROPERTY = 58;
        private static final byte GET_PROPERTY = 59;
        private static final byte GET_SELECTED_EVENTS = 60;
    }

    private static class Selection {
        Window window;
        XClient client;
        long mask;
        int deviceId;
    }

    private static class Grab {
        Window window;
        XClient client;
        long mask;
        boolean ownerEvents;
        boolean implicit;
    }

    @Override
    public String getName() {
        return "XInputExtension";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return FIRST_ERROR_ID;
    }

    @Override
    public byte getFirstEventId() {
        return FIRST_EVENT_ID;
    }

    private static boolean isMasterDevice(int deviceId) {
        return deviceId == MASTER_POINTER_ID || deviceId == MASTER_KEYBOARD_ID;
    }

    private static boolean isPointerDevice(int deviceId) {
        return deviceId == MASTER_POINTER_ID || deviceId == SLAVE_POINTER_ID;
    }

    private static boolean matchesSelection(Selection selection, int deviceId) {
        return selection.deviceId == XI_ALL_DEVICES
                || (selection.deviceId == XI_ALL_MASTER_DEVICES && isMasterDevice(deviceId))
                || selection.deviceId == deviceId;
    }

    private static void writeReplyHeader(XClient client, XOutputStream outputStream, int length) {
        outputStream.writeByte(RESPONSE_CODE_SUCCESS);
        outputStream.writeByte((byte)0);
        outputStream.writeShort(client.getSequenceNumber());
        outputStream.writeInt(length);
    }

    private static void getExtensionVersion(XClient client, XInputStream inputStream,
                                            XOutputStream outputStream) throws IOException {
        inputStream.skip(client.getRemainingRequestLength());

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0);
            outputStream.writeShort((short)XI_MAJOR);
            outputStream.writeShort((short)0);
            outputStream.writeByte((byte)1);
            outputStream.writePad(19);
        }
    }

    private static void getClientPointer(XClient client, XInputStream inputStream,
                                         XOutputStream outputStream) throws IOException {
        inputStream.skip(client.getRemainingRequestLength());

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0);
            outputStream.writeByte((byte)1);
            outputStream.writeByte((byte)0);
            outputStream.writeShort((short)MASTER_POINTER_ID);
            outputStream.writePad(20);
        }
    }

    private static void queryVersion(XClient client, XInputStream inputStream,
                                     XOutputStream outputStream) throws IOException {
        int clientMajor = inputStream.readUnsignedShort();
        int clientMinor = inputStream.readUnsignedShort();
        inputStream.skip(client.getRemainingRequestLength());

        int negotiatedMajor = XI_MAJOR;
        int negotiatedMinor = XI_MINOR;
        if (clientMajor < XI_MAJOR || (clientMajor == XI_MAJOR && clientMinor < XI_MINOR)) {
            negotiatedMajor = clientMajor;
            negotiatedMinor = clientMinor;
        }

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0);
            outputStream.writeShort((short)negotiatedMajor);
            outputStream.writeShort((short)negotiatedMinor);
            outputStream.writePad(20);
        }
    }

    private static class DeviceInfo {
        final int id;
        final int use;
        final int attachment;
        final String name;

        DeviceInfo(int id, int use, int attachment, String name) {
            this.id = id;
            this.use = use;
            this.attachment = attachment;
            this.name = name;
        }

        boolean isPointer() {
            return use == XI_MASTER_POINTER || use == XI_SLAVE_POINTER;
        }

        int classesSize() {
            return isPointer() ? buttonClassSize() + 2 * 44 : 8 + Keyboard.KEYS_COUNT * 4;
        }

        int size() {
            return 12 + ((name.length() + 3) & ~3) + classesSize();
        }
    }

    private static final DeviceInfo[] DEVICES = {
        new DeviceInfo(MASTER_POINTER_ID, XI_MASTER_POINTER, MASTER_KEYBOARD_ID, "Virtual core pointer"),
        new DeviceInfo(MASTER_KEYBOARD_ID, XI_MASTER_KEYBOARD, MASTER_POINTER_ID, "Virtual core keyboard"),
        new DeviceInfo(SLAVE_POINTER_ID, XI_SLAVE_POINTER, MASTER_POINTER_ID, "Virtual core XTEST pointer"),
        new DeviceInfo(SLAVE_KEYBOARD_ID, XI_SLAVE_KEYBOARD, MASTER_KEYBOARD_ID, "Virtual core XTEST keyboard"),
    };

    private static int buttonClassSize() {
        return 8 + 4 + Pointer.MAX_BUTTONS * 4;
    }

    private static void writeButtonClass(XOutputStream outputStream, int sourceId) {
        int numButtons = Pointer.MAX_BUTTONS;
        outputStream.writeShort((short)XI_BUTTON_CLASS);
        outputStream.writeShort((short)(buttonClassSize() / 4));
        outputStream.writeShort((short)sourceId);
        outputStream.writeShort((short)numButtons);
        outputStream.writeInt(0); // button state
        for (int i = 0; i < numButtons; i++) outputStream.writeInt(0); // labels
    }

    private static void writeValuatorClass(XOutputStream outputStream, int sourceId, int axisNumber) {
        outputStream.writeShort((short)XI_VALUATOR_CLASS);
        outputStream.writeShort((short)11);
        outputStream.writeShort((short)sourceId);
        outputStream.writeShort((short)axisNumber);
        outputStream.writeInt(0);
        outputStream.writeFP3232(0);
        outputStream.writeFP3232(0);
        outputStream.writeFP3232(0);
        outputStream.writeInt(0);
        outputStream.writeByte((byte)0);
        outputStream.writePad(3);
    }

    private static void writeKeyClass(XOutputStream outputStream, int sourceId) {
        outputStream.writeShort((short)XI_KEY_CLASS);
        outputStream.writeShort((short)(2 + Keyboard.KEYS_COUNT));
        outputStream.writeShort((short)sourceId);
        outputStream.writeShort(Keyboard.KEYS_COUNT);
        for (int keycode = Keyboard.MIN_KEYCODE; keycode <= Keyboard.MAX_KEYCODE; keycode++) outputStream.writeInt(keycode);
    }

    private static void queryDevice(XClient client, XInputStream inputStream,
                                    XOutputStream outputStream) throws IOException {
        int deviceId = inputStream.readUnsignedShort();
        inputStream.skip(client.getRemainingRequestLength());

        List<DeviceInfo> devices = new ArrayList<>();
        for (DeviceInfo device : DEVICES) {
            if (deviceId == XI_ALL_DEVICES || (deviceId == XI_ALL_MASTER_DEVICES && isMasterDevice(device.id)) || deviceId == device.id) {
                devices.add(device);
            }
        }
        int length = 0;
        for (DeviceInfo device : devices) length += device.size();

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, length / 4);
            outputStream.writeShort((short)devices.size());
            outputStream.writePad(22);

            for (DeviceInfo device : devices) {
                byte[] name = device.name.getBytes(XServer.LATIN1_CHARSET);
                outputStream.writeShort((short)device.id);
                outputStream.writeShort((short)device.use);
                outputStream.writeShort((short)device.attachment);
                outputStream.writeShort((short)(device.isPointer() ? 3 : 1));
                outputStream.writeShort((short)name.length);
                outputStream.writeByte((byte)1);
                outputStream.writeByte((byte)0);
                outputStream.write(name);
                outputStream.writePad(((name.length + 3) & ~3) - name.length);

                if (device.isPointer()) {
                    writeButtonClass(outputStream, device.id);
                    writeValuatorClass(outputStream, device.id, 0);
                    writeValuatorClass(outputStream, device.id, 1);
                }
                else writeKeyClass(outputStream, device.id);
            }
        }
    }

    private void selectEvents(XClient client, XInputStream inputStream)
            throws XRequestError {
        int windowId = inputStream.readInt();
        int numMasks = inputStream.readUnsignedShort();
        inputStream.skip(2);

        if (numMasks == 0) throw new BadValue(numMasks);

        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        for (int i = 0; i < numMasks; i++) {
            int deviceId = inputStream.readUnsignedShort();
            int maskLen = inputStream.readUnsignedShort();
            long mask = 0;

            for (int word = 0; word < maskLen; word++) {
                long value = inputStream.readUnsignedInt();
                if (word < 2) mask |= value << (word * 32);
            }

            selections.removeIf(old -> old.client == client && old.window == window && old.deviceId == deviceId);

            if (mask != 0) {
                Selection selection = new Selection();
                selection.client = client;
                selection.window = window;
                selection.deviceId = deviceId;
                selection.mask = mask;
                selections.add(selection);
            }
        }

        inputStream.skip(client.getRemainingRequestLength());
    }

    private void queryPointer(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());
        XServer xServer = client.xServer;
        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        short x = xServer.pointer.getX();
        short y = xServer.pointer.getY();
        short[] localPoint = window.rootPointToLocal(x, y);
        Window pointWindow = xServer.inputDeviceManager.getPointWindow();
        Window child = null;
        for (Window ancestor = pointWindow; ancestor != null; ancestor = ancestor.getParent()) {
            if (ancestor.getParent() == window) {
                child = ancestor;
                break;
            }
        }

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 7);
            outputStream.writeInt(xServer.windowManager.rootWindow.id);
            outputStream.writeInt(child != null ? child.id : 0);
            outputStream.writeInt(x << 16);
            outputStream.writeInt(y << 16);
            outputStream.writeInt(localPoint[0] << 16);
            outputStream.writeInt(localPoint[1] << 16);
            outputStream.writeByte((byte)1);
            outputStream.writeByte((byte)0);
            outputStream.writeShort((short)1);
            int modifiers = modifiers(xServer);
            outputStream.writeInt(modifiers);
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            outputStream.writeInt(modifiers);
            outputStream.writeInt(0);
            outputStream.writeInt(buttons(xServer));
        }
    }

    /** XIChangeCursor: the window's cursor, like ChangeWindowAttributes */
    private static void changeCursor(XClient client, XInputStream inputStream) throws XRequestError {
        int windowId = inputStream.readInt();
        int cursorId = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);
        window.attributes.setCursor(cursorId != 0 ? client.xServer.cursorManager.getCursor(cursorId) : null);
        Bitmask mask = new Bitmask();
        mask.set(com.winlator.cmod.xserver.WindowAttributes.FLAG_CURSOR);
        client.xServer.windowManager.triggerOnUpdateWindowAttributes(window, mask);
    }

    private static void setFocus(XClient client, XInputStream inputStream) throws XRequestError {
        int windowId = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());
        WindowManager windowManager = client.xServer.windowManager;
        Window window = windowId != 0 ? windowManager.getWindow(windowId) : windowManager.rootWindow;
        if (window == null) throw new BadWindow(windowId);
        windowManager.setFocus(window, WindowManager.FocusRevertTo.POINTER_ROOT);
    }

    private static void getFocus(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(client.getRemainingRequestLength());
        Window focused = client.xServer.windowManager.getFocusedWindow();
        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0);
            outputStream.writeInt(focused != null ? focused.id : 0);
            outputStream.writePad(20);
        }
    }

    private void grabDevice(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        inputStream.skip(8); // time, cursor
        int deviceId = inputStream.readUnsignedShort();
        inputStream.skip(2); // grab_mode, paired_device_mode
        boolean ownerEvents = inputStream.readByte() != 0;
        inputStream.skip(1);
        int maskLen = inputStream.readUnsignedShort();
        long mask = 0;
        for (int word = 0; word < maskLen; word++) {
            long value = inputStream.readUnsignedInt();
            if (word < 2) mask |= value << (word * 32);
        }
        inputStream.skip(client.getRemainingRequestLength());
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        Grab grab = new Grab();
        grab.window = window;
        grab.client = client;
        grab.mask = mask;
        grab.ownerEvents = ownerEvents;
        if (isPointerDevice(deviceId)) pointerGrab = grab;
        else keyboardGrab = grab;

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0);
            outputStream.writeByte((byte)0); // Success
            outputStream.writePad(23);
        }
    }

    private void ungrabDevice(XClient client, XInputStream inputStream) {
        inputStream.skip(4); // time
        int deviceId = inputStream.readUnsignedShort();
        inputStream.skip(client.getRemainingRequestLength());
        if (isPointerDevice(deviceId)) {
            if (pointerGrab != null && pointerGrab.client == client) pointerGrab = null;
        }
        else if (keyboardGrab != null && keyboardGrab.client == client) keyboardGrab = null;
    }

    /** Replies with nothing in it: no passive grabs, device properties or selected events here */
    private static void writeEmptyReply(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(client.getRemainingRequestLength());
        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0);
            outputStream.writePad(24);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream,
                              XOutputStream outputStream)
            throws IOException, XRequestError {
        switch (client.getRequestData()) {
            case ClientOpcodes.GET_EXTENSION_VERSION:
                getExtensionVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.QUERY_POINTER:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                    queryPointer(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.CHANGE_CURSOR:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.CURSOR_MANAGER)) {
                    changeCursor(client, inputStream);
                }
                break;
            case ClientOpcodes.SET_FOCUS:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    setFocus(client, inputStream);
                }
                break;
            case ClientOpcodes.GET_FOCUS:
                getFocus(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GRAB_DEVICE:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                    grabDevice(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.UNGRAB_DEVICE:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                    ungrabDevice(client, inputStream);
                }
                break;
            case ClientOpcodes.WARP_POINTER:
            case ClientOpcodes.CHANGE_HIERARCHY:
            case ClientOpcodes.SET_CLIENT_POINTER:
            case ClientOpcodes.ALLOW_EVENTS:
            case ClientOpcodes.PASSIVE_UNGRAB_DEVICE:
            case ClientOpcodes.CHANGE_PROPERTY:
            case ClientOpcodes.DELETE_PROPERTY:
                inputStream.skip(client.getRemainingRequestLength());
                break;
            case ClientOpcodes.PASSIVE_GRAB_DEVICE:
            case ClientOpcodes.LIST_PROPERTIES:
            case ClientOpcodes.GET_PROPERTY:
            case ClientOpcodes.GET_SELECTED_EVENTS:
                writeEmptyReply(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_CLIENT_POINTER:
                getClientPointer(client, inputStream, outputStream);
                break;
            case ClientOpcodes.SELECT_EVENTS:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    selectEvents(client, inputStream);
                }
                break;
            case ClientOpcodes.QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.QUERY_DEVICE:
                queryDevice(client, inputStream, outputStream);
                break;
            default:
                throw new BadImplementation();
        }
    }

    public void onClientDisconnected(XClient client) {
        selections.removeIf(selection -> selection.client == client);
        if (pointerGrab != null && pointerGrab.client == client) pointerGrab = null;
        if (keyboardGrab != null && keyboardGrab.client == client) keyboardGrab = null;
    }

    public void onDestroyWindow(Window window) {
        selections.removeIf(selection -> selection.window == window);
        if (pointerGrab != null && pointerGrab.window == window) pointerGrab = null;
        if (keyboardGrab != null && keyboardGrab.window == window) keyboardGrab = null;
    }

    public void emitRawMotion(int deviceId, double deltaX, double deltaY) {
        for (Selection selection : selections) {
            if (!matchesSelection(selection, deviceId)) continue;
            if ((selection.mask & (1L << XI_RAW_MOTION)) == 0) continue;
            selection.client.sendEvent(
                    new XIRawMotionNotify(deviceId, MAJOR_OPCODE, deltaX, deltaY));
        }
    }

    public void emitRawButton(int deviceId, int buttonNumber, boolean pressed) {
        long eventMask = 1L << (pressed ? XI_RAW_BUTTON_PRESS : XI_RAW_BUTTON_RELEASE);

        for (Selection selection : selections) {
            if (!matchesSelection(selection, deviceId)) continue;
            if ((selection.mask & eventMask) == 0) continue;

            if (pressed) {
                selection.client.sendEvent(
                        new XIRawButtonPressNotify(deviceId, MAJOR_OPCODE, buttonNumber));
            }
            else {
                selection.client.sendEvent(
                        new XIRawButtonReleaseNotify(deviceId, MAJOR_OPCODE, buttonNumber));
            }
        }
    }

    // Device event delivery

    private static int buttons(XServer xServer) {
        int buttons = 0;
        for (Pointer.Button button : Pointer.Button.values()) {
            if (xServer.pointer.isButtonPressed(button)) buttons |= 1 << button.code();
        }
        return buttons;
    }

    private static int modifiers(XServer xServer) {
        return xServer.keyboard.getModifiersMask().getBits();
    }

    private boolean hasSelection(Window window, int evtype, int deviceId) {
        long bit = 1L << evtype;
        for (Selection selection : selections) {
            if (selection.window == window && (selection.mask & bit) != 0 && matchesSelection(selection, deviceId)) return true;
        }
        return false;
    }

    /**
     * The window an XI2 event from source would go to: the first one up to stopAt (included) that
     * a client selected it on, or null. Core delivery stops at the same point.
     */
    public Window findEventWindow(Window source, Window stopAt, int evtype, int deviceId) {
        for (Window window = source; window != null; window = window.getParent()) {
            if (hasSelection(window, evtype, deviceId)) return window;
            if (window == stopAt) break;
        }
        return null;
    }

    /** True when a grab or an implicit grab takes this device's events */
    public boolean isGrabbed(int deviceId) {
        return (isPointerDevice(deviceId) ? pointerGrab : keyboardGrab) != null;
    }

    /**
     * Delivers an XI2 device event, to the device's grab if there is one, else to the clients
     * that selected it on eventWindow (from findEventWindow). A button press starts an implicit
     * grab that takes the pointer's events until the last button is released.
     */
    public void sendDeviceEvent(XServer xServer, int evtype, int detail, Window eventWindow, Window pointWindow, int flags) {
        boolean pointer = evtype == XI_BUTTON_PRESS || evtype == XI_BUTTON_RELEASE || evtype == XI_MOTION;
        int deviceId = pointer ? MASTER_POINTER_ID : MASTER_KEYBOARD_ID;
        int sourceId = pointer ? SLAVE_POINTER_ID : SLAVE_KEYBOARD_ID;
        Grab grab = pointer ? pointerGrab : keyboardGrab;

        List<XClient> clients = new ArrayList<>();
        if (grab != null && !(grab.ownerEvents && eventWindow != null && hasClientSelection(eventWindow, grab.client, evtype, deviceId))) {
            if ((grab.mask & (1L << evtype)) == 0 && !grab.implicit) return;
            eventWindow = grab.window;
            clients.add(grab.client);
        }
        else if (eventWindow != null) {
            long bit = 1L << evtype;
            for (Selection selection : selections) {
                if (selection.window == eventWindow && (selection.mask & bit) != 0 && matchesSelection(selection, deviceId)
                        && !clients.contains(selection.client)) {
                    clients.add(selection.client);
                }
            }
        }
        if (eventWindow == null || clients.isEmpty()) return;

        short x = xServer.pointer.getX();
        short y = xServer.pointer.getY();
        short[] localPoint = eventWindow.rootPointToLocal(x, y);
        Window child = pointWindow != null && pointWindow != eventWindow && eventWindow.isAncestorOf(pointWindow) ? childOf(eventWindow, pointWindow) : null;
        // The button state is the one before the event, like the core events' state
        int buttons = buttons(xServer);
        if (evtype == XI_BUTTON_PRESS) buttons &= ~(1 << detail);
        else if (evtype == XI_BUTTON_RELEASE) buttons |= 1 << detail;

        XIDeviceEvent event = new XIDeviceEvent(MAJOR_OPCODE, evtype, deviceId, sourceId, detail, xServer.windowManager.rootWindow,
            eventWindow, child, x, y, localPoint[0], localPoint[1], buttons, modifiers(xServer), flags);
        for (XClient client : clients) client.sendEvent(event);

        if (evtype == XI_BUTTON_PRESS && pointerGrab == null) {
            Grab implicit = new Grab();
            implicit.window = eventWindow;
            implicit.client = clients.get(0);
            implicit.implicit = true;
            pointerGrab = implicit;
        }
        else if (evtype == XI_BUTTON_RELEASE && pointerGrab != null && pointerGrab.implicit && buttons(xServer) == 0) {
            pointerGrab = null;
        }
    }

    /** Sends a core event (MappingNotify) to the clients an XI2 event from source would go to */
    public void sendToSelectingClients(Window source, Window stopAt, int evtype, com.winlator.cmod.xserver.events.Event event) {
        boolean pointer = evtype == XI_BUTTON_PRESS || evtype == XI_BUTTON_RELEASE || evtype == XI_MOTION;
        int deviceId = pointer ? MASTER_POINTER_ID : MASTER_KEYBOARD_ID;
        Window window = findEventWindow(source, stopAt, evtype, deviceId);
        if (window == null) return;
        List<XClient> sent = new ArrayList<>();
        for (Selection selection : selections) {
            if (selection.window == window && (selection.mask & (1L << evtype)) != 0 && !sent.contains(selection.client)) {
                sent.add(selection.client);
                selection.client.sendEvent(event);
            }
        }
    }

    private boolean hasClientSelection(Window window, XClient client, int evtype, int deviceId) {
        long bit = 1L << evtype;
        for (Selection selection : selections) {
            if (selection.window == window && selection.client == client && (selection.mask & bit) != 0 && matchesSelection(selection, deviceId)) return true;
        }
        return false;
    }

    private static Window childOf(Window ancestor, Window window) {
        while (window != null && window.getParent() != ancestor) window = window.getParent();
        return window;
    }

    /** XI_Enter/XI_Leave/XI_FocusIn/XI_FocusOut, to the clients that selected them on that very window */
    public void sendCrossingEvent(XServer xServer, int evtype, Window window, byte mode, byte detail, boolean focus) {
        boolean keyboard = evtype == XI_FOCUS_IN || evtype == XI_FOCUS_OUT;
        int deviceId = keyboard ? MASTER_KEYBOARD_ID : MASTER_POINTER_ID;
        long bit = 1L << evtype;
        short x = xServer.pointer.getX();
        short y = xServer.pointer.getY();
        short[] localPoint = window.rootPointToLocal(x, y);
        XIEnterEvent event = null;
        for (Selection selection : selections) {
            if (selection.window != window || (selection.mask & bit) == 0 || !matchesSelection(selection, deviceId)) continue;
            if (event == null) {
                event = new XIEnterEvent(MAJOR_OPCODE, evtype, deviceId, keyboard ? SLAVE_KEYBOARD_ID : SLAVE_POINTER_ID, mode, detail,
                    xServer.windowManager.rootWindow, window, x, y, localPoint[0], localPoint[1], focus, buttons(xServer), modifiers(xServer));
            }
            selection.client.sendEvent(event);
        }
    }
}
