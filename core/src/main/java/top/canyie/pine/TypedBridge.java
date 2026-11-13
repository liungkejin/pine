package top.canyie.pine;

import android.annotation.TargetApi;
import dalvik.system.InMemoryDexClassLoader;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Adler32;

/**
 * A bridge with ART-visible reference parameters. The legacy register bridge stores references
 * as integers across safepoints, where a moving GC cannot update them. Keep the original shorty
 * (plus an explicit reference receiver) so ART owns the roots before any allocation or JNI call.
 * References are erased to Object, allowing hooks on types from arbitrary class loaders.
 */
@TargetApi(26)
final class TypedBridge {
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    static Method create(Pine.HookRecord record, Class<?> returnType) {
        Class<?>[] parameters = new Class<?>[record.paramNumber + (record.isStatic ? 0 : 1)];
        int start = record.isStatic ? 0 : 1;
        if (start != 0) parameters[0] = Object.class;
        for (int i = 0; i < record.paramNumber; i++) {
            Class<?> type = record.paramTypes[i];
            parameters[start + i] = type.isPrimitive() ? type : Object.class;
        }
        String name = "top.canyie.pine.bridge.Bridge" + NEXT_ID.getAndIncrement();
        byte[] dex = new Dex(name, parameters, returnType, record.isStatic).build();
        try {
            ClassLoader loader = new InMemoryDexClassLoader(ByteBuffer.wrap(dex), Pine.class.getClassLoader());
            Class<?> bridge = Class.forName(name, true, loader);
            bridge.getField("record").set(null, record);
            // HookRecord.bridge retains this class and its loader for the trampoline's lifetime.
            return bridge.getDeclaredMethod("invoke", parameters);
        } catch (ReflectiveOperationException e) {
            // Never fall back to unrooted integer arguments if generation/loading fails.
            throw new IllegalStateException("Cannot create GC-safe bridge for " + record.target, e);
        }
    }

    /** Minimal DEX 035 writer for one static field and one straight-line static method.
     * Format: https://source.android.com/docs/core/runtime/dex-format
     * Instructions: https://source.android.com/docs/core/runtime/dalvik-bytecode
     */
    private static final class Dex {
        private static final String OBJECT = "Ljava/lang/Object;";
        private static final String ARRAY = "[Ljava/lang/Object;";
        private static final String RECORD = "Ltop/canyie/pine/Pine$HookRecord;";
        private static final int LOCALS = 8;
        private final String owner;
        private final Class<?>[] parameters;
        private final Class<?> returnType;
        private final boolean isStatic;
        private final List<String> strings, types;
        private final List<Proto> protos;
        private final List<MethodId> methods;
        private final MethodId invoke, handleCall;

        Dex(String name, Class<?>[] parameters, Class<?> returnType, boolean isStatic) {
            this.owner = "L" + name.replace('.', '/') + ";";
            this.parameters = parameters;
            this.returnType = returnType;
            this.isStatic = isStatic;
            String[] args = new String[parameters.length];
            for (int i = 0; i < args.length; i++) args[i] = descriptor(parameters[i]);
            invoke = new MethodId(owner, "invoke", descriptor(returnType), args);
            handleCall = new MethodId("Ltop/canyie/pine/Pine;", "handleCall", OBJECT, RECORD, OBJECT, ARRAY);
            TreeSet<MethodId> methodSet = new TreeSet<>();
            methodSet.add(invoke);
            methodSet.add(handleCall);
            for (Class<?> type : parameters) if (type.isPrimitive()) methodSet.add(box(type));
            if (returnType.isPrimitive() && returnType != void.class) methodSet.add(unbox(returnType));
            methods = new ArrayList<>(methodSet);
            TreeSet<Proto> protoSet = new TreeSet<>();
            TreeSet<String> typeSet = new TreeSet<>(Arrays.asList(owner, OBJECT, ARRAY, RECORD));
            TreeSet<String> stringSet = new TreeSet<>(Collections.singleton("record"));
            for (MethodId method : methods) {
                protoSet.add(method.proto);
                typeSet.add(method.owner);
                typeSet.add(method.proto.result);
                typeSet.addAll(Arrays.asList(method.proto.args));
                stringSet.add(method.name);
                stringSet.add(method.proto.shorty());
            }
            protos = new ArrayList<>(protoSet);
            types = new ArrayList<>(typeSet);
            stringSet.addAll(types);
            strings = new ArrayList<>(stringSet);
        }

        byte[] build() {
            int stringIds = 112;
            int typeIds = stringIds + strings.size() * 4;
            int protoIds = typeIds + types.size() * 4;
            int fieldIds = protoIds + protos.size() * 12;
            int methodIds = fieldIds + 8;
            int classDefs = methodIds + methods.size() * 8;
            int dataOffset = classDefs + 32;
            Bytes out = new Bytes();
            for (int i = 0; i < dataOffset; i++) out.u8(0);
            List<int[]> map = new ArrayList<>();
            map.add(new int[] {0, 1, 0});
            map.add(new int[] {1, strings.size(), stringIds});
            map.add(new int[] {2, types.size(), typeIds});
            map.add(new int[] {3, protos.size(), protoIds});
            map.add(new int[] {4, 1, fieldIds});
            map.add(new int[] {5, methods.size(), methodIds});
            map.add(new int[] {6, 1, classDefs});
            map.add(new int[] {0x2002, strings.size(), out.size()});
            int[] stringOffsets = new int[strings.size()];
            for (int i = 0; i < strings.size(); i++) {
                stringOffsets[i] = out.size();
                String value = strings.get(i);
                out.uleb(value.length());
                // All strings are generated ASCII names/descriptors, never user type names.
                for (int j = 0; j < value.length(); j++) out.u8(value.charAt(j));
                out.u8(0);
            }
            out.align();
            int typeListStart = out.size(), typeListCount = 0;
            int[] paramOffsets = new int[protos.size()];
            for (int i = 0; i < protos.size(); i++) {
                String[] args = protos.get(i).args;
                if (args.length == 0) continue;
                out.align();
                paramOffsets[i] = out.size();
                typeListCount++;
                out.u32(args.length);
                for (String arg : args) out.u16(types.indexOf(arg));
            }
            map.add(new int[] {0x1001, typeListCount, typeListStart});
            out.align();
            int codeOffset = out.size();
            map.add(new int[] {0x2001, 1, codeOffset});
            int ins = 0;
            for (Class<?> type : parameters) ins += width(type);
            byte[] code = code();
            out.u16(LOCALS + ins); // registers_size
            out.u16(ins);         // ins_size
            out.u16(3);           // outs_size: handleCall(record, receiver, args)
            out.u16(0);           // tries_size
            out.u32(0);           // debug_info_off
            out.u32(code.length / 2);
            out.write(code, 0, code.length);
            int classData = out.size();
            map.add(new int[] {0x2000, 1, classData});
            out.uleb(1); out.uleb(0); out.uleb(1); out.uleb(0);
            out.uleb(0); out.uleb(0x9); // static field: public static
            out.uleb(methods.indexOf(invoke)); out.uleb(0x9); out.uleb(codeOffset);
            out.align();
            int mapOffset = out.size();
            map.add(new int[] {0x1000, 1, mapOffset});
            out.u32(map.size());
            for (int[] entry : map) {
                out.u16(entry[0]); out.u16(0); out.u32(entry[1]); out.u32(entry[2]);
            }
            byte[] dex = out.toByteArray();
            ByteBuffer b = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);
            b.put(new byte[] {'d', 'e', 'x', '\n', '0', '3', '5', 0});
            b.position(32);
            b.putInt(dex.length).putInt(112).putInt(0x12345678).putInt(0).putInt(0).putInt(mapOffset);
            b.putInt(strings.size()).putInt(stringIds).putInt(types.size()).putInt(typeIds);
            b.putInt(protos.size()).putInt(protoIds).putInt(1).putInt(fieldIds);
            b.putInt(methods.size()).putInt(methodIds).putInt(1).putInt(classDefs);
            b.putInt(dex.length - dataOffset).putInt(dataOffset);
            for (int offset : stringOffsets) b.putInt(offset);
            for (String type : types) b.putInt(strings.indexOf(type));
            for (int i = 0; i < protos.size(); i++) {
                Proto proto = protos.get(i);
                b.putInt(strings.indexOf(proto.shorty())).putInt(types.indexOf(proto.result)).putInt(paramOffsets[i]);
            }
            b.putShort((short) types.indexOf(owner)).putShort((short) types.indexOf(RECORD));
            b.putInt(strings.indexOf("record"));
            for (MethodId method : methods) {
                b.putShort((short) types.indexOf(method.owner));
                b.putShort((short) Collections.binarySearch(protos, method.proto));
                b.putInt(strings.indexOf(method.name));
            }
            b.putInt(types.indexOf(owner)).putInt(0x11).putInt(types.indexOf(OBJECT));
            b.putInt(0).putInt(-1).putInt(0).putInt(classData).putInt(0);
            try {
                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                sha1.update(dex, 32, dex.length - 32);
                b.position(12); b.put(sha1.digest());
            } catch (NoSuchAlgorithmException e) {
                throw new AssertionError(e);
            }
            Adler32 checksum = new Adler32();
            checksum.update(dex, 12, dex.length - 12);
            b.putInt(8, (int) checksum.getValue());
            return dex;
        }

        private byte[] code() {
            Bytes c = new Bytes();
            int start = isStatic ? 0 : 1;
            c.u16(0x0113); c.u16(parameters.length - start); // const/16 v1, argument count
            c.u16(0x1023); c.u16(types.indexOf(ARRAY));       // new-array v0, v1, Object[]
            int source = LOCALS + start;
            for (int i = start; i < parameters.length; i++) {
                Class<?> type = parameters[i];
                c.u16(0x0113); c.u16(i - start); // const/16 v1, index
                if (type.isPrimitive()) {
                    c.u16((width(type) == 2 ? 0x05 : 0x02) | (6 << 8));
                    c.u16(source); // move[-wide]/from16 v6, parameter
                    call(c, 0x71, box(type), width(type) == 2 ? new int[] {6, 7} : new int[] {6});
                    c.u16(0x020c); // move-result-object v2
                } else {
                    c.u16(0x0208); c.u16(source); // move-object/from16 v2, parameter
                }
                c.u16(0x024d); c.u16(0x0100); // aput-object v2, v0, v1
                source += width(type);
            }
            c.u16(0x0362); c.u16(0); // sget-object v3, record
            if (isStatic) {
                c.u16(0x0412); // const/4 v4, null
            } else {
                c.u16(0x0408); c.u16(LOCALS); // move-object/from16 v4, receiver
            }
            call(c, 0x71, handleCall, new int[] {3, 4, 0});
            if (returnType == void.class) {
                c.u16(0x0e); // return-void
            } else if (!returnType.isPrimitive()) {
                c.u16(0x000c); c.u16(0x0011); // move-result-object v0; return-object v0
            } else {
                c.u16(0x000c); // move-result-object v0
                MethodId unbox = unbox(returnType);
                c.u16(0x001f); c.u16(types.indexOf(unbox.owner)); // check-cast v0, wrapper
                call(c, 0x6e, unbox, new int[] {0});
                c.u16(width(returnType) == 2 ? 0x000b : 0x000a);
                c.u16(width(returnType) == 2 ? 0x0010 : 0x000f);
            }
            return c.toByteArray();
        }

        private void call(Bytes code, int opcode, MethodId method, int[] registers) {
            // format 35c; all outgoing arguments fit in low registers and at most three words.
            code.u16(opcode | (registers.length << 12));
            code.u16(Collections.binarySearch(methods, method));
            int packed = 0;
            for (int i = 0; i < registers.length; i++) packed |= registers[i] << (i * 4);
            code.u16(packed);
        }

        private static String descriptor(Class<?> type) {
            if (!type.isPrimitive()) return OBJECT;
            if (type == void.class) return "V";
            if (type == boolean.class) return "Z";
            if (type == byte.class) return "B";
            if (type == char.class) return "C";
            if (type == short.class) return "S";
            if (type == int.class) return "I";
            if (type == long.class) return "J";
            if (type == float.class) return "F";
            if (type == double.class) return "D";
            throw new AssertionError(type);
        }

        private static String wrapper(Class<?> type) {
            String name = type == int.class ? "Integer" : type == char.class ? "Character"
                    : Character.toUpperCase(type.getName().charAt(0)) + type.getName().substring(1);
            return "Ljava/lang/" + name + ";";
        }

        private static MethodId box(Class<?> type) {
            return new MethodId(wrapper(type), "valueOf", wrapper(type), descriptor(type));
        }

        private static MethodId unbox(Class<?> type) {
            return new MethodId(wrapper(type), type.getName() + "Value", descriptor(type));
        }

        private static int width(Class<?> type) {
            return type == long.class || type == double.class ? 2 : 1;
        }
    }

    private static final class Proto implements Comparable<Proto> {
        final String result;
        final String[] args;
        Proto(String result, String[] args) { this.result = result; this.args = args; }
        String shorty() {
            StringBuilder value = new StringBuilder().append(shortType(result));
            for (String arg : args) value.append(shortType(arg));
            return value.toString();
        }
        private static char shortType(String type) { return type.length() == 1 ? type.charAt(0) : 'L'; }
        @Override public int compareTo(Proto other) {
            int order = result.compareTo(other.result);
            if (order != 0) return order;
            for (int i = 0; i < Math.min(args.length, other.args.length); i++) {
                order = args[i].compareTo(other.args[i]);
                if (order != 0) return order;
            }
            return Integer.compare(args.length, other.args.length);
        }
    }

    private static final class MethodId implements Comparable<MethodId> {
        final String owner, name;
        final Proto proto;
        MethodId(String owner, String name, String result, String... args) {
            this.owner = owner; this.name = name; this.proto = new Proto(result, args);
        }
        @Override public int compareTo(MethodId other) {
            int order = owner.compareTo(other.owner);
            if (order == 0) order = name.compareTo(other.name);
            return order == 0 ? proto.compareTo(other.proto) : order;
        }
    }

    private static final class Bytes extends ByteArrayOutputStream {
        void u8(int value) { write(value); }
        void u16(int value) { write(value); write(value >>> 8); }
        void u32(int value) { u16(value); u16(value >>> 16); }
        void align() { while ((size() & 3) != 0) write(0); }
        void uleb(int value) {
            do {
                int next = value & 0x7f;
                value >>>= 7;
                write(value == 0 ? next : next | 0x80);
            } while (value != 0);
        }
    }
}
