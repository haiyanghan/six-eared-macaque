package six.eared.macaque.agent.accessor;

import javassist.CannotCompileException;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.bytecode.AccessFlag;
import org.objectweb.asm.Type;
import six.eared.macaque.agent.asm2.AsmField;
import six.eared.macaque.agent.asm2.AsmMethod;
import six.eared.macaque.agent.asm2.AsmUtil;
import six.eared.macaque.agent.javassist.JavassistClassBuilder;
import six.eared.macaque.agent.asm2.classes.ClazzDefinition;
import six.eared.macaque.agent.enhance.AccessorClassNameGenerator;
import six.eared.macaque.agent.enhance.CompatibilityModeClassLoader;
import six.eared.macaque.agent.env.Environment;
import six.eared.macaque.agent.exceptions.AccessorCreateException;
import six.eared.macaque.agent.javassist.JavaSsistUtil;
import six.eared.macaque.common.util.ClassUtil;
import six.eared.macaque.common.util.StringUtil;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


public class CompatibilityModeAccessorUtil {

    private static final Map<String, ClazzDefinition> LOADED = new HashMap<>();

    /**
     * @param className          外部类类名
     * @param classNameGenerator 类名生成器
     * @param deepth             深度
     * @return
     */
    public static ClazzDefinition createAccessor(String className, AccessorClassNameGenerator classNameGenerator, int deepth) {
        if (LOADED.containsKey(className)) {
            return LOADED.get(className);
        }
        String accessorName = classNameGenerator.generate(className);
        try {
            ClazzDefinition clazzDefinition = AsmUtil.readOriginClass(className);
            String superClassName = clazzDefinition.getSuperClassName();
            ClazzDefinition superAccessor = null;
            if (deepth > 0) {
                if (StringUtil.isNotEmpty(superClassName)
                        && !isSystemClass(superClassName)) {
                    superAccessor = createAccessor(superClassName, classNameGenerator, --deepth);
                }
            }
            String superAccessorName = tryGetAccessorClassName(superClassName, classNameGenerator);
            JavassistClassBuilder javassistClassBuilder = generateAccessorClass(accessorName, superAccessorName);

            collectAccessibleMethods(clazzDefinition, javassistClassBuilder, superAccessor);
            collectAccessibleFields(clazzDefinition, javassistClassBuilder, superAccessor);
            CompatibilityModeClassLoader.loadClass(javassistClassBuilder.getClassName(), javassistClassBuilder.toByteArray());

            ClazzDefinition accessorDefinition = AsmUtil.readClass(javassistClassBuilder.toByteArray());
            LOADED.put(className, accessorDefinition);
            return accessorDefinition;
        } catch (Exception e) {
            throw new AccessorCreateException(e);
        }
    }


    /**
     * @param accessorName
     * @param superAccessorName
     * @return
     */
    private static JavassistClassBuilder generateAccessorClass(String accessorName, String superAccessorName) throws NotFoundException, CannotCompileException {
        boolean containSupper = superAccessorName != null;
        JavassistClassBuilder javassistClassBuilder
                = JavaSsistUtil.defineClass(Modifier.PUBLIC, accessorName, superAccessorName, null);
        javassistClassBuilder.defineField("public static final MethodHandles$Lookup LOOKUP = MethodHandles.lookup();");
        if (!containSupper) {
            javassistClassBuilder.defineField(Modifier.PUBLIC | AccessFlag.SYNTHETIC, "this$0", "java.lang.Object");
        }
        javassistClassBuilder.defineConstructor(String.format("public %s(Object this$0) { %s }",
                ClassUtil.toSimpleName(accessorName), containSupper ? "super(this$0);" : "this.this$0=this$0;"));
        return javassistClassBuilder;
    }

    /**
     * @param className
     * @param classNameGenerator
     * @return
     */
    private static String tryGetAccessorClassName(String className, AccessorClassNameGenerator classNameGenerator) {
        if (LOADED.containsKey(className)) {
            return LOADED.get(className).getClassName();
        }
        return null;
    }

    private static void collectAccessibleMethods(ClazzDefinition definition, JavassistClassBuilder accessorBuilder, ClazzDefinition superAccessor) {
        try {
            // my all method
            for (AsmMethod method : definition.getAsmMethods()) {
                if (method.isConstructor() || method.isClinit()) {
                    continue;
                }
                // 私有方法
                if (method.isPrivate()) {
                    invokeSpecial(definition.getClassName(), definition.getClassName(), method, accessorBuilder);
                    continue;
                }
                // 继承而来 （如果自己重写了父类的方法, 就保存父类的字节码，防止 super调用）
                boolean inherited = inherited(definition.getSuperClassName(), method.getMethodName(), method.getDesc());
                if (inherited && superAccessor != null) {
                    continue;
                }
                // 不是继承而来的 或者 继承来的但是没有父accessor, 就生成方法调用
                invokerVirtual(accessorBuilder, definition.getClassName(), method);
            }

            // 收集父类中所有可以访问到的方法
            Set<String> unique = new HashSet<>();
            ClazzDefinition superClassDefinition = AsmUtil.readOriginClass(definition.getSuperClassName());
            while (superClassDefinition != null) {
                for (AsmMethod superMethod : superClassDefinition.getAsmMethods()) {
                    if (superMethod.isConstructor() || superMethod.isClinit() || superMethod.isPrivate()) {
                        continue;
                    }
                    String key = superMethod.getMethodName()+"#"+superMethod.getDesc();
                    if (!unique.contains(key)) {
                        unique.add(superMethod.getMethodName()+"#"+superMethod.getDesc());
                        invokeSpecial(definition.getClassName(), superClassDefinition.getClassName(), superMethod, accessorBuilder);
                    }
                }
                if (superAccessor != null || superClassDefinition.getClassName().equals("java.lang.Object") || superClassDefinition.getSuperClassName() == null) {
                    break;
                }
                superClassDefinition = AsmUtil.readOriginClass(superClassDefinition.getSuperClassName());
            }
            // default method in interface class
            if (Environment.getJdkVersion() > 7) {

            }
        } catch (Exception e) {
            throw new AccessorCreateException(e);
        }
    }

    private static void collectAccessibleFields(ClazzDefinition definition, JavassistClassBuilder javassistClassBuilder, ClazzDefinition superAccessor) {
        try {
            // my all field
            for (AsmField asmField : definition.getAsmFields()) {
                getField(asmField, definition.getClassName(), javassistClassBuilder);
            }
            // non private field in super class


        } catch (Exception e) {
            throw new AccessorCreateException(e);
        }
    }

    private static void getField(AsmField asmField, String owner, JavassistClassBuilder javassistClassBuilder) throws CannotCompileException {
        Type fieldType = Type.getType(asmField.getDesc());
        String type = fieldType.getClassName();
        String name = asmField.getFieldName();
        String unpacking = getUnpacking(fieldType);
        String body = null;

        if (asmField.isPrivate()) {
            body = "Field field = " + owner + ".class.getDeclaredField(\"" + name + "\"); field.setAccessible(true);" +
                    "return ((" + type + ") Util." + unpacking + "(field.get(this$0)));";
        } else {
            body = "return ((" + owner + ") this$0)." + name + ";";
        }
        javassistClassBuilder.defineMethod(String.format("public %s "+ Accessor.FIELD_GETTER_PREFIX+"%s() { %s }", type, name, body));
    }

    private static void invokerVirtual(JavassistClassBuilder javassistClassBuilder, String this0Class,
                                       AsmMethod method) throws CannotCompileException {
        String methodName = method.getMethodName();
        Type methodType = Type.getMethodType(method.getDesc());
        Type[] args = methodType.getArgumentTypes();

        String rType = methodType.getReturnType().getClassName();
        String[] argVars = IntStream.range(0, args.length).mapToObj(i -> "var_" + i).toArray(String[]::new);

        String declare = String.format("public %s %s(%s)",
                rType, methodName, IntStream.range(0, args.length).mapToObj(i -> args[i].getClassName() + " " + argVars[i]).collect(Collectors.joining(",")));

        String body = (rType.equals("void") ? "" : "return (" + rType + ")")
                + " ((" + this0Class + ") this$0)." + methodName + "(" + String.join(",", argVars) + ");";
        javassistClassBuilder.defineMethod(declare + "{" + body + "}");
    }

    /**
     * @param this0Class   this0Class
     * @param method       生成的方法
     * @param javassistClassBuilder 构造器
     */
    private static void invokeSpecial(String this0Class, String methodOwner, AsmMethod method, JavassistClassBuilder javassistClassBuilder) throws CannotCompileException {
        String methodName = method.getMethodName();
        Type methodType = Type.getMethodType(method.getDesc());
        String rType = methodType.getReturnType().getClassName();

        Type[] args = methodType.getArgumentTypes();
        String[] argVars = IntStream.range(0, args.length).mapToObj(i -> "var_" + i).toArray(String[]::new);
        String argsClassDeclare = Arrays.stream(args).map(type -> type.getClassName() + ".class").collect(Collectors.joining(","));
        String argsDeclare = IntStream.range(0, args.length).mapToObj(i -> args[i].getClassName() + " " + argVars[i])
                .collect(Collectors.joining(","));
        String[] packingArgs = Arrays.stream(argVars).map(a -> "Util.packing(" + a + ")").toArray(String[]::new);
        String unpacking = getUnpacking(methodType.getReturnType());

        StringBuilder methodSrc = new StringBuilder("public " + rType + " super_" + methodName + "(" + argsDeclare + ") {").append("\n")
                .append("MethodType type = MethodType.methodType(" + rType + ".class,new Class[]{" + argsClassDeclare + "});").append("\n")
                .append("MethodHandle mh = LOOKUP.findSpecial(" + this0Class + ".class,\"" + methodName + "\",type," + methodOwner + ".class).bindTo(this$0);").append("\n")
                .append(unpacking != null ? "return (" + rType + ")" : "")
                .append(unpacking != null ? "Util." + unpacking + "(" : "(")
                .append("mh.invoke(new Object[] {" + String.join(",", packingArgs) + "}));").append("\n")
                .append("}");
        javassistClassBuilder.defineMethod(methodSrc.toString());
    }

    /**
     * @param superClass
     * @param methodName
     * @param methodDesc
     * @return 返回这个方法是否继承而来的方法
     */
    private static boolean inherited(String superClass, String methodName, String methodDesc)
            throws ClassNotFoundException, IOException {
        while (StringUtil.isNotEmpty(superClass)
                && !superClass.equals("java.lang.Object")) {
            ClazzDefinition definition = AsmUtil.readOriginClass(superClass);
            if (definition.hasMethod(methodName, methodDesc)) {
                return true;
            }
            superClass = definition.getSuperClassName();
        }
        return false;
    }

    public static boolean isSystemClass(String className) {
        if (className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("sun.")) {
            return true;
        }
        if (className.contains(".internal.") || className.contains(".reflect.") || className.contains(".lang.")
                || className.contains(".io.") || className.contains(".net.")) {
            return true;
        }
        if (className.contains("java$") || className.contains("javax$") || className.contains("sun$")) {
            return true;
        }
        return false;
    }

    public static String getUnpacking(Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
                return "unpack_boolean";
            case Type.CHAR:
                return "unpack_char";
            case Type.BYTE:
                return "unpack_byte";
            case Type.SHORT:
                return "unpack_short";
            case Type.INT:
                return "unpack_int";
            case Type.FLOAT:
                return "unpack_float";
            case Type.LONG:
                return "unpack_long";
            case Type.DOUBLE:
                return "unpack_double";
            case Type.VOID:
                return null;
            default:
                return "unpack_object";
        }
    }
}
