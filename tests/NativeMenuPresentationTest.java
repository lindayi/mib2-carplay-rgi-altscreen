package com.luka.carplay.settings;

import de.esolutions.hmi.widgets.audi.base.AbstractWidget;
import de.esolutions.hmi.widgets.audi.base.StringUtility;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LayoutContainerController;
import de.esolutions.hmi.widgets.audi.evo.widgets.RadioButtonController;
import de.esolutions.hmi.widgets.audi.evo.widgets.TitleBarWidget;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.IMenuItem;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuLayout;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

public final class NativeMenuPresentationTest {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static Object field(Object target,Class<?> owner,String name) throws Exception {
        Field f=owner.getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    static final class Menu extends CarplayMenuController {
        final MenuLayout layout=new MenuLayout(this) {
            public int getActiveContentRightOffset(boolean small){return 245;}
        };
        public MenuLayout getLayout(){return layout;}
        void disconnectUpdatesForTest(){disconnectUpdates();}
    }
    // Only font measurement is simulated; the stock widgets, paging window and bounds are real.
    static final class FontFixture extends CarplayMenuChrome.TextRenderer {
        final LabelController label;
        int fontHeight=28;
        final StringUtility metrics=new StringUtility(null) {
            public int getCharWidth(char c){return 14;}
            public int getStringWidth(String text){return text.length()*14;}
            protected int getStringWidth(char[] text,int start,int length){return length*14;}
            protected int getFontBaseline(){return 23;}
            protected boolean isAsia(){return false;}
        };
        FontFixture(LabelController label){super(label);this.label=label;}
        public int getFontHeight(){return fontHeight;}
        public int getNumberOfRows(int width){return lines(width).length;}
        String[] lines(int width) {
            return metrics.wrapText((String)label.getModel(),width,width,getAutoWrap());
        }
    }
    static LabelController label(int id,String text) {
        LabelController label=new LabelController();
        label.setModelID(-1);label.setTextIds(new int[]{id});label.setModel(text);
        return label;
    }
    static void framework() throws Exception {
        Constructor<?> ctor=Class.forName("com.luka.carplay.rgd.BAPBridge$SilentLogChannel").getDeclaredConstructor();
        ctor.setAccessible(true);Object log=ctor.newInstance();
        de.audi.atip.base.IFrameworkAccess fw=(de.audi.atip.base.IFrameworkAccess)Proxy.newProxyInstance(
            NativeMenuPresentationTest.class.getClassLoader(),new Class[]{de.audi.atip.base.IFrameworkAccess.class},(p,m,a)->{
                if(m.getName().equals("getLogChannel"))return log;
                if(m.getReturnType()==boolean.class)return true;
                if(m.getReturnType()==int.class)return 0;
                if(m.getReturnType()==long.class)return 0L;
                return null;
            });
        AbstractWidget.setFrameworkAccess(fw);
    }
    public static void main(String[] args) throws Exception {
        framework();
        Preferences.Snapshot prefs=new Preferences.Snapshot(
            Preferences.parse(new String(Files.readAllBytes(Paths.get(args[0])),"UTF-8")),0);
        String[] status={"Build: fixture","Phone session: Connected","Map mascot renderer: NIAN","Last action: not saved"};
        String message="Action failed: a deliberately long failure message must remain readable in Last result.";
        Set<String> pages=new LinkedHashSet<>(Arrays.asList("root","presentation","information","guidance","appearance",
            "controls","diagnostics","recovery","status","result","confirm:reset","confirm:export_full","confirm:restart_video"));
        for(int id=0;id<Setting.ALL.length;id++)pages.add("choice:"+id);
        for(String name:pages) {
            MenuModel.Page page=MenuModel.page(name,prefs,status,message);
            check(page.rows[0].target.equals("back"),"Back/Cancel missing: "+name);
            Set<String> keys=new HashSet<>();
            for(MenuModel.Row row:page.rows) {
                check(row.target.length()>0 && keys.add(row.focusKey()),"non-action or ambiguous focus");
                check(!row.label.equals(page.title) && !row.label.equals(message),"title/notice is an option");
                for(String line:status)check(!row.label.equals(line),"status value is an option");
                check(!row.label.contains("[reconnect]"),"hint still attached to option label");
                CarplayMenuItem item=new CarplayMenuItem(new CarplayMenuController(),row,990001);
                check(item.isFocusable(),"real action not focusable");
                check(item.getType()!=MenuItemController.TYPE_LABEL,"informational menu row returned");
                if(row.target.equals("choose")) {
                    check(item.getType()==MenuItemController.TYPE_RADIO_BUTTON,"choice is not a radio");
                    RadioButtonController radio=new RadioButtonController();
                    radio.setWidgetID(item.getWidgetID());radio.setModel(item.getModel());
                    Method calculate=RadioButtonController.class.getDeclaredMethod("calculateTargets");
                    calculate.setAccessible(true);calculate.invoke(radio);
                    check(((Float)field(radio,RadioButtonController.class,"targetAnimatedSelected"))==(row.checked?1f:0f),
                        "native radio does not agree with selection");
                } else if(row.checkable)check(item.getType()==MenuItemController.TYPE_CHECKBOX,"switch is not checkbox");
                check(page.rows[page.focusIndex(row.focusKey())]==row,"focus cannot be restored");
            }
            if(name.startsWith("choice:"))check(page.rows[page.focusIndex(null)].checked,"choice opens away from selection");
            if(name.startsWith("confirm:")) {
                check(page.document && page.information.length()>0 && page.focusIndex(null)==0,"unsafe confirmation");
                check(page.rows[0].label.equals("Cancel"),"confirmation must default to Cancel");
            }
        }
        MenuModel.Page root=MenuModel.page("root",prefs,status,message);
        MenuModel.Row enabled=root.rows[1];
        check(enabled.setting==Setting.ENABLED && root.focusIndex(null)==1,"root should open on Enabled");
        MenuModel.Row changed=new MenuModel.Row(enabled.label,enabled.target,enabled.setting,1-enabled.value,true,!enabled.checked,enabled.help);
        check(enabled.focusKey().equals(changed.focusKey()),"changing switch loses focus");
        check(MenuModel.page("status",prefs,status,message).information.contains("Map mascot renderer: NIAN"),"status missing");
        check(MenuModel.page("result",prefs,status,message).information.equals(message),"failure detail truncated");

        Menu menu=new Menu();menu.setBounds(59,77,946,324);
        menu.layout.setContentLeftOffset(12);menu.layout.setItemsGap(9);
        LayoutContainerController screen=new LayoutContainerController();
        screen.add(menu);
        TitleBarWidget bar=new TitleBarWidget();
        LabelController title=label(402132,"Audi title"),crumb=label(402311,"Audi breadcrumb");
        bar.addTitle(title);bar.addBreadcrumb(crumb);screen.add(bar);
        CarplayMenuChrome chrome=new CarplayMenuChrome(menu,screen);chrome.attach();
        LabelController text=(LabelController)field(chrome,CarplayMenuChrome.class,"text");
        check(!(text instanceof IMenuItem) && !menu.isMenuItem(text),"read-only layer is a menu item");
        check(((CarplayMenuChrome.TextRenderer)text.getRenderer()).getAutoWrap()==
            de.esolutions.hmi.widgets.audi.base.StringUtility.WRAP_MODE_STANDARD,"native renderer still defaults to no wrapping");
        FontFixture font=new FontFixture(text);
        Field renderer=CarplayMenuChrome.class.getDeclaredField("renderer");renderer.setAccessible(true);renderer.set(chrome,font);
        text.setRenderer(font);
        for(String name:pages) {
            MenuModel.Page page=MenuModel.page(name,prefs,status,message);
            chrome.show(page,0,page.document?150:50,"Saved",true);
            check(title.getModel().equals("Carplay Altscreen"),"native title not changed");
            check(text.getParent()==menu.getParent() && !menu.getChildren().contains(text),"text is inside list");
            check(text.getY()>=77 && text.getY()+text.getHeight()<=401,"text outside stock content");
            check(menu.getY()>=77 && menu.getY()+menu.getHeight()<=401,"controls outside stock content");
            check(page.document?text.getY()+text.getHeight()<menu.getY():
                menu.getY()+menu.getHeight()<text.getY(),"text overlaps controls");
            check(font.calculatedRenderedLines(font.lines(text.getWidth())).length<=text.getHeight()/font.getLineHeight(),
                "renderer leaks lines into controls");
            for(String line:font.lines(text.getWidth()))
                check(font.metrics.getStringWidth(line)<=text.getWidth(),"native wrapped text exceeds its width");
        }
        StringBuilder longText=new StringBuilder();
        for(int i=0;i<90;i++)longText.append("Status ").append(i).append(": retained information\n");
        MenuModel.Page details=MenuModel.page("result",prefs,status,longText.toString());
        chrome.show(details,0,150,"Saved",false);
        String[] all=font.lines(text.getWidth());
        List<String> seen=new ArrayList<>();
        int count=chrome.pageCount();
        check(count>2,"fixture did not exercise later pages");
        for(int i=0;i<count;i++) {
            check(chrome.show(details,i,150,"Saved",false)==i,"page changed");
            seen.addAll(Arrays.asList(font.calculatedRenderedLines(all)));
        }
        check(Arrays.equals(all,seen.toArray(new String[0])),"later pages skipped/truncated/duplicated text");
        check(text.getMaxLines()==0,"stock truncation occurs before paging");
        check(chrome.show(details,999,150,"Saved",false)==count-1,"stale page not bounded");
        chrome.show(root,0,50,message,true);
        check(text.getModel().toString().contains("Failed") && text.getModel().toString().contains("Reconnect"),"failure/reconnect lost");
        MenuModel.Page confirmation=MenuModel.page("confirm:reset",prefs,status,message);
        MenuModel.Page tooLong=new MenuModel.Page(confirmation.title,longText.toString(),
            new ArrayList<>(Arrays.asList(confirmation.rows)),true,false);
        try {chrome.show(tooLong,0,150,"Saved",false);throw new AssertionError("clipped confirmation accepted");}
        catch(IllegalStateException expected){}
        chrome.close();
        check(menu.getX()==59 && menu.getY()==77 && menu.getWidth()==946 && menu.getHeight()==324,"OEM bounds not restored");
        check(title.getModel().equals("Audi title") && crumb.getModel().equals("Audi breadcrumb"),"OEM title not restored");
        check(!text.isVisible(),"information leaks onto stock page");
        Field ownedChrome=CarplayMenuController.class.getDeclaredField("chrome");ownedChrome.setAccessible(true);
        ownedChrome.set(menu,chrome);
        Field dispatcher=CarplayMenuController.class.getDeclaredField("dispatcher");dispatcher.setAccessible(true);
        Object ui=Proxy.newProxyInstance(NativeMenuPresentationTest.class.getClassLoader(),
            new Class[]{de.audi.atip.hmi.event.EventDispatcher.class},
            (p,m,a)->m.getReturnType()==boolean.class?Boolean.TRUE:null);
        int childCount=screen.getChildrenSize();
        for(int i=0;i<30;i++) {
            dispatcher.set(menu,ui);
            chrome.show(root,0,50,"Saved",false);menu.disconnectUpdatesForTest();
            check(screen.getChildrenSize()==childCount && screen.getChildren().contains(text),
                "disconnect mutates a parent whose stock teardown cached its child count");
            check(!text.isVisible() && ownedChrome.get(menu)==chrome,"hidden layer not retained for reconnection");
        }
        check(screen.getChildren().contains(menu) && screen.getChildren().contains(bar),"OEM widgets removed");
        crumb.setTextIds(new int[]{999});
        try {new CarplayMenuChrome(menu,screen);throw new AssertionError("unexpected native title accepted");}
        catch(IllegalStateException expected){}
        System.out.println("NativeMenuPresentationTest: action-only rows, native radios, focus, separate text bounds, complete paging and OEM restoration PASS (font metrics simulated; graphics require HU)");
    }
}
