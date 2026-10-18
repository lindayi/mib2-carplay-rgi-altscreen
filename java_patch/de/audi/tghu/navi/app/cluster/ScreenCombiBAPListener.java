package de.audi.tghu.navi.app.cluster;

import de.audi.atip.log.LogChannel;
import de.audi.atip.mmicombi.IViewSizeManager;
import de.audi.tghu.command.ICommandListFactory;
import de.audi.tghu.navi.app.NavigationEnv;
import de.audi.tghu.navi.app.OperationManager;
import de.audi.tghu.navi.app.SpeechManager;
import de.audi.tghu.navi.app.audio.AudioStateMachine;
import de.audi.tghu.navi.app.map.MapManager;

/**
 * Stock BAP boundary for CarPlay KDK composition.
 *
 * The service gate forwards accepted Fct44/Fct54 Status, including stock replay.
 * The steering-wheel roller
 * (setMapScale) keeps zooming the head unit's own cluster map outside an acknowledged
 * VC panel; an owned panel reports unchanged scale instead. While
 * the AltScreen CarPlay video covers that map, each step also zooms the iPhone's
 * cluster map (AltScreenCluster).
 */
public final class ScreenCombiBAPListener extends CombiBAPListener {
    public ScreenCombiBAPListener(
        ClusterService service,
        LogChannel logChannel,
        NavigationEnv env,
        SpeechManager speechManager,
        OperationManager operationManager,
        AudioStateMachine audioStateMachine,
        MapManager mapManager,
        ICommandListFactory commandListFactory,
        IViewSizeManager viewSizeManager
    ) {
        super(
            service,
            logChannel,
            env,
            speechManager,
            operationManager,
            audioStateMachine,
            mapManager,
            commandListFactory,
            viewSizeManager
        );
    }

    public void setMapScale(int steps) {
        if(com.luka.carplay.settings.VcPanel.scale(steps)) {
            updateMapScale();
            return;
        }
        com.luka.carplay.framework.Log.i("VcInput","BAP_MAP_SCALE steps="+steps+" route=STOCK_AND_EXISTING_ALTSCREEN");
        com.luka.carplay.cluster.AltScreenCluster.onMapScaleSteps(steps);
        super.setMapScale(steps);
    }

    public void setMapPresentation(boolean largeMapView, boolean leftMenu, boolean rightMenu) {
        com.luka.carplay.framework.Log.i("VcInput","BAP_PRESENTATION large="+largeMapView
            +" left_menu="+leftMenu+" right_menu="+rightMenu+" input_owner=UNVERIFIED");
        super.setMapPresentation(largeMapView, leftMenu, rightMenu);
        com.luka.carplay.cluster.AltScreenCluster.onPresentation(largeMapView);
    }
}
