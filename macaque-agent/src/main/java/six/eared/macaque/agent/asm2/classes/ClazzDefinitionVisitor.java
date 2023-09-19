package six.eared.macaque.agent.asm2.classes;


import six.eared.macaque.agent.asm2.AsmField;
import six.eared.macaque.agent.asm2.AsmMethod;
import six.eared.macaque.asm.*;

public class ClazzDefinitionVisitor extends ClassVisitor {

    private AsmMethodVisitor methodVisitor;

    private AsmFieldVisitor fieldVisitor;

    private ClazzDefinition definition = null;

    private boolean reuse = false;

    /**
     * reuse
     */
    public ClazzDefinitionVisitor() {
        super(Opcodes.ASM4);
        this.reuse = true;
    }

    /**
     * not reuse
     * @param methodVisitor
     * @param fieldVisitor
     */
    public ClazzDefinitionVisitor(AsmMethodVisitor methodVisitor, AsmFieldVisitor fieldVisitor) {
        super(Opcodes.ASM4, new ClassWriter(0));
        this.methodVisitor = methodVisitor;
        this.fieldVisitor = fieldVisitor;
    }

    public ClazzDefinition getDefinition() {
        return definition;
    }

    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        if (this.reuse) {
            this.cv = new ClassWriter(0);
        }
        if (this.cv == null) {
            throw new RuntimeException("cw is null");
        }
        this.cv.visit(version, access, name, signature, superName, interfaces);

        definition = new ClazzDefinition();
        definition.setClassName(name.replaceAll("/", "."));
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
        AsmField asmField = AsmField.AsmFieldBuilder
                .builder()
                .modifier(access)
                .fieldName(name)
                .fieldSign(signature)
                .build();

        if (this.fieldVisitor == null) {
            this.definition.addAsmField(asmField);
            return this.cv.visitField(access, name, desc, signature, value);
        }

        return this.fieldVisitor.visitField(asmField, this.definition, (ClassWriter) this.cv);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        AsmMethod asmMethod = AsmMethod.AsmMethodBuilder
                .builder()
                .modifier(access)
                .methodName(name)
                .desc(desc)
                .methodSign(signature)
                .exceptions(exceptions)
                .build();

        if (this.methodVisitor == null) {
            this.definition.addAsmMethod(asmMethod);
            return this.cv.visitMethod(access, name, desc, signature, exceptions);
        }

        return this.methodVisitor.visitMethod(asmMethod, this.definition, (ClassWriter) this.cv);
    }

    @Override
    public void visitBytes(byte[] bytes) {
        this.definition.setOriginData(bytes);
    }

    public void visitEnd() {
        if (this.methodVisitor != null) {
            this.methodVisitor.visitEnd();
        }

        ClassWriter writer = ClassWriter.class.cast(this.cv);
        this.definition.setByteCode(writer.toByteArray());
        if (!this.reuse) {
            this.cv = null;
        }
    }

    public boolean isReuse() {
        return reuse;
    }
}
