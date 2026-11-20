package dev.tearsmilk.textshader;

final class PapiHook {

    private PapiHook() {
    }

    static void register(TextShaderPlugin plugin) {
        new Papi(plugin).register();
    }
}
