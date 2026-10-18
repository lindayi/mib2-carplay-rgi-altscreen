package com.luka.carplay.rgd;

import com.luka.carplay.framework.ElapsedClock;
import com.luka.carplay.framework.Log;
import de.audi.tghu.navi.app.Navigation;
import de.audi.tghu.navi.app.cluster.ClusterService;
import java.util.ArrayList;

/** Coalesced model/KOMO work. Stock BAP state is kept separate by ClusterService. */
public final class LowerBarKomo {
    private static final int ROAD = 1, DISTANCE = 2, ARRIVAL = 4;
    private static final ArrayList pending = new ArrayList();
    private final ClusterService target;
    private String road;
    private int distance = -1, dirty, ticket, revision;
    private long arrival = -1L, submittedAt, lastAttempt, lastWarning;
    private boolean zoneOffset, retired, scheduled, applying;

    LowerBarKomo(ClusterService target) { this.target = target; }

    boolean targets(ClusterService service) { return target == service; }

    synchronized void update(int changed, String road, int distance, long arrival, boolean offset) {
        if (retired) return;
        ++revision;
        if ((changed & RouteGuidance.State.DIRTY_CURRENT_ROAD) != 0) {
            this.road = road;
            dirty |= ROAD;
        }
        if ((changed & RouteGuidance.State.DIRTY_DIST_DEST) != 0) {
            this.distance = distance;
            dirty |= DISTANCE;
        }
        if ((changed & (RouteGuidance.State.DIRTY_ETA | RouteGuidance.State.DIRTY_TIME_REMAINING
                | RouteGuidance.State.DIRTY_DEST_TIMEZONE)) != 0) {
            this.arrival = arrival;
            zoneOffset = offset;
            dirty |= ARRIVAL;
        }
        queue();
        schedule();
    }

    synchronized void clear() {
        retired = true;
        ++revision;
        road = null;
        distance = -1;
        arrival = -1L;
        dirty = ROAD | DISTANCE | ARRIVAL;
        queue();
        schedule();
    }

    private void queue() {
        synchronized (pending) {
            if (dirty != 0) {
                if (!pending.contains(this)) pending.add(this);
            } else pending.remove(this);
        }
    }

    private synchronized void schedule() {
        long now = ElapsedClock.nowMs();
        if (scheduled && now - submittedAt >= 5000L) {
            scheduled = false;
            ++ticket;
            warn("NavigationJobs task expired", null);
        }
        if (dirty == 0 || scheduled || applying) return;
        if (lastAttempt != 0L && now - lastAttempt < 2000L) return;
        Navigation navigation = Navigation.getInstance();
        if (navigation == null || navigation.getDispatcher() == null) return;
        lastAttempt = now;
        submittedAt = now;
        scheduled = true;
        final int expected = ++ticket;
        try {
            navigation.getDispatcher().execute(new Runnable() {
                public void run() { apply(expected); }
            });
        } catch (Throwable failure) {
            scheduled = false;
            warn("NavigationJobs submission failed", failure);
        }
    }

    private void apply(int expected) {
        final int version, fields, nextDistance;
        final String nextRoad;
        final long nextArrival;
        final boolean nextOffset, clearing;
        synchronized (this) {
            if (!scheduled || ticket != expected) return;
            scheduled = false;
            applying = true;
            version = revision;
            fields = dirty;
            nextRoad = road;
            nextDistance = distance;
            nextArrival = arrival;
            nextOffset = zoneOffset;
            clearing = retired;
        }
        int accepted = 0;
        boolean replaced = false;
        try {
            Navigation navigation = Navigation.getInstance();
            ClusterService current = navigation == null ? null : navigation.getClusterService();
            if (current == null) return;
            if (current != target) {
                target.abandonCarplayLowerBar(this);
                replaced = true;
                return;
            }
            for (int field = ROAD; field <= ARRIVAL; field <<= 1) {
                if ((fields & field) == 0) continue;
                try {
                    if (field == ROAD) target.updateCarplayCurrentStreet(this, nextRoad);
                    else if (field == DISTANCE) target.updateCarplayDestinationDistance(this, nextDistance);
                    else target.updateCarplayArrival(this, nextArrival, nextOffset);
                    accepted |= field;
                } catch (Throwable failure) {
                    warn("Model/KOMO " + (clearing ? "restore" : "update") + " failed, field=" + field, failure);
                }
            }
        } catch (Throwable failure) {
            warn("Navigation target unavailable", failure);
        } finally {
            synchronized (this) {
                applying = false;
                if (replaced) {
                    retired = true;
                    dirty = 0;
                } else if (revision == version) dirty &= ~accepted;
                if (dirty == 0 || revision != version) lastAttempt = 0L;
                queue();
                if (revision != version) schedule();
            }
        }
    }

    private synchronized void warn(String message, Throwable failure) {
        long now = ElapsedClock.nowMs();
        if (lastWarning != 0L && now - lastWarning < 10000L) return;
        lastWarning = now;
        Log.w("LowerBarKomo", message + (failure == null ? "" : ": " + failure));
    }

    /** Resident lifecycle monitor also retries cleanup after RGI has stopped. */
    public static void retryPending() {
        LowerBarKomo[] snapshot;
        synchronized (pending) {
            snapshot = new LowerBarKomo[pending.size()];
            pending.toArray(snapshot);
        }
        for (int i = 0; i < snapshot.length; ++i) snapshot[i].schedule();
    }
}
