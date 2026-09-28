package com.luka.carplay.settings;

import de.esolutions.hmi.widgets.audi.base.AbstractWidget;
import de.esolutions.hmi.widgets.audi.base.StringUtility;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.MultiLineLabelRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.TitleBarWidget;
import java.util.List;

/** Native text layers outside the menu's selectable-item and glassplate layout. */
final class CarplayMenuChrome {
    static class TextRenderer extends MultiLineLabelRendererHigh {
        private int visibleLines=1;
        TextRenderer(LabelController text){super(text);setAutoWrap(StringUtility.WRAP_MODE_STANDARD);}
        void setVisibleLines(int lines){visibleLines=lines;setCompositesDirty(true);}
        protected String[] calculatedRenderedLines(String[] lines) {
            String[] remaining=super.calculatedRenderedLines(lines);
            if(remaining==null || remaining.length<=visibleLines)return remaining;
            String[] visible=new String[visibleLines];
            System.arraycopy(remaining,0,visible,0,visibleLines);
            return visible;
        }
    }
    private final CarplayMenuController menu;
    private final LabelController text=new LabelController();
    private final TextRenderer renderer=new TextRenderer(text);
    private LabelController title,breadcrumb;
    private Object originalTitle,originalBreadcrumb;
    private final int x,y,width,height;
    private boolean open;
    private int pageCount=1;

    CarplayMenuChrome(CarplayMenuController menu,AbstractWidget screen) {
        this.menu=menu;
        x=menu.getX();y=menu.getY();width=menu.getWidth();height=menu.getHeight();
        findTitle(screen);
        if(title==null || breadcrumb==null)throw new IllegalStateException("Native Navigation Settings title not found");
        originalTitle=title.getModel();originalBreadcrumb=breadcrumb.getModel();
        text.setRenderer(renderer);text.setModelID(-1);
        // Stock maxLines truncates before firstVisibleLine; window the wrapped lines instead.
        text.setMaxLines(0);
        text.setColorIndices(new int[]{2,0,4,3});
        text.setVisible(false);
    }
    void attach() {
        // This runs from the queued refresh, after stock tree connection has finished.
        menu.getParent().add(text);
    }
    private void findTitle(AbstractWidget widget) {
        if(widget instanceof TitleBarWidget) {
            TitleBarWidget bar=(TitleBarWidget)widget;
            List titles=bar.getTitleWidgets(),crumbs=bar.getBreadcrumbWidgets();
            if(titles.size()==1 && crumbs.size()==1 && titles.get(0) instanceof LabelController
                    && crumbs.get(0) instanceof LabelController) {
                LabelController candidate=(LabelController)titles.get(0);
                LabelController trail=(LabelController)crumbs.get(0);
                if(hasTextID(candidate,402132) && hasTextID(trail,402311)) {
                    if(title!=null)throw new IllegalStateException("Ambiguous Navigation Settings title");
                    title=candidate;breadcrumb=trail;
                }
            }
        }
        List children=widget.getChildren();
        if(children!=null)for(int i=0;i<children.size();i++)findTitle((AbstractWidget)children.get(i));
    }
    private static boolean hasTextID(LabelController label,int id) {
        int[] ids=label.getTextIds();
        return label.getModelID()==-1 && ids!=null && ids.length==1 && ids[0]==id;
    }
    int show(MenuModel.Page view,int requestedPage,int controlsHeight,String notice,boolean reconnect) {
        open=true;
        int lineHeight=Math.max(renderer.getPreferredLineHeight(),renderer.getFontHeight());
        renderer.setLineHeight(lineHeight);
        int gap=menu.getLayout().getItemsGap();
        int textWidth=menu.getLayout().getContentWidth();
        if(lineHeight<=0 || textWidth<=0 || height<=controlsHeight+gap+lineHeight)
            throw new IllegalStateException("Native menu has no room for information text");
        int textHeight=view.document?height-controlsHeight-gap:lineHeight*2;
        int visibleLines=textHeight/lineHeight;
        textHeight=visibleLines*lineHeight;
        if(!view.document && height-textHeight-gap<controlsHeight)
            throw new IllegalStateException("Native menu has no room for controls and notice");
        text.setBounds(x+menu.getLayout().getContentLeftOffset(),
            view.document?y:y+height-textHeight,textWidth,textHeight);
        menu.setBounds(x,view.document?y+textHeight+gap:y,width,height-textHeight-gap);
        renderer.setFirstVisibleLine(0);
        String content=view.document?view.information:notice+(reconnect?"\nReconnect CarPlay to apply":"");
        text.setText(content.replace('\r',' '));
        int lines=renderer.getNumberOfRows(textWidth);
        if(!view.document && lines>visibleLines) {
            String summary=notice.startsWith("Not saved")?"Not saved - see Last result":
                notice.toLowerCase().indexOf("fail")>=0?"Failed - see Last result":"See Last result in diagnostics";
            text.setText(summary+(reconnect?"\nReconnect CarPlay to apply":""));
            if(renderer.getNumberOfRows(textWidth)>visibleLines)
                throw new IllegalStateException("Native notice does not fit");
        }
        pageCount=view.paginated?Math.max(1,(lines+visibleLines-1)/visibleLines):1;
        if(view.document && !view.paginated && lines>visibleLines)
            throw new IllegalStateException("Confirmation warning does not fit");
        int current=Math.max(0,Math.min(requestedPage,pageCount-1));
        renderer.setFirstVisibleLine(current*visibleLines);
        renderer.setVisibleLines(visibleLines);text.setVisible(true);
        title.setText("Carplay Altscreen");
        breadcrumb.setText(view.title.equals("Carplay Altscreen")?"":view.title+
            (view.paginated?" ("+(current+1)+"/"+pageCount+")":""));
        return current;
    }
    int pageCount(){return pageCount;}
    void close() {
        if(!open)return;
        text.setVisible(false);menu.setBounds(x,y,width,height);
        title.setModel(originalTitle);title.updateContent();
        breadcrumb.setModel(originalBreadcrumb);breadcrumb.updateContent();
        open=false;
    }
}
