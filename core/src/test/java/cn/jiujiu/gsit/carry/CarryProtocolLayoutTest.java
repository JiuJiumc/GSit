package cn.jiujiu.gsit.carry;

import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;

/** Verify packet-only adapters against pinned protocol bytecode, without instantiating world entities. */
final class CarryProtocolLayoutTest {
    static int checks;
    static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    static List<String> dataFields(JarFile jar, String name) throws Exception {
        var clazz = ClassFile.of().parse(jar.getInputStream(jar.getJarEntry(name+".class")).readAllBytes());
        var names = new ArrayList<String>(); boolean define = false;
        for (var m : clazz.methods()) if (m.methodName().equalsString("<clinit>")) {
            for (var e : m.code().orElseThrow()) {
                if (e instanceof InvokeInstruction i && i.owner().asInternalName().equals("net/minecraft/network/syncher/SynchedEntityData")
                        && i.name().equalsString("defineId")) define = true;
                if (define && e instanceof FieldInstruction f && f.opcode() == Opcode.PUTSTATIC) {
                    names.add(f.name().stringValue()); define = false;
                }
            }
        }
        return names;
    }
    static int run() throws Exception {
        try (var output = new JarFile(System.getProperty("gsit.test.jar"))) {
            for (var version : List.of("v26_2", "v26_3")) {
                try (var server = new JarFile(Path.of("mcv",version,".gradle/caches/paperweight/taskCache/mappedServerJar.jar").toFile())) {
                    check(dataFields(server,"net/minecraft/world/entity/Entity").size() == 8,version+" Entity metadata count");
                    check(dataFields(server,"net/minecraft/world/entity/Interaction").equals(List.of("DATA_WIDTH_ID","DATA_HEIGHT_ID","DATA_RESPONSE_ID")),version+" Interaction metadata layout");
                }
                var entry = output.getJarEntry("dev/geco/gsit/mcv/"+version+"/model/CarryInputTarget.class");
                var clazz = ClassFile.of().parse(output.getInputStream(entry).readAllBytes());
                for (var method : clazz.methods()) if (method.code().isPresent()) {
                    for (var e : method.code().get()) if (e instanceof InvokeInstruction i) {
                        String owner = i.owner().asInternalName();
                        if (owner.equals("net/minecraft/world/entity/Interaction")
                                || owner.endsWith("/CraftInteraction") || i.name().equalsString("getBukkitEntity")
                                || i.name().equalsString("snapTo") || i.name().equalsString("setPosRaw"))
                            throw new AssertionError(version+" reintroduced unowned phantom entity access: "+i);
                    }
                }
                check(true,version+" spawn/move/cleanup contain no phantom entity access");
            }
        }
        return checks;
    }
}
