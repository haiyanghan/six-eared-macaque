package six.eared.macaque.agent.test;

public class EarlyClass extends AbsEarlyClass {

    public String returnNull() {
        return null;
    }
    public String test1() {
        System.out.println("test1");
        int a = 19;
        class inner {
            public void accept() {
                test4();
                System.out.println(a);
            }
        }
        new inner().accept();
        return "test1";
    }

    public String test2() {
        System.out.println("test2");
        return "test2";
    }

    public final String test3() {
        System.out.println("test3");
        return "test3";
    }

    private final String test4() {
        System.out.println("test4");
        return "test4";
    }

    public class Macaque_Accessor {
        public String test1() {
            return EarlyClass.this.test1();
        }

        public String test2() {
            return EarlyClass.this.test2();
        }

        public String test3() {
            return EarlyClass.this.test3();
        }
    }
}