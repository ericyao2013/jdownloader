package org.jdownloader.gui.mainmenu.action;

import java.awt.event.ActionEvent;

import org.jdownloader.controlling.contextmenu.CustomizableAppAction;
import org.jdownloader.gui.IconKey;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.gui.views.linkgrabber.actions.AddLinksAction;

public class AddLinksMenuAction extends CustomizableAppAction {

    /**
     *
     */
    private static final long serialVersionUID = 1L;

    public AddLinksMenuAction() {
        setName(_GUI.T.AddOptionsAction_actionPerformed_addlinks());
        setIconKey(IconKey.ICON_ADD);

    }

    @Override
    public void actionPerformed(ActionEvent e) {
        new AddLinksAction().actionPerformed(e);
    }

}
