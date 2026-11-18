package core.amazake.systems.coremechanics.host;

import com.github.stephengold.joltjni.JobSystemThreadPool;
import com.github.stephengold.joltjni.Jolt;
import com.github.stephengold.joltjni.JoltPhysicsObject;
import com.github.stephengold.joltjni.TempAllocatorMalloc;
import core.amazake.systems.coremechanics.physics.PhysicsHost;
import core.amazake.systems.coremechanics.physics.PhysicsWorld;

/**
 * Физдвижок на Jolt (jolt-jni). Класс определяет HostLoader — новый на каждое включение системы; классы Jolt и его
 * нативка — общие на JVM. Поэтому то, что делается раз на жизнь нативки (cleaner, аллокатор, фабрика типов), помечено
 * флагом в {@link System#getProperties()}, а не в статическом поле этого класса.
 */
public final class JoltHost implements PhysicsHost {

    /** Флаг «фабрика типов Jolt уже создана в этой JVM». */
    static final String FACTORY_KEY = "core-mechanics.jolt.factory";

    private JoltLayers layers;
    private TempAllocatorMalloc temp;
    private JobSystemThreadPool jobs;
    private String version = "?";
    private String selfTest = "";
    private int threads;

    public JoltHost() {
    }

    @Override
    public void start(final int wantThreads) throws Exception {
        initFactoryOnce();
        this.version = Jolt.versionString();
        this.layers = new JoltLayers();
        this.temp = new TempAllocatorMalloc();
        final int cores = Runtime.getRuntime().availableProcessors();
        this.threads = wantThreads > 0 ? wantThreads : Math.max(1, Math.min(4, cores / 2));
        this.jobs = new JobSystemThreadPool(Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, this.threads);
        final JoltSelfTest.Outcome o = JoltSelfTest.quick(this.layers, this.temp, this.jobs);
        this.selfTest = o.report();
        if (!o.ok()) {
            this.shutdown();
            throw new IllegalStateException("самотест физики провален: " + o.report());
        }
    }

    /** Раз на JVM: cleaner обёрток, аллокатор, колбэки, фабрика и регистрация типов. */
    static void initFactoryOnce() {
        final java.util.Properties props = System.getProperties();
        synchronized (props) {
            if ("1".equals(props.getProperty(FACTORY_KEY))) {
                return;
            }
            JoltPhysicsObject.startCleaner();
            Jolt.registerDefaultAllocator();
            Jolt.installDefaultAssertCallback();
            Jolt.installDefaultTraceCallback();
            if (!Jolt.newFactory()) {
                throw new IllegalStateException("Jolt.newFactory() вернул false");
            }
            Jolt.registerTypes();
            props.setProperty(FACTORY_KEY, "1");
        }
    }

    @Override
    public String version() {
        return this.version;
    }

    @Override
    public String selfTest() {
        return this.selfTest;
    }

    @Override
    public int threads() {
        return this.threads;
    }

    @Override
    public PhysicsWorld newWorld(final int maxBodies, final float gravity, final int substeps) {
        return new JoltWorld(this.layers, this.temp, this.jobs, maxBodies, gravity, substeps);
    }

    @Override
    public void shutdown() {
        if (this.jobs != null) {
            this.jobs.close();
            this.jobs = null;
        }
        if (this.temp != null) {
            this.temp.close();
            this.temp = null;
        }
        if (this.layers != null) {
            this.layers.close();
            this.layers = null;
        }
    }
}
