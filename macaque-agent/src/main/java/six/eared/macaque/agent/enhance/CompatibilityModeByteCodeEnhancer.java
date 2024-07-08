package six.eared.macaque.agent.enhance;

import javassist.CannotCompileException;
import javassist.NotFoundException;
import six.eared.macaque.agent.accessor.CompatibilityModeAccessorUtil;
import six.eared.macaque.agent.asm2.AsmClassBuilder;
import six.eared.macaque.agent.asm2.AsmMethod;
import six.eared.macaque.agent.asm2.AsmUtil;
import six.eared.macaque.agent.asm2.ClassIncrementUpdate;
import six.eared.macaque.agent.asm2.classes.AsmMethodVisitorCaller;
import six.eared.macaque.agent.asm2.classes.ClassVisitorDelegation;
import six.eared.macaque.agent.asm2.classes.ClazzDefinition;
import six.eared.macaque.agent.enums.CorrelationEnum;
import six.eared.macaque.agent.exceptions.EnhanceException;
import six.eared.macaque.asm.ClassWriter;
import six.eared.macaque.asm.MethodVisitor;
import six.eared.macaque.asm.Opcodes;
import six.eared.macaque.asm.Type;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


public class CompatibilityModeByteCodeEnhancer {


    public static List<ClassEnhanceChangeRecord> enhance(List<ClazzDefinition> definitions) throws IOException, ClassNotFoundException,
            NotFoundException, CannotCompileException {

        List<ClassIncrementUpdate> classIncrementUpdates = new ArrayList<>();
        for (ClazzDefinition definition : definitions) {
            // 准备
            ClassIncrementUpdate incrementUpdate = prepare(definition);
            classIncrementUpdates.add(incrementUpdate);
        }

        List<ClassEnhanceChangeRecord> changed = new ArrayList<>();
        // 转换
        for (ClassIncrementUpdate updateInfo : classIncrementUpdates) {
            ClassEnhanceChangeRecord changeRecord = bytecodeConvert(updateInfo);
            changed.add(changeRecord);
        }
        return changed;
    }

    private static ClassIncrementUpdate prepare(ClazzDefinition definition) throws IOException, ClassNotFoundException {
        ClazzDefinition accessor = createAccessor(definition.getClassName());

        ClazzDefinition originClass = AsmUtil.readOriginClass(definition.getClassName());
        ClassIncrementUpdate incrementUpdate = new ClassIncrementUpdate(definition, accessor);
        for (AsmMethod asmMethod : originClass.getAsmMethods()) {
            if (asmMethod.isConstructor() || asmMethod.isClinit()) continue;
            AsmMethod method = definition.getMethod(asmMethod.getMethodName(), asmMethod.getDesc());
            if (method == null || method.isStatic() ^ asmMethod.isStatic()) {
                incrementUpdate.addDeleted(asmMethod);
            }
        }
        for (AsmMethod asmMethod : definition.getAsmMethods()) {
            if (asmMethod.isConstructor() || asmMethod.isClinit()) continue;
            AsmMethod method = originClass.getMethod(asmMethod.getMethodName(), asmMethod.getDesc());
            if (method == null || method.isStatic() ^ asmMethod.isStatic()) {
                incrementUpdate.addNew(asmMethod);
                MethodBindManager.createMethodBindInfo(definition.getClassName(), asmMethod, accessor.getClassName());
            }
        }
        return incrementUpdate;
    }


    private static ClassEnhanceChangeRecord bytecodeConvert(ClassIncrementUpdate classUpdateInfo) {
        byte[] newByteCode = generateNewByteCode(classUpdateInfo);
        ClassEnhanceChangeRecord changeRecord = new ClassEnhanceChangeRecord(classUpdateInfo.getClassName(), classUpdateInfo.getClazzDefinition(), newByteCode);
        if (classUpdateInfo.getNewMethods() == null) {
            return changeRecord;
        }

        for (AsmMethod newMethod : classUpdateInfo.getNewMethods()) {
            MethodBindInfo bindInfo = MethodBindManager.getBindInfo(newMethod.getClassName(), newMethod.getMethodName(), newMethod.getDesc(), newMethod.isStatic());
            AsmMethodVisitorCaller visitorCaller = bindInfo.getVisitorCaller();
            if (visitorCaller == null || visitorCaller.isEmpty()) {
                throw new EnhanceException("read new method error");
            }
            AsmClassBuilder classBuilder = AsmUtil.defineClass(Opcodes.ACC_PUBLIC, bindInfo.getBindClass(), null, null, null)
                    .defineMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                            bindInfo.getBindMethod(), bindInfo.getBindMethodDesc(),
                            newMethod.getExceptions(), newMethod.getMethodSign())
                    .accept(writer -> visitorCaller.accept(new BindMethodWriter(writer, newMethod, bindInfo)))
                    .end();
            ClazzDefinition bindClazzDefinition = classBuilder.toDefinition();
            if (bindInfo.isLoaded()) {
                changeRecord.addCorrelationClasses(CorrelationEnum.METHOD_BIND, bindClazzDefinition);
            } else {
                CompatibilityModeClassLoader.loadClass(bindInfo.getBindClass(), bindClazzDefinition.getByteArray());
                bindInfo.setLoaded(true);
            }
        }
        return changeRecord;
    }

    /**
     * 生成新的字节码
     */
    private static byte[] generateNewByteCode(ClassIncrementUpdate classIncrementUpdate) {
        ClazzDefinition definition = classIncrementUpdate.getClazzDefinition();
        ClassWriter classWriter = new ClassWriter(0);
        definition.revisit(new ClassVisitorDelegation(classWriter) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodBindInfo bindInfo = MethodBindManager
                        .getBindInfo(definition.getClassName(), name, desc, (access & Opcodes.ACC_STATIC) > 0);
                if (bindInfo == null) {
                    MethodVisitor writer = super.visitMethod(access, name, desc, signature, exceptions);
                    return new InvokeCodeConvertor(writer);
                }
                return bindInfo.getVisitorCaller().createProxyObj();
            }

            @Override
            public void visitEnd() {
                if (classIncrementUpdate.getDeletedMethods() != null) {
                    for (AsmMethod method : classIncrementUpdate.getDeletedMethods()) {
                        // 将类上面需要删除的方法， 删掉
                        MethodVisitor methodWrite = super.visitMethod(method.getModifier(), method.getMethodName(), method.getDesc(),
                                method.getMethodSign(), method.getExceptions());
                        int lvblen = AsmUtil.calculateLvbOffset(method.isStatic(), Type.getArgumentTypes(method.getDesc()));
                        methodWrite.visitMaxs(lvblen + 3, lvblen);
                        AsmUtil.throwNoSuchMethod(methodWrite, method.getMethodName());
                    }
                }
                super.visitEnd();
            }
        });
        return classWriter.toByteArray();
    }

    /**
     * 创建访问器
     *
     * @param className
     */
    private static ClazzDefinition createAccessor(String className) {
        // 计算深度
        int deepth = 5;
        ClazzDefinition accessor = CompatibilityModeAccessorUtil.createAccessor(className, new AccessorClassNameGenerator(), deepth);
        return accessor;
    }
}