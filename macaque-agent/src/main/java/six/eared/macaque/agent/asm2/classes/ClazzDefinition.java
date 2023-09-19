package six.eared.macaque.agent.asm2.classes;

import six.eared.macaque.agent.asm2.AsmField;
import six.eared.macaque.agent.asm2.AsmMethod;

import java.util.ArrayList;
import java.util.List;

public class ClazzDefinition implements Cloneable {

    private String className;

    private byte[] originData;

    private byte[] byteCode;

    private List<AsmMethod> asmMethods = new ArrayList<>();

    private List<AsmField> asmFields = new ArrayList<>();

    public ClazzDefinition() {

    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public void addAsmMethod(AsmMethod method) {
        asmMethods.add(method);
    }

    public List<AsmMethod> getAsmMethods() {
        return asmMethods;
    }

    public void addAsmField(AsmField asmField) {
        asmFields.add(asmField);
    }

    public List<AsmField> getAsmFields() {
        return asmFields;
    }

    public byte[] getByteCode() {
        return byteCode;
    }

    public void setByteCode(byte[] byteCode) {
        this.byteCode = byteCode;
    }

    @Override
    public ClazzDefinition clone() {
        try {
            return (ClazzDefinition) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] getOriginData() {
        return originData;
    }

    public void setOriginData(byte[] originData) {
        this.originData = originData;
    }

    public boolean hasMethod(AsmMethod method) {
        return asmMethods.stream()
                .anyMatch(item -> item.equals(method));
    }
}
