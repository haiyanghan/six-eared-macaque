package six.eared.macaque.agent.asm2.enhance;

public class SimpleClassNameGenerator implements ClassNameGenerator {


    @Override
    public String generate(String className, String methodName) {
        return className + "$$Macaque_" + methodName;
    }
}