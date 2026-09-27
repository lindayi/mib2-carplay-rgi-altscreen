public final class NativeMenuVerificationTest {
    static void set(Object o,String name,Object value) throws Exception {
        java.lang.reflect.Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);
    }
    public static void main(String[] args) throws Exception {
        String[] names={
            "de.audi.tghu.navi.hmi.evohigh.NaviScreenBag8",
            "com.luka.carplay.settings.CarplayMenuController",
            "com.luka.carplay.settings.CarplaySettingsScreen",
            "com.luka.carplay.settings.CarplayMenuItem"
        };
        for(String name:names) {
            Class<?> type=Class.forName(name,false,NativeMenuVerificationTest.class.getClassLoader());
            if(type.getDeclaredMethods().length==0)throw new AssertionError("No methods: "+name);
        }
        java.lang.reflect.Constructor<?> ctor=Class.forName("com.luka.carplay.rgd.BAPBridge$SilentLogChannel").getDeclaredConstructor();
        ctor.setAccessible(true);final Object log=ctor.newInstance();
        de.audi.atip.base.IFrameworkAccess fw=(de.audi.atip.base.IFrameworkAccess)java.lang.reflect.Proxy.newProxyInstance(
            NativeMenuVerificationTest.class.getClassLoader(),new Class[]{de.audi.atip.base.IFrameworkAccess.class},(p,m,a)->{
                if(m.getName().equals("getLogChannel"))return log;
                if(m.getReturnType()==Boolean.TYPE)return Boolean.valueOf(m.getName().equals("isEvoHigh"));
                if(m.getReturnType()==Integer.TYPE)return Integer.valueOf(0);
                if(m.getReturnType()==Long.TYPE)return Long.valueOf(0);
                return null;
            });
        de.esolutions.hmi.widgets.audi.base.AbstractWidget.setFrameworkAccess(fw);
        com.luka.carplay.settings.CarplayMenuController menu=new com.luka.carplay.settings.CarplayMenuController();
        com.luka.carplay.settings.CarplaySettingsScreen screen=new com.luka.carplay.settings.CarplaySettingsScreen(400102);
        if(screen.getID()!=400102 || menu.getChildrenSize()!=0)throw new AssertionError("native constructors");
        de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController original=new de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController();
        original.setVisible(true);original.setVisibleOnCurrentStage(true);menu.add(original);
        java.lang.reflect.Field field=menu.getClass().getDeclaredField("original");field.setAccessible(true);
        ((java.util.List)field.get(menu)).add(original);
        java.lang.reflect.Method active=menu.getClass().getDeclaredMethod("isActiveMenuItem",de.esolutions.hmi.widgets.audi.base.AbstractWidget.class);
        active.setAccessible(true);
        set(menu,"page","root");
        if((Boolean)active.invoke(menu,original))throw new AssertionError("original items leak into custom page");
        set(menu,"page","");
        if(!(Boolean)active.invoke(menu,original) || menu.getChildrenSize()!=1)throw new AssertionError("OEM binding was removed");
        set(menu,"front",true);set(menu,"pending",false);
        menu.changed();
        field=menu.getClass().getDeclaredField("pending");field.setAccessible(true);
        if(field.getBoolean(menu))throw new AssertionError("background status steals OEM focus");
        System.out.println("NativeMenuVerificationTest: native constructors, OEM item retention, closed-menu focus isolation and strict verification PASS (native graphics still require the HU)");
    }
}
