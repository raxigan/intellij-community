// "Convert field to local variable in method 'test'" "true-preview"
class Foo {
  void test() {
    class Bar {

        void test() {
        x = 2; // could be local
        System.out.println(x);
      }
    }
  }
}