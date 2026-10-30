package com.winlator.cmod.xserver;

import android.util.SparseArray;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xserver.errors.BadIdChoice;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.BadValue;
import com.winlator.cmod.xserver.errors.XRequestError;
import com.winlator.cmod.xserver.events.ConfigureNotify;
import com.winlator.cmod.xserver.events.ConfigureRequest;
import com.winlator.cmod.xserver.events.DestroyNotify;
import com.winlator.cmod.xserver.events.FocusChange;
import com.winlator.cmod.xserver.events.Event;
import com.winlator.cmod.xserver.events.Expose;
import com.winlator.cmod.xserver.events.MapNotify;
import com.winlator.cmod.xserver.events.MapRequest;
import com.winlator.cmod.xserver.events.ResizeRequest;
import com.winlator.cmod.xserver.events.UnmapNotify;

import java.util.ArrayList;
import java.util.List;

public class WindowManager extends XResourceManager {
    public enum FocusRevertTo {NONE, POINTER_ROOT, PARENT}
    private OnFocusChangeListener onFocusChangeListener;
    public final Window rootWindow;
    private final SparseArray<Window> windows = new SparseArray<>();
    public final DrawableManager drawableManager;
    private Window focusedWindow;
    private FocusRevertTo focusRevertTo = FocusRevertTo.NONE;
    private final ArrayList<OnWindowModificationListener> onWindowModificationListeners = new ArrayList<>();
    private volatile boolean renderingEnabled = true;

    public interface OnWindowModificationListener {
        default void onCreateWindow(Window window, Window parent) {}

        default void onMapWindow(Window window) {}

        default void onUnmapWindow(Window window) {}
        
        default void onDestroyWindow(Window window) {}

        default void onChangeWindowZOrder(Window window) {}

        default void onUpdateWindowContent(Window window) {}

        default void onUpdateWindowContentDirect(Window window, Drawable drawable) {}

        default void onUpdateWindowGeometry(Window window, boolean resized) {}

        default void onUpdateWindowAttributes(Window window, Bitmask mask) {}

        default void onModifyWindowProperty(Window window, Property property) {}

        default void onReparentWindow(Window window, Window newParent) {}
    }

    public WindowManager(ScreenInfo screenInfo, DrawableManager drawableManager) {
        this.drawableManager = drawableManager;
        int id = IDGenerator.generate();
        Drawable drawable = drawableManager.createDrawable(id, screenInfo.width, screenInfo.height, drawableManager.getVisual());
        rootWindow = new Window(id, drawable, 0, 0, screenInfo.width, screenInfo.height, null);
        rootWindow.attributes.setMapped(true);
        windows.put(id, rootWindow);
        // An X server starts with the focus on PointerRoot: keys go to the window under the
        // pointer until a client or window manager sets the focus. Starting at None dropped every
        // key for clients that never call SetInputFocus (Wine always does)
        focusedWindow = rootWindow;
        focusRevertTo = FocusRevertTo.POINTER_ROOT;
    }

    public Window getWindow(int id) {
        return windows.get(id);
    }

    public Window findWindowWithProcessId(int processId) {
        for (int i = 0; i < windows.size(); i++) {
            Window window = windows.valueAt(i);
            if (window != null && window.getProcessId() == processId) return window;
        }
        return null;
    }

    public Window findWindowByProcessName(String exeName) {
        String exeBase = exeName.toLowerCase().replace(".exe", "");
        for (int i = 0; i < windows.size(); i++) {
            Window window = windows.valueAt(i);
            if (window == null) continue;
            String className = window.getClassName().toLowerCase();
            if (!className.isEmpty() && className.contains(exeBase)) return window;
        }
        return null;
    }

    public void destroyWindow(int id) {
        Window window = getWindow(id);
        if (window != null && rootWindow.id != id) {
            unmapWindow(window);
            removeAllSubwindowsAndWindow(window);
            triggerOnDestroyWindow(window);
        }
    }

    private void removeAllSubwindowsAndWindow(Window window) {
        List<Window> children = new ArrayList<>(window.getChildren());
        for (Window child : children) removeAllSubwindowsAndWindow(child);

        Window parent = window.getParent();
        window.sendEvent(Event.STRUCTURE_NOTIFY, new DestroyNotify(window, window));
        parent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new DestroyNotify(parent, window));
        windows.remove(window.id);
        if (window.isInputOutput()) drawableManager.removeDrawable(window.getContent().id);
        window.attributes.releaseBackgroundPixmap();
        triggerOnFreeResourceListener(window);
        if (window == focusedWindow) revertFocus();
        parent.removeChild(window);
    }

    public void mapWindow(Window window) {
        mapWindow(window, null);
    }

    /** @param requester the client asking for it, or null when the server maps it */
    public void mapWindow(Window window, XClient requester) {
        if (!window.attributes.isMapped()) {
            Window parent = window.getParent();
            if (!parent.isRedirectedFor(Event.SUBSTRUCTURE_REDIRECT, requester) || window.attributes.isOverrideRedirect()) {
                window.attributes.setMapped(true);
                window.sendEvent(Event.STRUCTURE_NOTIFY, new MapNotify(window, window));
                parent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new MapNotify(parent, window));
                window.sendEvent(Event.EXPOSURE, new Expose(window));
                triggerOnMapWindow(window);
            }
            else parent.sendEvent(Event.SUBSTRUCTURE_REDIRECT, new MapRequest(parent, window));
        }
    }

    public void unmapWindow(Window window) {
        if (rootWindow.id != window.id && window.attributes.isMapped()) {
            window.attributes.setMapped(false);
            Window parent = window.getParent();
            window.sendEvent(Event.STRUCTURE_NOTIFY, new UnmapNotify(window, window));
            parent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new UnmapNotify(parent, window));
            if (window == focusedWindow) revertFocus();
            triggerOnUnmapWindow(window);
        }
    }

    public Window getFocusedWindow() {
        return focusedWindow;
    }

    public void revertFocus() {
        switch (focusRevertTo) {
            case NONE:
                // Strictly the focus becomes None here, but with no window manager to set it again
                // every key would be dropped from then on (SDL2 focuses a probe window and destroys
                // it), so fall back to PointerRoot like a window manager would
                focusedWindow = rootWindow;
                focusRevertTo = FocusRevertTo.POINTER_ROOT;
                break;
            case POINTER_ROOT:
                focusedWindow = rootWindow;
                break;
            case PARENT:
                if (focusedWindow.getParent() != null) focusedWindow = focusedWindow.getParent();
                break;
        }
    }

    public void setFocus(Window focusedWindow, FocusRevertTo focusRevertTo) {
        Window previous = this.focusedWindow;
        this.focusedWindow = focusedWindow;
        this.focusRevertTo = focusRevertTo;

        // Simplified focus events (no virtual crossing events): enough for clients like SDL2 that
        // only track whether their own window has the keyboard focus
        if (previous == focusedWindow) return;
        if (previous != null && previous != rootWindow) {
            previous.sendEvent(Event.FOCUS_CHANGE, new FocusChange(false, previous, FocusChange.NOTIFY_NONLINEAR));
        }
        if (focusedWindow != null && focusedWindow != rootWindow) {
            focusedWindow.sendEvent(Event.FOCUS_CHANGE, new FocusChange(true, focusedWindow, FocusChange.NOTIFY_NONLINEAR));
        }
        if (onFocusChangeListener != null) onFocusChangeListener.onFocusChange(previous, focusedWindow);
    }

    /** XInput2 sends its own focus events (GTK4 tracks the keyboard focus with them) */
    public interface OnFocusChangeListener {
        void onFocusChange(Window previous, Window focused);
    }

    public void setOnFocusChangeListener(OnFocusChangeListener onFocusChangeListener) {
        this.onFocusChangeListener = onFocusChangeListener;
    }

    public FocusRevertTo getFocusRevertTo() {
        return focusRevertTo;
    }

    public Window createWindow(int id, Window parent, short x, short y, short width, short height, WindowAttributes.WindowClass windowClass, Visual visual, byte depth, XClient client) throws XRequestError {
        if (windows.indexOfKey(id) >= 0) throw new BadIdChoice(id);

        boolean isInputOutput = false;
        switch (windowClass) {
            case COPY_FROM_PARENT:
                depth = (depth != 0 || !parent.isInputOutput()) ? depth : parent.getContent().visual.depth;
                isInputOutput = parent.isInputOutput();
                break;
            case INPUT_OUTPUT:
                if (parent.isInputOutput()) {
                    depth = depth == 0 ? parent.getContent().visual.depth : depth;
                    isInputOutput = true;
                } else throw new BadMatch();
                break;
            case INPUT_ONLY:
                isInputOutput = false;
                break;
        }

        if (isInputOutput) {
            visual = visual == null ? parent.getContent().visual : visual;
            if (depth != visual.depth) throw new BadMatch();
        }

        Drawable drawable = null;
        if (isInputOutput) {
            drawable = drawableManager.createDrawable(id, width, height, visual);
            if (drawable == null) throw new BadIdChoice(id);
        }

        final Window window = new Window(id, drawable, x, y, width, height, client);
        window.attributes.setWindowClass(windowClass);
        if (drawable != null) drawable.setOnDrawListener(() -> triggerOnUpdateWindowContent(window));
        windows.put(id, window);
        parent.addChild(window);
        triggerOnCreateResourceListener(window);
        triggerOnCreateWindow(window, parent);
        return window;
    }

    private void changeWindowGeometry(Window window, short x, short y, short width, short height) {
        boolean resized = window.getWidth() != width || window.getHeight() != height;
        if (resized && window.hasEventListenerFor(Event.RESIZE_REDIRECT)) {
            window.sendEvent(Event.SUBSTRUCTURE_REDIRECT, new ResizeRequest(window, width, height));
            width = window.getWidth();
            height = window.getHeight();
            resized = false;
        }

        if (resized && window.isInputOutput()) {
            Drawable oldContent = window.getContent();
            drawableManager.removeDrawable(oldContent.id);
            Drawable newContent = drawableManager.createDrawable(oldContent.id, width, height, oldContent.visual);
            newContent.setOnDrawListener(() -> triggerOnUpdateWindowContent(window));
            window.setContent(newContent);
        }

        if (resized || window.getX() != x || window.getY() != y) {
            window.setX(x);
            window.setY(y);
            window.setWidth(width);
            window.setHeight(height);
            triggerOnUpdateWindowGeometry(window, resized);
        }

        if (resized && window.isInputOutput() && window.attributes.isMapped()) {
            window.sendEvent(new Expose(window));
        }
    }

    private void changeWindowZOrder(Window.StackMode stackMode, Window window, Window sibling) {
        Window parent = window.getParent();
        switch (stackMode) {
            case ABOVE:
                parent.moveChildAbove(window, sibling);
                break;
            case BELOW:
                parent.moveChildBelow(window, sibling);
                break;
        }
        triggerOnChangeWindowZOrder(window);
    }

    public void configureWindow(Window window, Bitmask valueMask, XInputStream inputStream, XClient requester) throws XRequestError {
        short x = window.getX();
        short y = window.getY();
        short width = window.getWidth();
        short height = window.getHeight();
        short borderWidth = window.getBorderWidth();
        Window sibling = null;
        Window.StackMode stackMode = null;

        for (int index : valueMask) {
            switch (index) {
                case Window.FLAG_X:
                    x = (short)inputStream.readInt();
                    break;
                case Window.FLAG_Y:
                    y = (short)inputStream.readInt();
                    break;
                case Window.FLAG_WIDTH:
                    width = (short)inputStream.readInt();
                    break;
                case Window.FLAG_HEIGHT:
                    height = (short)inputStream.readInt();
                    break;
                case Window.FLAG_BORDER_WIDTH:
                    borderWidth = (short)inputStream.readInt();
                    break;
                case Window.FLAG_SIBLING:
                    sibling = getWindow(inputStream.readInt());
                    break;
                case Window.FLAG_STACK_MODE:
                    stackMode = Window.StackMode.values()[inputStream.readInt()];
                    break;
            }
        }

        // A zero size is a BadValue (window managers send them while setting up frames); the
        // content drawable can't be allocated with it
        if (width <= 0) throw new BadValue(width);
        if (height <= 0) throw new BadValue(height);

        Window parent = window.getParent();
        boolean overrideRedirect = window.attributes.isOverrideRedirect();
        if (!parent.isRedirectedFor(Event.SUBSTRUCTURE_REDIRECT, requester) || overrideRedirect) {
            changeWindowGeometry(window, x, y, width, height);

            window.setBorderWidth(borderWidth);
            if (stackMode != null) changeWindowZOrder(stackMode, window, sibling);

            Window previousSibling = window.previousSibling();
            window.sendEvent(Event.STRUCTURE_NOTIFY, new ConfigureNotify(window, window, previousSibling, x, y, width, height, borderWidth, overrideRedirect));
            parent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new ConfigureNotify(parent, window, previousSibling, x, y, width, height, borderWidth, overrideRedirect));
        }
        else parent.sendEvent(Event.SUBSTRUCTURE_REDIRECT, new ConfigureRequest(parent, window, window.previousSibling(), x, y, width, height, borderWidth, stackMode, valueMask));
    }

    /** Raises {@code window} to the top of its parent's Z-order. */
    public void raiseWindow(Window window) {
        if (window == null || window.getParent() == null) return;
        changeWindowZOrder(Window.StackMode.ABOVE, window, null);
    }

    public void reparentWindow(Window window, Window newParent) {
        Window oldParent = window.getParent();
        if (oldParent != null) oldParent.removeChild(window);
        newParent.addChild(window);
        triggerOnReparentWindow(window, newParent);
    }

    public Window findPointWindow(short rootX, short rootY) {
        return findPointWindow(rootWindow, rootX, rootY);
    }

    private Window findPointWindow(Window window, short rootX, short rootY) {
        if (!(window.attributes.isMapped() && window.containsPoint(rootX, rootY))) return null;
        Window child = window.getChildByCoords(rootX, rootY);
        return child != null ? findPointWindow(child, rootX, rootY) : window;
    }

    public void setRenderingEnabled(boolean enabled) {
        this.renderingEnabled = enabled;
    }

    public void addOnWindowModificationListener(OnWindowModificationListener onWindowModificationListener) {
        onWindowModificationListeners.add(onWindowModificationListener);
    }

    public void removeOnWindowModificationListener(OnWindowModificationListener onWindowModificationListener) {
        onWindowModificationListeners.remove(onWindowModificationListener);
    }

    private void triggerOnMapWindow(Window window) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onMapWindow(window);
        }
    }

    public void triggerOnCreateWindow(Window window, Window parent) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onCreateWindow(window, parent);
        }
    }

    public void triggerOnReparentWindow(Window window, Window newParent) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onReparentWindow(window, newParent);
        }
    }

    private void triggerOnUnmapWindow(Window window) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onUnmapWindow(window);
        }
    }

    public void triggerOnDestroyWindow(Window window) {
    	for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
        	onWindowModificationListeners.get(i).onDestroyWindow(window);
        }
    }

    private void triggerOnChangeWindowZOrder(Window window) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onChangeWindowZOrder(window);
        }
    }

    protected void triggerOnUpdateWindowContent(Window window) {
        if (!renderingEnabled) return;
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onUpdateWindowContent(window);
        }
    }

    public void triggerOnUpdateWindowContentDirect(Window window, Drawable drawable) {
        if (!renderingEnabled) return;
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onUpdateWindowContentDirect(window, drawable);
        }
    }

    protected void triggerOnUpdateWindowGeometry(Window window, boolean resized) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onUpdateWindowGeometry(window, resized);
        }
    }

    public void triggerOnUpdateWindowAttributes(Window window, Bitmask mask) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onUpdateWindowAttributes(window, mask);
        }
    }

    public void triggerOnModifyWindowProperty(Window window, Property property) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onModifyWindowProperty(window, property);
        }
    }

    
}
