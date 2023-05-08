package nonapi.io.github.classgraph.reflection;

import nonapi.io.github.classgraph.utils.VersionFinder;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/**
 * Standard reflection driver, except with a couple of hacks for
 * circumventing encapsulation without JNI or a special library.
 *
 * @author Matouš Kučera
 */
class UnsafeReflectionDriver extends StandardReflectionDriver {
    private static boolean patched = VersionFinder.JAVA_MAJOR_VERSION < 9;

    UnsafeReflectionDriver() {
        if (!patched) {
            try {
                final Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                final Unsafe unsafe = (Unsafe) unsafeField.get(null);

                final Field implLookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
                MethodHandles.publicLookup();
                final MethodHandles.Lookup implLookup = (MethodHandles.Lookup) unsafe.getObject(
                        unsafe.staticFieldBase(implLookupField),
                        unsafe.staticFieldOffset(implLookupField)
                );

                final MethodType moduleType = MethodType.methodType(Module.class);
                final MethodHandle classModule = implLookup.findVirtual(Class.class, "getModule", moduleType);
                final MethodHandle classLoaderModule = implLookup.findVirtual(ClassLoader.class, "getUnnamedModule", moduleType);
                final MethodHandle methodModifiers = implLookup.findSetter(Method.class, "modifiers", Integer.TYPE);

                final Method implAddOpensMethod = Module.class.getDeclaredMethod("implAddOpens", String.class);
                methodModifiers.invokeExact(implAddOpensMethod, Modifier.PUBLIC);

                final Set<Module> modules = new HashSet<>();

                final Module base = (Module) classModule.invokeExact(UnsafeReflectionDriver.class);
                if (base.getLayer() != null) {
                    modules.addAll(base.getLayer().modules());
                }
                modules.addAll(ModuleLayer.boot().modules());
                for (ClassLoader cl = UnsafeReflectionDriver.class.getClassLoader(); cl != null; cl = cl.getParent()) {
                    modules.add((Module) classLoaderModule.invokeExact(cl));
                }

                for (final Module module : modules) {
                    for (final String name : module.getPackages()) {
                        implAddOpensMethod.invoke(module, name);
                    }
                }

                patched = true;
            } catch (Throwable t) {
                throw new RuntimeException("Failed to patch encapsulation", t);
            }
        }
    }
}
