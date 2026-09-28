package com.luka.carplay.settings;

import de.audi.atip.hmi.event.KeyEvent;
import de.esolutions.hmi.widgets.audi.base.AbstractWidget;
import de.esolutions.hmi.widgets.audi.base.InitializationContext;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.CompositeRendererHigh;
import java.util.List;

/** Native Audi row; no replacement model IDs or generated translations. */
public final class CarplayMenuItem extends MenuItemController {
    private final CarplayMenuController owner;
    private final MenuModel.Row row;
    public CarplayMenuItem(CarplayMenuController owner,MenuModel.Row row,int id) {
        this.owner=owner;this.row=row;
        setRenderer(new CompositeRendererHigh(this));
        setModelID(-1);setLabelId(-1);setWidgetID(id);setInternalID(id);
        setGlassplateInsetsBottom(6);setGlassplateInsetsTop(7);
        boolean choice=row.target.equals("choose");
        boolean submenu=!row.target.equals("set") && !choice
            && !row.target.equals("back") && !row.target.startsWith("action:") && !row.target.startsWith("text:");
        setType(choice?TYPE_RADIO_BUTTON:row.checkable?TYPE_CHECKBOX:submenu?TYPE_SUBMENU:TYPE_ACTION);
        if(submenu)setBitmaps(new int[]{39,39,39,39});
        setFocusable(true);
        setInfolineTextEnabled(row.help);setInfolineTextDisabled(row.help);
        if(row.checkable)setModel(new Integer(row.checked?(choice?id:1):(choice?-1:0)));
    }
    MenuModel.Row row(){return row;}
    protected void afterConnected() {
        super.afterConnected();
        setLabels(this,row.label);
    }
    public void connected(InitializationContext context) {
        try {super.connected(context);}
        catch(RuntimeException error){owner.fail(error);}
        catch(LinkageError error){owner.fail(error);}
    }
    private static void setLabels(AbstractWidget widget,String text) {
        if(widget instanceof LabelController)((LabelController)widget).setText(text);
        List children=widget.getChildren();
        if(children!=null)for(int i=0;i<children.size();i++)setLabels((AbstractWidget)children.get(i),text);
    }
    public void keyPressed(KeyEvent event) {
        if(!event.isConsumed() && event.getKeyCode()==KeyEvent.INC_MENU_ENTER
                && isEnabled() && isVisible() && isVisibleOnCurrentStage()) {
            event.consume();owner.activate(row);return;
        }
        super.keyPressed(event);
    }
    public void keyReleased(KeyEvent event) {
        if(event.getKeyCode()==KeyEvent.INC_MENU_ENTER){event.consume();return;}
        super.keyReleased(event);
    }
}
