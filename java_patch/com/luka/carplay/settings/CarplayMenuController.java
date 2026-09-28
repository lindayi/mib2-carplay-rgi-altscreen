package com.luka.carplay.settings;

import com.luka.carplay.framework.Log;
import de.esolutions.hmi.widgets.audi.base.AbstractWidget;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuController;
import de.audi.atip.hmi.event.EventDispatcher;
import de.audi.atip.hmi.event.RunnableEvent;
import java.util.ArrayList;

/** Decorates only the stock front Navigation Settings menu. OEM rows stay bound. */
public class CarplayMenuController extends MenuController implements SettingsRuntime.Listener {
    private final ArrayList original=new ArrayList();
    private final ArrayList generated=new ArrayList();
    private CarplayMenuItem entry;
    private volatile String page="";
    private boolean pending;
    private boolean rebuilding;
    private volatile boolean pageChanged;
    private volatile boolean front;
    private volatile boolean failed;
    private final Object updateLock=new Object();
    private long generation;
    private boolean updateQueued;
    private volatile EventDispatcher dispatcher;

    protected void initializeWidget() {
        super.initializeWidget();
        if(failed)return;
        try {initializeExtension();}
        catch(RuntimeException error){fail(error);}
        catch(LinkageError error){fail(error);}
    }
    private void initializeExtension() {
        front=getTerminalImpl().getTerminalID()==0;
        if(!front)return;
        if(hmiService==null || hmiService.getEventDispatcher()==null)
            throw new IllegalStateException("HMI event dispatcher unavailable");
        dispatcher=hmiService.getEventDispatcher();
        requireDispatchThread();
        synchronized(updateLock){generation++;updateQueued=false;}
        if(entry==null) {
            Object[] children=getChildren().toArray();
            for(int i=0;i<children.length;i++)if(super.isMenuItem((AbstractWidget)children[i]))original.add(children[i]);
            entry=new CarplayMenuItem(this,new MenuModel.Row("Carplay Altscreen","root",-1,0,false,false,false),990000);
            add(entry);
        }
        if(getInitContext().getScreen() instanceof CarplaySettingsScreen)
            ((CarplaySettingsScreen)getInitContext().getScreen()).setMenu(this);
        SettingsRuntime.start();SettingsRuntime.addListener(this);
    }
    protected boolean isActiveMenuItem(AbstractWidget widget) {
        if(failed && widget instanceof CarplayMenuItem)return false;
        if((page==null || page.length()==0) && generated!=null && generated.contains(widget))return false;
        if(page!=null && page.length()!=0 && (widget==entry || original!=null && original.contains(widget)))return false;
        return super.isActiveMenuItem(widget);
    }
    public boolean isOpen(){return page.length()!=0;}
    public void fail(Throwable error) {
        failed=true;front=false;page="";pending=false;
        SettingsRuntime.removeListener(this);
        Log.e("SettingsMenu","native extension disabled; original Navigation Settings retained",error);
        final EventDispatcher target=dispatcher;
        final long run;
        synchronized(updateLock){run=++generation;updateQueued=false;}
        if(target==null)return;
        try {target.postEvent(new RunnableEvent(new Runnable(){public void run(){
            synchronized(updateLock){if(run!=generation || target!=dispatcher)return;}
            try {requireDispatchThread();clearAllCaches();relayout();triggerRepaint();}
            catch(RuntimeException cleanup){Log.e("SettingsMenu","fallback relayout failed",cleanup);}
            catch(LinkageError cleanup){Log.e("SettingsMenu","fallback linkage failed",cleanup);}
        }}));}
        catch(RuntimeException cleanup){Log.e("SettingsMenu","cannot queue fallback relayout",cleanup);}
    }
    public void back(){if(isOpen())show(MenuModel.parent(page));}
    public void activate(MenuModel.Row row) {
        try {
            if(row.target.equals("back"))back();
            else if(row.target.equals("set"))SettingsRuntime.set(row.setting,row.value);
            else if(row.target.equals("choose")) {
                SettingsRuntime.set(row.setting,row.value);show(MenuModel.parent(page));
            } else if(row.target.startsWith("action:")) {
                SettingsRuntime.action(row.target.substring(7));
                if(page.startsWith("confirm:"))show(MenuModel.parent(page));
            } else show(row.target);
        } catch(RuntimeException e){fail(e);}
    }
    private void show(String next) {
        requireDispatchThread();
        page=next;pageChanged=true;changed();
    }
    public void changed() {
        final EventDispatcher target=dispatcher;
        final long run;
        synchronized(updateLock) {
            if(!front || failed || !isOpen() && !pageChanged || target==null || updateQueued)return;
            run=generation;updateQueued=true;
        }
        try {
            target.postEvent(new RunnableEvent(new Runnable(){public void run(){
                synchronized(updateLock) {
                    if(run!=generation || target!=dispatcher)return;
                    updateQueued=false;
                }
                if(!front || failed)return;
                refreshMenu();
            }}));
        } catch(RuntimeException e) {
            synchronized(updateLock){if(run==generation)updateQueued=false;}
            Log.e("SettingsMenu","Cannot queue HMI update",e);
        }
    }
    private void requireDispatchThread() {
        if(dispatcher==null || !dispatcher.isDispatchThread())
            throw new IllegalStateException("Native menu operation outside HMI event thread");
    }
    protected void refreshMenu() {
        requireDispatchThread();
        pending=true;rebuild();
        if(!failed)triggerRepaint();
    }
    protected void disconnectUpdates() {
        SettingsRuntime.removeListener(this);
        page="";pending=false;front=false;
        synchronized(updateLock){generation++;updateQueued=false;dispatcher=null;}
    }
    private void rebuild() {
        if(!front || rebuilding || !pending)return;
        requireDispatchThread();
        rebuilding=true;pending=false;
        try {
            int focus=getFocusedIndex()==null?-1:getMenuItemID(getFocusedIndex());
            for(int i=generated.size()-1;i>=0;i--)remove((AbstractWidget)generated.get(i));
            generated.clear();
            if(isOpen()) {
                String notice=SettingsRuntime.result();
                if(SettingsRuntime.reconnectPending())notice+=" | Reconnect CarPlay for connection changes";
                MenuModel.Row[] rows=MenuModel.rows(page,Preferences.get().snapshot(),SettingsRuntime.status(),notice);
                for(int i=0;i<rows.length;i++) {
                    CarplayMenuItem item=new CarplayMenuItem(this,rows[i],990001+i);
                    generated.add(item);add(item);
                    if(failed)return;
                }
            }
            clearAllCaches();relayout();
            boolean restored=false;
            if(isOpen() && !pageChanged)for(int i=0;i<generated.size();i++) {
                CarplayMenuItem item=(CarplayMenuItem)generated.get(i);
                if(item.getWidgetID()==focus){focusItemImmediately(getMenuItemIndex(item));restored=true;break;}
            }
            if(isOpen()) {if(!restored)jumpToTop();}
            else if(entry!=null)focusItemImmediately(getMenuItemIndex(entry));
            pageChanged=false;
        } catch(RuntimeException error){fail(error);}
        catch(LinkageError error){fail(error);}
        finally {rebuilding=false;}
    }
    public void disconnecting() {
        disconnectUpdates();
        super.disconnecting();
    }
}
