import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in dev-client capture. No renderer changes; readback happens AFTER the timing window. */
public final class ScenePerf {
    static Object field(Object o, String n) throws Exception {
        Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o);
    }
    static Object call(Object o, String n) throws Exception {
        Method m = o.getClass().getDeclaredMethod(n); m.setAccessible(true); return m.invoke(o);
    }
    public static void main(String[] a) throws Exception {
        if (a.length != 3) throw new IllegalArgumentException("PID agent.jar output-directory");
        VirtualMachine vm = VirtualMachine.attach(a[0]);
        try { vm.loadAgent(a[1], Path.of(a[2]).toAbsolutePath().toString()); } finally { vm.detach(); }
    }
    public static void agentmain(String out, Instrumentation unused) throws Exception {
        Path dir = Path.of(out); Files.createDirectories(dir);
        ClassLoader loader = Class.forName("net.runelite.client.RuneLite").getClassLoader();
        Object injector = Class.forName("net.runelite.client.RuneLite", true, loader).getMethod("getInjector").invoke(null);
        Method get = Class.forName("com.google.inject.Injector", true, loader).getMethod("getInstance", Class.class);
        Class<?> pmClass = Class.forName("net.runelite.client.plugins.PluginManager", true, loader);
        Object pm = get.invoke(injector, pmClass);
        Object plugin = null;
        for (Object p : (Collection<?>) pmClass.getMethod("getPlugins").invoke(pm))
            if (p.getClass().getSimpleName().equals("RuneLightPlugin")) plugin = p;
        if (plugin == null) throw new IllegalStateException("RuneLight is not running");
        Object p = plugin;
        Class<?> ctClass = Class.forName("net.runelite.client.callback.ClientThread", true, loader);
        Object ct = get.invoke(injector, ctClass);
        Method invoke = ctClass.getMethod("invoke", Runnable.class);
        new Thread(() -> {
            try {
                List<String> rows = new ArrayList<>();
                Object gpu = field(p, "gpuTimer"), cpu = field(p, "cpuTimer");
                if (!(boolean)call(gpu, "isEnabled")) throw new IllegalStateException("Enable Show GPU timings before capturing");
                String[] gpuNames = (String[]) field(gpu, "NAMES"), cpuNames = (String[]) field(cpu, "NAMES");
                StringBuilder header = new StringBuilder("frame,gpu_frame,camera_hash,frame_ms,cpu_elapsed_ms");
                for (String n : gpuNames) header.append(",gpu:").append(n);
                for (String n : cpuNames) header.append(",cpu:").append(n);
                rows.add(header.toString());
                long start = System.nanoTime();
                while (System.nanoTime() - start < 8_000_000_000L) {
                    onClient(invoke, ct, () -> {
                        StringBuilder r = new StringBuilder();
                        r.append(call(gpu, "getFrames")).append(',').append(call(gpu, "getReadFrame")).append(',')
                            .append(Arrays.hashCode((float[]) field(p, "frameViewProj"))).append(',')
                            .append(lastFrameMs(p)).append(',').append(call(p, "getCpuFrameMs"));
                        double[] times = (double[]) field(gpu, "frame");
                        boolean[] present = (boolean[]) field(gpu, "present");
                        for (int i = 0; i < times.length; i++) r.append(',').append(present[i] ? Double.toString(times[i]) : "");
                        for (double t : (double[]) field(cpu, "last")) r.append(',').append(t);
                        rows.add(r.toString());
                    });
                    Thread.sleep(20);
                }
                Files.write(dir.resolve("timings.csv"), rows);
                Map<String, Object> meta = new LinkedHashMap<>();
                onClient(invoke, ct, () -> {
                    Object targets = field(p, "sceneTargets"), lights = field(p, "lightClusters");
                    for (String n : new String[]{"width", "height", "aoWidth", "aoHeight", "halfRatio", "samples"}) meta.put(n, field(targets, n));
                    for (String n : new String[]{"frameViewport", "frameViewProj", "frameInvViewProj", "frameCameraX", "frameCameraY", "frameCameraZ"}) meta.put(n, field(p, n));
                    meta.put("compositeCombined", Boolean.parseBoolean(System.getProperty("runelight.compositeCombined", "true")));
                    meta.put("camera_hash", Arrays.hashCode((float[]) field(p, "frameViewProj")));
                    meta.put("depthZeroToOne", field(field(p, "glCapabilities"), "OpenGL45"));
                    for (String n : new String[]{"lightCount", "fogCandidates", "overflows", "CLUSTERS_X", "CLUSTERS_Y", "CLUSTERS_Z", "MAX_LIGHTS_PER_CLUSTER", "CLUSTER_ENTRIES", "NEAR", "FAR"}) meta.put(n, field(lights, n));
                    meta.put("gpuStages", gpuNames); meta.put("cpuStages", cpuNames);
                    Map<String,Object> config = new TreeMap<>();
                    Object cfg = field(p, "config");
                    Class<?> configClass = Class.forName("com.midonarhel.runelight.RuneLightConfig", true, loader);
                    for (Method m : configClass.getMethods()) if (m.getParameterCount() == 0 && m.getDeclaringClass() == configClass)
                        config.put(m.getName(), String.valueOf(m.invoke(cfg)));
                    meta.put("config", config);
                    FloatBuffer data = ((FloatBuffer)field(lights, "lightData")).duplicate();
                    float[] fs = new float[data.limit()]; data.position(0); data.get(fs); writeFloats(dir.resolve("lights.f32"), fs);
                    data = ((FloatBuffer)field(lights, "extraData")).duplicate(); fs = new float[data.limit()]; data.position(0); data.get(fs); writeFloats(dir.resolve("light-extra.f32"), fs);
                    int texels = (int) field(lights, "texels"); meta.put("CLUSTER_TEXELS", texels);
                    int[] cs = Arrays.copyOf((int[]) field(lights, "masks"), (int) field(lights, "CLUSTER_ENTRIES") * texels * 4);
                    ByteBuffer bytes = ByteBuffer.allocate(cs.length * 4).order(ByteOrder.LITTLE_ENDIAN); bytes.asIntBuffer().put(cs); Files.write(dir.resolve("clusters.u32"), bytes.array());
                    int w = (int)field(targets,"width"), h = (int)field(targets,"height");
                    texture(loader, dir, targets, "texDepth", "depth", w, h, 0x1902, 1);
                    texture(loader, dir, targets, "texNormal", "normal", w, h, 0x1907, 3);
                    texture(loader, dir, targets, "texMaterial", "material", w, h, 0x1907, 3);
                    texture(loader, dir, targets, "texColor", "color", w, h, 0x1908, 4);
                });
                Object gson = Class.forName("com.google.gson.Gson", true, loader).getConstructor().newInstance();
                Files.writeString(dir.resolve("scene.json"), (String)gson.getClass().getMethod("toJson", Object.class).invoke(gson, meta));
                Files.writeString(dir.resolve("DONE"), "Timing excludes the synchronous texture readback. Binary floats/ints are little endian, rows bottom-up.\n");
            } catch (Throwable e) {
                try { java.io.StringWriter s = new java.io.StringWriter(); e.printStackTrace(new java.io.PrintWriter(s)); Files.writeString(dir.resolve("ERROR"), s.toString()); } catch (Exception ignored) {}
            }
        }, "RuneLight scene performance capture").start();
    }
    static double lastFrameMs(Object plugin) throws Exception {
        Object monitor = field(plugin, "hitchMonitor");
        double[] history = (double[])field(monitor, "history");
        int count = (int)field(monitor, "count");
        return count > 0 ? history[(count - 1) % history.length] : Double.NaN;
    }
    interface Work { void run() throws Exception; }
    static void onClient(Method invoke, Object ct, Work work) throws Exception {
        CountDownLatch done = new CountDownLatch(1); Throwable[] error = new Throwable[1];
        invoke.invoke(ct, (Runnable) () -> { try { work.run(); } catch (Throwable e) { error[0] = e; } finally { done.countDown(); } });
        if (!done.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Client callback timeout");
        if (error[0] != null) throw new RuntimeException(error[0]);
    }
    static void writeFloats(Path path, float[] values) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN); b.asFloatBuffer().put(values); Files.write(path, b.array());
    }
    static void texture(ClassLoader loader, Path dir, Object targets, String name, String file, int w, int h, int format, int channels) throws Exception {
        Class<?> gl = Class.forName("org.lwjgl.opengl.GL33C", true, loader);
        Method get = gl.getMethod("glGetInteger", int.class), bind = gl.getMethod("glBindTexture", int.class, int.class);
        Method bindBuffer = gl.getMethod("glBindBuffer", int.class, int.class), pixel = gl.getMethod("glPixelStorei", int.class,int.class);
        int oldTexture = (int)get.invoke(null, 0x8069), oldBuffer = (int)get.invoke(null, 0x88ED);
        int[] names = {0x0D05,0x0D02,0x0D03,0x0D04}; int[] old = new int[names.length];
        for (int i=0;i<names.length;i++) old[i]=(int)get.invoke(null,names[i]);
        try {
            bindBuffer.invoke(null,0x88EB,0); for(int n:names) pixel.invoke(null,n,n==0x0D05?4:0);
            bind.invoke(null,0x0DE1,(int)field(targets,name));
            ByteBuffer bytes=ByteBuffer.allocateDirect(w*h*channels*4).order(ByteOrder.nativeOrder());
            gl.getMethod("glGetTexImage",int.class,int.class,int.class,int.class,FloatBuffer.class).invoke(null,0x0DE1,0,format,0x1406,bytes.asFloatBuffer());
            byte[] copy=new byte[bytes.capacity()]; bytes.get(copy); Files.write(dir.resolve(file+".f32"),copy);
        } finally {
            bind.invoke(null,0x0DE1,oldTexture); bindBuffer.invoke(null,0x88EB,oldBuffer);
            for(int i=0;i<names.length;i++) pixel.invoke(null,names[i],old[i]);
        }
    }
}
