package core.amazake.systems.coremechanics.host;

import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable;
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable;
import com.github.stephengold.joltjni.ObjectVsBroadPhaseLayerFilterTable;
import com.github.stephengold.joltjni.PhysicsSystem;

/**
 * Слои коллизий. Держатся сильными ссылками всё время жизни физ-систем: PhysicsSystem хранит на них нативные
 * указатели, и если обёртку соберёт GC — краш JVM. Нужны только рэгдоллам: STATIC — меш мира, MOVING — части кукол.
 */
final class JoltLayers {

    static final int STATIC = 0, MOVING = 1, NUM_OBJ = 2;
    static final int BP_STATIC = 0, BP_MOVING = 1, NUM_BP = 2;

    final ObjectLayerPairFilterTable pairs;
    final BroadPhaseLayerInterfaceTable bpLayers;
    final ObjectVsBroadPhaseLayerFilterTable objVsBp;

    JoltLayers() {
        this.pairs = new ObjectLayerPairFilterTable(NUM_OBJ);
        this.pairs.enableCollision(MOVING, STATIC);
        this.pairs.enableCollision(MOVING, MOVING);
        this.pairs.disableCollision(STATIC, STATIC);

        this.bpLayers = new BroadPhaseLayerInterfaceTable(NUM_OBJ, NUM_BP);
        this.bpLayers.mapObjectToBroadPhaseLayer(STATIC, BP_STATIC);
        this.bpLayers.mapObjectToBroadPhaseLayer(MOVING, BP_MOVING);

        this.objVsBp = new ObjectVsBroadPhaseLayerFilterTable(this.bpLayers, NUM_BP, this.pairs, NUM_OBJ);
    }

    PhysicsSystem newSystem(final int maxBodies, final int maxBodyPairs, final int maxContacts) {
        final PhysicsSystem sys = new PhysicsSystem();
        sys.init(maxBodies, 0, maxBodyPairs, maxContacts, this.bpLayers, this.objVsBp, this.pairs);
        return sys;
    }

    void close() {
        this.objVsBp.close();
        this.bpLayers.close();
        this.pairs.close();
    }
}
