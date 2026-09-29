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
 * VC's Fct44 (KDK visibility) and Fct54 (map presentation/stage) are forwarded to
 * the layer controller before stock acknowledges them.  The steering-wheel roller
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

    /** Apply the accepted stock state before its Status acknowledgement. This boundary
     * also covers internal supplementary visibility changes and initial Status replay,
     * which bypass the two-argument BAP request setter. */
    protected void updateMapVisibility() {
        com.luka.carplay.cluster.ClusterLayerController.onVcVisibility(this.supplementaryMapViewVisible);
        super.updateMapVisibility();
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
        com.luka.carplay.cluster.ClusterLayerController.onVcPresentation(largeMapView);
        super.setMapPresentation(largeMapView, leftMenu, rightMenu);
        com.luka.carplay.cluster.AltScreenCluster.onPresentation(largeMapView);
        com.luka.carplay.settings.VcPanel.presentation(largeMapView,leftMenu,rightMenu);
    }
}
