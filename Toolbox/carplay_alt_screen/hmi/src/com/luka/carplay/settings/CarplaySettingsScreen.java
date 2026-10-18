package com.luka.carplay.settings;

import de.audi.atip.hmi.event.KeyEvent;
import de.esolutions.hmi.widgets.audi.evo.ScreenWidgetEVO;

/** Preserve native screen/drawer behavior; Back navigates our submenu first. */
public final class CarplaySettingsScreen extends ScreenWidgetEVO {
    private CarplayMenuController menu;
    private boolean consumedBack;
    public CarplaySettingsScreen(int id){super(id);}
    void setMenu(CarplayMenuController menu){this.menu=menu;}
    public void disconnecting(){consumedBack=false;menu=null;super.disconnecting();}
    public void keyPressed(KeyEvent event) {
        if(!event.isConsumed() && !isLocked() && getTerminalID()==0 && menu!=null
                && menu.isOpen() && event.getKeyCode()==KeyEvent.KEY_BACK
                && hmiService!=null && hmiService.getCurrentPopup(0)<=0) {
            consumedBack=true;event.consume();menu.back();return;
        }
        super.keyPressed(event);
    }
    public void keyReleased(KeyEvent event) {
        if(consumedBack && event.getKeyCode()==KeyEvent.KEY_BACK){consumedBack=false;event.consume();return;}
        super.keyReleased(event);
    }
}
