package xyz.znix.xftl.sys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.newdawn.slick.InputListener;

/**
 * The mouse and the keys as the hosts feed them (BrowserEngine on the website and on Android): mouse type 0 move,
 * 1 press, 2 release, 3 wheel; the listeners are the current screen's (MainGame.setCurrentState).
 * <p>
 * One click, one screen: a held mouse button belongs to the listeners there were when it went down. Its drags, its
 * release (and the click that comes with it) and {@link #isMouseButtonDown} stay with them, so a screen that came up
 * while the button was held never sees the rest of that click.
 * <p>
 * The original never acts twice on one click because its screens act on the press and their release only ends what the
 * press began: CApp::OnLButtonDown hands the press to MainMenu::MouseClick (which hands it to the open OptionsScreen,
 * the ship builder, ...) or CommandGui::LButtonDown, and CApp::OnLButtonUp the release to MainMenu::MouseUp (where
 * OptionsScreen::MouseUp only lets go of a dragged slider) or CommandGui::LButtonUp. The screens here act on the press
 * too; when some acted on the release (the main menu, the hangar), a press that changed the screen (Close in the title
 * menu's options, Game Over's Main Menu, Hangar or Restart) handed its release to the next screen, which clicked what
 * was under the pointer there, and this rule keeps any screen that still has a release from getting another's. The
 * original's touch code ties the halves of a touch together the same way (TouchRouter): MainMenu::OnTouch keeps the
 * finger that came down, and a button clicks only on the lift of the finger that went down on it.
 */
public class HostInput implements Input {
    public int x, y;
    public final Set<Integer> held = new HashSet<>(), pressed = new HashSet<>(), buttons = new HashSet<>();
    public final List<InputListener> listeners = new ArrayList<>();
    /** The listeners each held button's press reached. */
    private final Map<Integer, List<InputListener>> owners = new HashMap<>();

    @Override public int getMouseX() { return x; }

    @Override public int getMouseY() { return y; }

    /** Down for the screen that took the press only. */
    @Override public boolean isMouseButtonDown(int b) { return buttons.contains(b) && !holders(b).isEmpty(); }

    @Override public boolean isKeyPressed(int k) { return pressed.remove(k); }

    @Override public boolean isKeyDown(int k) { return held.contains(k); }

    @Override public void addListener(InputListener l) { listeners.add(l); }

    @Override public void removeAllListeners() { listeners.clear(); }

    @Override public void clearInputPressedRecord() { pressed.clear(); }

    /** The current listeners that [b]'s press reached. */
    private List<InputListener> holders(int b) {
        List<InputListener> from = owners.get(b), to = new ArrayList<>();
        if (from == null) return to;
        for (InputListener l : listeners) for (InputListener o : from) if (o == l) { to.add(l); break; }
        return to;
    }

    private boolean drags(InputListener l) {
        for (int b : buttons) { List<InputListener> from = owners.get(b); if (from != null) for (InputListener o : from) if (o == l) return true; }
        return false;
    }

    /** Lets go of every key and button, the buttons to the screen that has them. */
    public void reset() {
        for (int k : new ArrayList<>(held)) key(k, (char) 0, false);
        List<Integer> released = new ArrayList<>(buttons);
        buttons.clear();
        for (int b : released) for (InputListener l : holders(b)) l.mouseReleased(b, x, y);
        owners.clear();
        held.clear();
        pressed.clear();
    }

    public void mouse(int nx, int ny, int type, int b) {
        int ox = x, oy = y;
        x = nx;
        y = ny;
        if (type == 1) {
            buttons.add(b);
            owners.put(b, new ArrayList<>(listeners));
        }
        if (type == 2 && !buttons.contains(b)) return;
        List<InputListener> to = type == 2 ? holders(b) : new ArrayList<>(listeners);
        if (type == 2) {
            buttons.remove(b);
            owners.remove(b);
        }
        for (InputListener l : to) {
            if (type == 0) {
                l.mouseMoved(ox, oy, x, y);
                if (drags(l)) l.mouseDragged(ox, oy, x, y);
            } else if (type == 1) l.mousePressed(b, x, y);
            else if (type == 2) {
                l.mouseReleased(b, x, y);
                l.mouseClicked(b, x, y, 1);
            } else if (type == 3) l.mouseWheelMoved(b);
        }
    }

    public void key(int k, char c, boolean down) {
        if (down) {
            held.add(k);
            pressed.add(k);
        } else held.remove(k);
        for (InputListener l : new ArrayList<>(listeners)) {
            if (down) l.keyPressed(k, c);
            else l.keyReleased(k, c);
        }
    }
}
